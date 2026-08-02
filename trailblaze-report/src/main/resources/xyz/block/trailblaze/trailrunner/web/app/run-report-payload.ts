// Generic-event payload helpers + gzip transport inflation for the interactive run report:
// pretty-printing/normalizing embedded event payloads, and inflating the gzip+base64 blobs the
// bun driver packs (eventsGz / deviceLogGz / networkGz — see packEvents & friends in
// run-report-cli.ts). Used by both the viewer at render time and the report assembly.
// Shared contract types come from the ambient run-report-types.d.ts (see its header for why it
// stays ambient rather than becoming module exports).

// Parse a possibly-multiply-JSON-encoded generic event payload, one quoting layer at a time.
// Manually replacing escape sequences instead can double-unescape producer-controlled text and
// change its meaning.
function parseEventJsonish(value, depth = 0) {
  if (depth > 8 || value == null) return value;
  if (typeof value !== 'string') {
    if (Array.isArray(value)) return value.map((v) => parseEventJsonish(v, depth + 1));
    if (typeof value === 'object') {
      const out = {};
      Object.keys(value).forEach((k) => { out[k] = parseEventJsonish(value[k], depth + 1); });
      return out;
    }
    return value;
  }
  const raw = value.trim();
  if (!raw) return value;
  const candidates = [raw];
  if (raw.indexOf('\\"') >= 0 || raw.indexOf('\\\\') >= 0) candidates.push(`"${raw}"`);
  for (const candidate of candidates) {
    try {
      const parsed = JSON.parse(candidate);
      return parseEventJsonish(parsed, depth + 1);
    } catch (_) {}
  }
  return value;
}

function eventValueText(value) {
  if (value == null) return '';
  if (typeof value === 'string') return value;
  if (typeof value === 'number' || typeof value === 'boolean') return String(value);
  return JSON.stringify(value);
}

// Summary metadata (semantic label + headline fields) for one generic event. Results are cached
// on the function object (WeakMap keyed by the event record) so repeated renders of the same
// payload don't re-walk it.
function normalizeEventPayload(event) {
  const self = normalizeEventPayload as any;
  const cache: WeakMap<object, any> = self.cache || (self.cache = new WeakMap());
  const cached = cache.get(event);
  if (cached) return cached;
  const kinds: Array<[string, string[]]> = [
    ['Event', ['event', 'eventname', 'eventvalue', 'name', 'label', 'title', 'message']],
    ['Action', ['action', 'actiontext', 'blockeraction', 'cdfaction']],
    ['Entity', ['entity', 'cdfentity', 'namespace']],
    ['Path', ['path', 'urlpath', 'finalpath', 'uniquefinalpath']],
    ['Status', ['status', 'statuscode', 'code']],
    ['Method', ['method']],
    ['Journey', ['journey', 'journeyname', 'flow', 'clientscenario']],
    ['ID', ['id', 'messageuuid', 'blockerid', 'flowtoken']],
  ];
  const raw = String(event.d == null ? '' : event.d);
  const parsed = parseEventJsonish(raw);
  const found = new Map();
  const queue = [{ value: parsed, depth: 0 }];
  let visited = 0;
  while (queue.length && visited++ < 240) {
    const current = queue.shift();
    const value = current.value;
    if (current.depth > 6 || value == null || typeof value !== 'object') continue;
    if (Array.isArray(value)) {
      value.slice(0, 40).forEach((item) => queue.push({ value: item, depth: current.depth + 1 }));
      continue;
    }
    Object.keys(value).slice(0, 80).forEach((key) => {
      const child = value[key];
      const normalized = key.toLowerCase().replace(/[^a-z0-9]/g, '');
      if (!found.has(normalized) && child != null && child !== '') found.set(normalized, child);
      if (child && typeof child === 'object') queue.push({ value: child, depth: current.depth + 1 });
    });
  }
  const fields = [];
  kinds.forEach(([label, names]) => {
    const name = names.find((candidate) => found.has(candidate));
    const text = name ? eventValueText(found.get(name)) : '';
    if (text && !fields.some((field) => field.value === text)) fields.push({ label, value: text });
  });
  const labelField = kinds[0][1].find((name) => found.has(name));
  const normalized = { raw, parsed, fields: fields.slice(0, 8), semanticLabel: labelField ? eventValueText(found.get(labelField)) : '' };
  cache.set(event, normalized);
  return normalized;
}

// Full pretty-printed payload for one generic event. Payloads are embedded untruncated and can be
// huge, so the viewer only calls this when a payload expando is actually opened (lazy-body wiring).
function eventPrettyText(event) {
  const { raw, parsed } = normalizeEventPayload(event);
  try { return parsed !== raw ? JSON.stringify(parsed, null, 2) : raw; } catch (_) { return raw; }
}

// Pretty-print one FormattedRow.raw payload for the expanded row body: nested JSON-in-string
// values are recursively parsed (parseEventJsonish) so bodies read as real JSON, not escaped text.
// Embedded compact; only built when a row is actually expanded (lazy-body wiring).
function rawPrettyText(value) {
  const parsed = parseEventJsonish(value);
  if (typeof parsed === 'string') return parsed;
  try { return JSON.stringify(parsed, null, 2); } catch (_) { return String(value); }
}

// Inflate one gzip+base64 blob (the transport the driver's packEvents/packDeviceLog/packNetwork
// emit — see run-report-cli.ts) back to text. Null when the blob is malformed or the runtime
// lacks DecompressionStream, so callers can fall back to a "can't decompress" note instead of a
// broken tab.
async function inflateGzText(b64: string): Promise<string | null> {
  try {
    const bin = atob(b64);
    const bytes = new Uint8Array(bin.length);
    for (let i = 0; i < bin.length; i++) bytes[i] = bin.charCodeAt(i);
    const stream = new Blob([bytes]).stream().pipeThrough(new DecompressionStream('gzip'));
    return await new Response(stream).text();
  } catch (_) {
    return null;
  }
}

// Inflate one gzip+base64 blob carrying a JSON array (events, network) back to the parsed array.
// Null when inflation fails or the payload isn't an array.
async function inflateGzJsonArray(b64: string): Promise<unknown[] | null> {
  const text = await inflateGzText(b64);
  if (text == null) return null;
  try {
    const parsed = JSON.parse(text);
    return Array.isArray(parsed) ? parsed : null;
  } catch (_) {
    return null;
  }
}

// Inflate a session's compressed events payload (SessionPayload.eventsGz: gzip'd EventStream[]
// JSON as base64 — see packEvents in run-report-cli.ts).
async function inflateEventsGz(b64: string): Promise<EventStream[] | null> {
  return (await inflateGzJsonArray(b64)) as EventStream[] | null;
}

// JSON.stringify for a payload embedded inside a <script> element: escape `<` so a literal
// `</script>` inside any string can't close the script element early (an HTML-parser rule that
// applies to every script type, including application/json). The escape is JSON-transparent, so
// JSON.parse returns the original strings.
function toInertJson(value: unknown): string {
  return JSON.stringify(value).replace(/</g, '\\u003c');
}

// The static #tb-boot loader markup: the first thing a standalone report paints, while the payload
// is still being parsed. Shared by buildMultiReportHtml (fresh documents) and the viewer's export
// path (re-seeded into the exported clone) so the two copies can't drift.
function tbBootLoaderHtml(heading: string): string {
  const safe = String(heading == null ? '' : heading).replace(/[<>&"]/g, (c) => ({ '<': '&lt;', '>': '&gt;', '&': '&amp;', '"': '&quot;' }[c]));
  return `<div id="tb-boot" role="status"><div class="tb-boot-spinner" aria-hidden="true"></div><div class="tb-boot-title">${safe}</div><div class="tb-boot-note">Loading report…</div></div>`;
}

// Re-key the hoisted sprite chunk for an exported subset of sessions: #tb-sprites is keyed by
// session index, and indices shift when a subset is exported (session 3 of 5 becomes session 0 of
// 1). `spriteFor(video, originalIndex)` resolves one session's sheet URIs in order (inline
// `video.sprites` first, the hoisted chunk otherwise), so an export of an export round-trips.
function rekeySprites(exported: SessionPayload[], all: SessionPayload[], spriteFor: (video: VideoInfo | null | undefined, sessionIndex: number) => string[]): Record<string, string[]> {
  const sprites: Record<string, string[]> = {};
  exported.forEach((session, i) => { const urls = spriteFor(session.video, all.indexOf(session)); if (urls.some(Boolean)) sprites[String(i)] = urls; });
  return sprites;
}

export { parseEventJsonish, eventValueText, normalizeEventPayload, eventPrettyText, rawPrettyText, inflateGzText, inflateGzJsonArray, inflateEventsGz, toInertJson, tbBootLoaderHtml, rekeySprites };
