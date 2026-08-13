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

// Inflate a session's compressed LLM transcripts (SessionPayload.llmMessagesGz: gzip'd
// LlmTranscripts JSON as base64 — see packLlmMessages in run-report-cli.ts). Null when inflation
// fails or the payload isn't the pooled transcript shape — including any per-call entry that
// isn't itself an array, so a malformed blob degrades to the "could not decompress" note instead
// of throwing during render/export.
async function inflateLlmMessagesGz(b64: string): Promise<LlmTranscripts | null> {
  const text = await inflateGzText(b64);
  if (text == null) return null;
  try {
    const parsed = JSON.parse(text);
    const wellFormed = parsed && Array.isArray(parsed.texts) && Array.isArray(parsed.calls)
      && parsed.calls.every((call) => Array.isArray(call));
    return wellFormed ? parsed : null;
  } catch (_) {
    return null;
  }
}

// The mirror of inflateGzText for the producers that run in a browser (the in-app Share button,
// the zip pipeline's HTML export): gzip text and return it base64-encoded, the same transport the
// bun driver's packGz emits. Null when the runtime lacks CompressionStream or compression fails,
// so callers can fall back to embedding the payload inline.
async function deflateGzText(text: string): Promise<string | null> {
  try {
    if (typeof CompressionStream === 'undefined') return null;
    const stream = new Blob([text]).stream().pipeThrough(new CompressionStream('gzip'));
    const bytes = new Uint8Array(await new Response(stream).arrayBuffer());
    let bin = '';
    // Chunked fromCharCode: one spread of a multi-megabyte payload would blow the arg-count limit.
    for (let i = 0; i < bytes.length; i += 0x8000) bin += String.fromCharCode.apply(null, bytes.subarray(i, i + 0x8000) as unknown as number[]);
    return btoa(bin);
  } catch (_) {
    return null;
  }
}

// Inflate one gzip+base64 blob carrying a JSON object map (SessionPayload.hierarchiesGz) back to
// the parsed record. Null when inflation fails or the payload isn't a plain object.
async function inflateGzJsonRecord(b64: string): Promise<Record<string, unknown> | null> {
  const text = await inflateGzText(b64);
  if (text == null) return null;
  try {
    const parsed = JSON.parse(text);
    return parsed && typeof parsed === 'object' && !Array.isArray(parsed) ? parsed : null;
  } catch (_) {
    return null;
  }
}

// ─────────────────────────────────────────────────────────────────────────────
// Display YAML — tool calls render exactly like a trail-file tool entry.
// ─────────────────────────────────────────────────────────────────────────────

// Faithful TypeScript port of TrailblazeYaml.jsonToYaml (trailblaze-models
// commonMain/.../yaml/TrailblazeYaml.kt) — the emitter the WASM report's LLM-actions display
// uses (formatLlmActionsYaml in SessionCombinedView.kt), whose output matches what the recorder
// writes into trail files. The report renders tool calls with it so a transcript entry reads
// exactly like the same call in a trail.yaml / recordingYaml. The self-contained report can't
// reuse the Trail Runner web app's js-yaml (a CDN UMD global; exported reports run offline),
// hence a local port; if the Kotlin emitter's rules change, change these to match.
//
// Kotlin needsYamlQuoting, ported rule for rule: quote empty strings, any ':' / '#' / newline
// anywhere, leading flow/indicator characters, the lowercase YAML keywords, and anything that
// parses as a number. (Deliberately NOT quoted, matching the recorder: leading '-' or '^',
// leading/trailing spaces, mixed-case keywords.)
function yamlNeedsQuote(s: string): boolean {
  return s === ''
    || s.indexOf(':') >= 0 || s.indexOf('#') >= 0 || s.indexOf('\n') >= 0
    || /^[{\["'*&!|>%@]/.test(s)
    || s === 'true' || s === 'false' || s === 'null' || s === '~'
    || s === 'yes' || s === 'no' || s === 'on' || s === 'off'
    || yamlParsesAsNumber(s);
}

// Kotlin's `toLongOrNull() != null || toDoubleOrNull() != null` (Double.parseDouble trims and
// accepts Infinity/NaN spellings).
function yamlParsesAsNumber(s: string): boolean {
  const t = s.trim();
  if (t === '') return false;
  return !Number.isNaN(Number(t)) || t === 'NaN' || /^[+-]?Infinity$/.test(t);
}

// Port of appendJsonElementAsYaml: 2-space indent per level; object values that are non-empty
// containers break onto the next line; array items are `- ` with the first key inlined on the
// dash line; quoted strings escape only backslash, quote, and newline.
function jsonToYaml(value: unknown): string {
  let out = '';
  const scalar = (v: any): string => {
    if (v == null) return 'null';
    if (typeof v === 'boolean' || typeof v === 'number') return String(v);
    const s = String(v);
    if (!yamlNeedsQuote(s)) return s;
    return '"' + s.replace(/\\/g, '\\\\').replace(/"/g, '\\"').replace(/\n/g, '\\n') + '"';
  };
  const isEmptyContainer = (v: any) => Array.isArray(v) ? v.length === 0 : (v != null && typeof v === 'object' && Object.keys(v).length === 0);
  const append = (element: any, indent: number, inlineFirst = false) => {
    if (element == null || typeof element !== 'object') { out += scalar(element); return; }
    const pad = '  '.repeat(indent);
    if (Array.isArray(element)) {
      if (!element.length) { out += '[]'; return; }
      element.forEach((item, i) => {
        out += pad + '- ';
        append(item, indent + 1, true);
        if (i < element.length - 1) out += '\n';
      });
      return;
    }
    const keys = Object.keys(element);
    if (!keys.length) { out += '{}'; return; }
    keys.forEach((key, i) => {
      if (i > 0 || !inlineFirst) out += pad;
      out += key + ':';
      const value = element[key];
      if (value != null && typeof value === 'object' && !isEmptyContainer(value)) {
        out += '\n';
        append(value, indent + 1);
      } else {
        out += ' ';
        append(value, indent + 1);
      }
      if (i < keys.length - 1) out += '\n';
    });
  };
  append(value, 0);
  return out.replace(/\s+$/, '');
}

// The transcript's tool messages arrive as markdown (`**tool**` + a ```json fence — see
// TrailblazeLogger.toTrailblazeLlmMessages); when the fenced (or whole-body) payload parses as
// JSON, render it exactly like the same call in a trail file — `- toolName:` with the args
// indented four columns past the dash — mirroring the WASM report's formatLlmActionsYaml
// composition. A JSON tool RESULT renders as the bare payload (results aren't trail entries);
// prose output returns null and falls through to the raw text.
function transcriptToolCallYaml(m: { role?: string; text?: string; toolName?: string | null } | null | undefined): string | null {
  if (!m) return null;
  const role = String(m.role || '');
  const isCall = role === 'tool_use' || role === 'tool_call';
  if (!isCall && role !== 'tool' && role !== 'function' && role !== 'tool_result') return null;
  const text = String(m.text == null ? '' : m.text);
  const fence = text.match(/```json\s*\n([\s\S]*?)\n?\s*```/);
  const payload = (fence ? fence[1] : text).trim();
  if (!payload || (payload[0] !== '{' && payload[0] !== '[')) return null;
  let parsed;
  try { parsed = JSON.parse(payload); } catch (_) { return null; }
  if (parsed == null || typeof parsed !== 'object') return null;
  const argsYaml = jsonToYaml(parsed);
  if (!isCall || !m.toolName) return argsYaml;
  if (!argsYaml || argsYaml === '{}') return `- ${m.toolName}:`;
  const body = argsYaml.split('\n').filter((line) => line.trim() !== '').map((line) => '    ' + line).join('\n');
  return `- ${m.toolName}:\n${body}`;
}

// Clean display body for a tool RESULT message. TrailblazeLogger wraps tool messages in a
// markdown envelope — a `**toolName**` header plus a ```json fence whose content is often NOT
// JSON but the executor's prose ("**Executed `tap`.** Typed 'TKT-1'") — which rendered verbatim
// reads as stray asterisks and doubled fence markers. Parse the envelope instead: drop the
// header (the bubble header already names the tool), unwrap the fence, render structured output
// as trail-file YAML and prose with the markdown bold/code markers stripped. `raw` carries the
// verbatim text whenever the cleaned body differs, so the UI can offer it behind an expander and
// no fidelity is lost. Null for non-result roles or when nothing displayable remains.
function transcriptToolResultDisplay(m: { role?: string; text?: string; toolName?: string | null } | null | undefined): { text: string; raw: string | null } | null {
  if (!m) return null;
  const role = String(m.role || '');
  if (role !== 'tool' && role !== 'function' && role !== 'tool_result') return null;
  const rawText = String(m.text == null ? '' : m.text);
  let body = rawText.replace(/^\s*\*\*[^*\n]+\*\*\s*\n+/, '');
  const fence = body.match(/```[a-z]*\s*\n([\s\S]*?)\n?\s*```/);
  if (fence) body = fence[1];
  body = body.trim();
  let parsed = null;
  if (body && (body[0] === '{' || body[0] === '[')) { try { parsed = JSON.parse(body); } catch (_) { parsed = null; } }
  const text = parsed != null && typeof parsed === 'object'
    ? jsonToYaml(parsed)
    : body.replace(/\*\*/g, '').replace(/`([^`\n]*)`/g, '$1').trim();
  if (!text) return null;
  return { text, raw: text === rawText ? null : rawText };
}

// JSON.stringify for a payload embedded inside a <script> element: escape `<` so a literal
// `</script>` inside any string can't close the script element early (an HTML-parser rule that
// applies to every script type, including application/json). The escape is JSON-transparent, so
// JSON.parse returns the original strings.
function toInertJson(value: unknown): string {
  return JSON.stringify(value).replace(/</g, '\\u003c');
}

// The `</script>`-safety rule above applies to embedded CODE as well as data, and code can't be
// `\u003c`-escaped — so neutralize just the closer: inside a JS string, regex, or comment,
// `<\/script` is read exactly as `</script` was, while the HTML parser no longer sees an end tag.
// (`String.raw` is the one context where the backslash would survive into the value; no bundle we
// embed contains a raw-string `</script`.)
//
// Applied to every code bundle a generated document inlines. Without it an ordinary future comment
// containing `</script>` in any embedded source would truncate the page at that byte, and no guard
// downstream could see it: the markers publish checks look for sit near the top of the document, so
// a truncated page still matches them.
function inertScriptBody(code: string): string {
  return String(code == null ? '' : code).replace(/<\/script/gi, '<\\/script');
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

export { parseEventJsonish, eventValueText, normalizeEventPayload, eventPrettyText, rawPrettyText, inflateGzText, deflateGzText, inflateGzJsonArray, inflateEventsGz, inflateLlmMessagesGz, inflateGzJsonRecord, jsonToYaml, transcriptToolCallYaml, transcriptToolResultDisplay, toInertJson, inertScriptBody, tbBootLoaderHtml, rekeySprites };
