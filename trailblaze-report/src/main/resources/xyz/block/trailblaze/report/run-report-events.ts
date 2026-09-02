// Session-events pipeline for the headless report driver (run-report-cli.ts): decodes
// `events/<name>.ndjson` lines, routes each stream through an optional formatter module
// (EventStreamFormatter in run-report-types.d.ts), and clamps everything a formatter returns so a
// buggy or chatty formatter can't balloon the self-contained report.
//
// Kept separate from the driver so the whole path is testable with raw NDJSON lines and no
// filesystem (run-report-events.test.ts). RunReportGenerator stages this file beside the driver;
// formatter modules are staged the same way and required at runtime by file name.
//
// The renderer knows nothing about any specific producer: formatters turn a stream into
// FormattedRow data (never HTML), and streams without a formatter keep the generic last-N,
// truncated-preview shape the viewer has always rendered.

// This pipeline itself embeds event payloads IN FULL — no last-N event cap and no preview
// truncation. Report size is kept in check by the driver (it gzips a session's events into
// `eventsGz` past an inline threshold and enforces a loud total budget — see run-report-cli.ts),
// by lazy payload rendering in the viewer, and by the formatters themselves: a formatter receives
// the session outcome (FormatterContext) and may apply a per-stream size budget to raw payloads of
// PASSED sessions (grep REPORT_SIZE_BUDGET for each stream's policy). Sessions that didn't pass
// always keep full payloads. The only per-value clamp here is a pathological-input backstop far
// above any legitimate payload.
export const MAX_VALUE_CHARS = 10_000_000;

// Per-row output budgets. UI-chrome parts (label, badges, summary fields) stay tightly bounded so
// a buggy formatter can't wreck the row grid; the raw payloads get the pathological backstop only.
const CAPS = {
  label: 300,
  badgeText: 40,
  badges: 8,
  fieldKey: 120,
  fieldValue: 2000,
  fields: 16,
  raw: 8,
  rows: 100_000,
};

/**
 * `<name>.ndjson` → stream name, or null for a non-events file. A legacy trailing `.json` style
 * segment (`<name>.json.ndjson`, the only style the retired styled format ever wrote) is stripped
 * so old session dirs keep resolving to the same stream names. Locked against the Kotlin
 * `SessionEvents.parseFileName` by session-events-parity-fixtures.json.
 */
export function parseStreamFileName(file: string): string | null {
  if (!file.endsWith(".ndjson")) return null;
  let base = file.slice(0, -".ndjson".length);
  if (base.endsWith(".json")) base = base.slice(0, -".json".length);
  return base || null;
}

/**
 * Decode one NDJSON line. Mirrors the JVM SessionEventsReader envelope rule: a top-level `timeMs`
 * marks the `{ timeMs, data }` envelope (payload is `data`); otherwise the whole object is a bare
 * rich payload ordered by its own `timestampMs` when present. Locked against the JVM reader by
 * session-events-parity-fixtures.json.
 */
export function decodeEventLine(line: string): FormatterEntry | null {
  try {
    const o = JSON.parse(line);
    if (o == null || typeof o !== "object") return null;
    const envelope = typeof o.timeMs === "number";
    const t = envelope ? o.timeMs : typeof o.timestampMs === "number" ? o.timestampMs : null;
    return { t, data: envelope ? (o.data ?? o) : o };
  } catch {
    return null;
  }
}

/** Validate a required formatter module (tolerating a `default` export wrapper). */
export function resolveFormatterModule(mod: unknown): EventStreamFormatter | null {
  const f = ((mod as { default?: unknown })?.default ?? mod) as EventStreamFormatter | null;
  if (!f || typeof f !== "object") return null;
  if (typeof f.id !== "string" || !f.id) return null;
  if (!Array.isArray(f.streams) || !f.streams.length || f.streams.some((s) => typeof s !== "string")) return null;
  if (typeof f.format !== "function") return null;
  return f;
}

/** First formatter owning `name`: exact match, or a `prefix.*` wildcard (dot-anchored). */
export function formatterForStream(
  formatters: EventStreamFormatter[],
  name: string,
): EventStreamFormatter | null {
  return (
    formatters.find((f) =>
      f.streams.some((s) => (s.endsWith(".*") ? name.startsWith(s.slice(0, -1)) : name === s)),
    ) || null
  );
}

// ── Attachment refs ────────────────────────────────────────────────────────────────────────────
// TS mirror of the Kotlin `AttachmentRef` detection contract (trailblaze-models
// commonMain/…/events/AttachmentRef.kt): a producer embeds a media/file reference anywhere in an
// event payload as an object carrying the `"$attachment": true` discriminator plus
// `path` (session-relative), `mimeType`, `sizeBytes`, and an optional `label`. Detection is
// exact-dispatch on the marker — never duck-typed on path+mimeType — with the same field rules as
// the Kotlin side: marker literally boolean `true`, path/mimeType JSON strings, sizeBytes a finite
// NON-NEGATIVE JSON number (truncated to a whole byte count), extra fields tolerated, a non-string
// label read as absent. Locked against the Kotlin implementation by session-events-parity-fixtures.json.

export const ATTACHMENT_MARKER_FIELD = "$attachment";

/** `Long.MAX_VALUE.toDouble()` — the first size the Kotlin contract cannot represent, and so nor may this. */
const LONG_RANGE_EXCLUSIVE_MAX = 9223372036854775808;

/** Exact-dispatch probe of one decoded JSON value; null unless it is a well-formed attachment ref. */
export function attachmentRefOf(value: unknown): AttachmentRef | null {
  if (value == null || typeof value !== "object" || Array.isArray(value)) return null;
  const o = value as Record<string, unknown>;
  if (o[ATTACHMENT_MARKER_FIELD] !== true) return null;
  const { path, mimeType, sizeBytes, label } = o;
  if (typeof path !== "string" || typeof mimeType !== "string") return null;
  // A negative size is bad input, not a very small file: it reaches humanBytes as an empty string
  // and would render a row claiming bytes that cannot exist. A size at or past 2^63 is bad input
  // for the same reason — it cannot be represented as the Kotlin contract's Long, so accepting it
  // here would mean the two languages disagree about what is a ref. Kotlin rejects both identically.
  if (typeof sizeBytes !== "number" || !Number.isFinite(sizeBytes) || sizeBytes < 0 || sizeBytes >= LONG_RANGE_EXCLUSIVE_MAX) return null;
  return { path, mimeType, sizeBytes: Math.trunc(sizeBytes), label: typeof label === "string" ? label : null };
}

// ── Attachment resolution policy ───────────────────────────────────────────────────────────────
// WHAT a detected ref is allowed to become on a report surface, as opposed to what counts as a ref
// at all (above). Every surface that resolves attachment bytes reads these from here — the bun
// driver by direct import, run-payload.js and zip-report-core.js through the same
// run-report-core channel that already supplies `collectStreamAttachmentRefs` — because three
// hand-kept copies of a policy drift silently, and unlike detection there is no parity fixture
// that would notice.

/**
 * Largest attachment inlined as a `data:` URI. Past this the viewer shows its "in the session
 * bundle, not embedded" note: an exported report has to stay a portable single file, not carry
 * arbitrary megabytes of media.
 */
export const ATTACHMENT_INLINE_MAX_BYTES = 512 * 1024;

/** Per-session ceiling on resolved attachments, so one pathological session cannot exhaust a surface. */
export const MAX_ATTACHMENTS_PER_SESSION = 200;

/**
 * Total decompressed bytes one session's attachments may occupy when a surface materializes them
 * up front — today the zip viewer, which inflates each referenced file into an object URL while
 * building the report. The count ceiling alone does not bound this: 200 refs at a few hundred MB
 * each is an archive that decompresses into the tab before a single attachment is opened. Refs past
 * the budget keep their "in the session bundle, not embedded" note.
 */
export const ATTACHMENT_MATERIALIZE_MAX_TOTAL_BYTES = 64 * 1024 * 1024;

/**
 * Total ENCODED bytes the attachments of one standalone report FILE may add to it — the length of
 * the `data:` URIs themselves, not the files behind them, because that is what lands in the HTML.
 * The count ceiling does not bound this: the policy permits 200 files of 512 KiB, which is 100 MiB
 * of media and ~137 MiB base64-encoded, while the daemon's share route refuses HTML over
 * `SHARE_HTML_MAX_BYTES` (64 MiB) — so Share would fail outright on a report well inside every
 * other limit. Half that ceiling, leaving the other half for the trace, logs and screenshots that
 * share the file. Refs past the budget keep their "in the session bundle, not embedded" note.
 *
 * Per FILE, not per session, because the limit it defends is the file's. That distinction only
 * bites where one file holds many sessions: `readAttachments` (the CLI, which writes every session
 * of a run into one HTML) therefore threads a single budget across its session loop, while
 * `collectAttachments` charges per call because each daemon-built document carries exactly one
 * session. A per-session budget in the CLI would let ten sessions embed ten times this ceiling.
 */
export const ATTACHMENT_EMBED_MAX_TOTAL_BYTES = 32 * 1024 * 1024;

/**
 * Read budgets for the event streams themselves, applied by every surface that turns
 * `events/*.ndjson` into an EventStream: the CLI's filesystem walk sizes each file before reading
 * it, and the zip viewer sizes each archive entry before inflating it. A stream is decompressed in
 * full before a single line is decoded, so a small archive holding one highly compressible stream
 * is otherwise an out-of-memory tab. Streams past either budget are skipped, not truncated — half
 * an ndjson file is a lie about what the session recorded.
 */
export const MAX_EVENT_STREAM_BYTES = 64 * 1024 * 1024;
export const MAX_EVENT_STREAMS_TOTAL_CHARS = 256 * 1024 * 1024;

/**
 * MIME types an attachment's bytes may become a `data:`/`blob:` source for — exactly the ones the
 * lightbox hands to a native `<audio>`/`<video>`/`<img>` element, in attribute-safe spellings.
 * Deliberately narrow: a hostile archive must not be able to turn an attachment into a same-origin
 * document.
 */
export const ATTACHMENT_MIME = /^(audio|video|image)\/[a-z0-9][a-z0-9.+-]*$/i;

/**
 * True for a path that stays inside the session directory: relative, forward slashes, no `.` or
 * `..` segment. A traversal-shaped path must not become a `/static` URL the browser normalizes out
 * of the session's own tree, nor an archive lookup that escapes it.
 */
export function isSafeSessionRelativePath(path: string): boolean {
  if (!path || typeof path !== "string" || path.charAt(0) === "/" || path.includes("\\") || path.includes("\0")) return false;
  return path.split("/").every((seg) => seg !== "" && seg !== "." && seg !== "..");
}

/**
 * Depth-first sweep of a decoded event payload for embedded attachment refs, in document order —
 * the mirror of Kotlin `AttachmentRef.findAll`. Does not descend into a matched ref.
 */
export function findAttachmentRefs(value: unknown): AttachmentRef[] {
  const found: AttachmentRef[] = [];
  const walk = (v: unknown) => {
    if (v == null || typeof v !== "object") return;
    if (Array.isArray(v)) { v.forEach(walk); return; }
    const ref = attachmentRefOf(v);
    if (ref) found.push(ref);
    else Object.values(v).forEach(walk);
  };
  walk(value);
  return found;
}

/**
 * Every attachment ref embedded in a session's event streams, in stream order: generic events'
 * payloads (each `d` is the exact JSON.stringify of the decoded payload — re-parsed here; a
 * payload past the size backstop no longer parses and simply contributes nothing) plus formatted
 * rows' raw payloads. Callers dedupe by path as needed.
 */
export function collectStreamAttachmentRefs(streams: EventStream[] | null | undefined): AttachmentRef[] {
  const refs: AttachmentRef[] = [];
  for (const stream of streams || []) {
    for (const e of stream.events || []) {
      try { refs.push(...findAttachmentRefs(JSON.parse(e.d))); } catch { /* truncated/non-JSON payload */ }
    }
    for (const row of stream.rows || []) {
      for (const raw of row.raw || []) refs.push(...findAttachmentRefs(raw));
    }
  }
  return refs;
}

const clampText = (value: unknown, max: number): string => {
  const s = typeof value === "string" ? value : value == null ? "" : String(value);
  return s.length > max ? `${s.slice(0, max)}…` : s;
};

/**
 * Validated `RowField.href`: an absolute http(s) URL within the field-value budget, else null.
 * Validated once here at embed time so the viewer only ever sees renderable link targets (it
 * still re-checks before emitting an anchor — the embedded payload is data, not trusted markup).
 */
export function safeFieldHref(value: unknown): string | null {
  if (typeof value !== "string" || !value || value.length > CAPS.fieldValue) return null;
  try {
    const url = new URL(value);
    return url.protocol === "https:" || url.protocol === "http:" ? url.href : null;
  } catch {
    return null;
  }
}

const clampKv = (kv: unknown, maxEntries: number): RowField[] | null => {
  if (!Array.isArray(kv)) return null;
  const out = kv
    .filter((f) => f && typeof f === "object")
    .slice(0, maxEntries)
    .map((f: { k?: unknown; v?: unknown; href?: unknown }) => {
      const field: RowField = {
        k: clampText(f.k, CAPS.fieldKey),
        v: clampText(f.v, CAPS.fieldValue),
      };
      const href = safeFieldHref(f.href);
      if (href) field.href = href;
      return field;
    })
    .filter((f) => f.k || f.v);
  return out.length ? out : null;
};

// Raw payloads are embedded as JSON VALUES (compact — the viewer pretty-prints on expand). Only a
// pathological entry (unserializable, or past the size backstop) degrades to a (truncated) string.
const clampRawEntry = (value: unknown): unknown => {
  let json: string | undefined;
  try {
    json = JSON.stringify(value);
  } catch {
    json = undefined;
  }
  // Unserializable either way (throw, or stringify-to-undefined: functions, symbols) — degrade to
  // a bounded string so nothing bypasses the backstop.
  if (json === undefined) return clampText(String(value), MAX_VALUE_CHARS);
  return json.length > MAX_VALUE_CHARS ? `${json.slice(0, MAX_VALUE_CHARS)}…` : value;
};

/** Author row → embedded row: validate, serialize structured parts, enforce every budget. */
const clampRow = (row: FormatterRowInput | null | undefined): FormattedRow | null => {
  if (!row || typeof row !== "object" || typeof row.label !== "string" || !row.label) return null;
  const out: FormattedRow = {
    t: typeof row.t === "number" ? row.t : null,
    label: clampText(row.label, CAPS.label),
  };
  if (row.tone === "ok" || row.tone === "warn" || row.tone === "error") out.tone = row.tone;
  if (Array.isArray(row.badges)) {
    const badges = row.badges
      .filter((b) => b && typeof b === "object" && b.text != null && b.text !== "")
      .slice(0, CAPS.badges)
      .map((b) => {
        const badge: RowBadge = { text: clampText(b.text, CAPS.badgeText) };
        if (b.tone === "ok" || b.tone === "warn" || b.tone === "error") badge.tone = b.tone;
        return badge;
      });
    if (badges.length) out.badges = badges;
  }
  const fields = clampKv(row.fields, CAPS.fields);
  if (fields) out.fields = fields;
  if (Array.isArray(row.raw)) {
    const raw = row.raw
      .filter((r) => r != null)
      .slice(0, CAPS.raw)
      .map(clampRawEntry);
    if (raw.length) out.raw = raw;
  }
  return out;
};

/**
 * Run a formatter over a stream's decoded entries. Null (→ generic fallback) when the formatter
 * throws, returns a non-array, or produces no usable rows — a formatter can never lose data, only
 * decline to improve its rendering.
 */
export function formatRows(
  formatter: EventStreamFormatter,
  entries: FormatterEntry[],
  ctx?: FormatterContext,
): FormattedRow[] | null {
  let produced: Array<FormatterRowInput | null | undefined>;
  try {
    produced = formatter.format(entries, ctx ?? { sessionPassed: false });
  } catch {
    return null;
  }
  if (!Array.isArray(produced)) return null;
  const rows = produced.map(clampRow).filter((r): r is FormattedRow => r != null).slice(0, CAPS.rows);
  return rows.length ? rows : null;
}

/**
 * The whole pipeline for one `events/` file: raw lines in, embeddable EventStream out (null when
 * the file isn't a well-formed events stream). Every line is kept — with a matching formatter as
 * netlog-style rows (the formatter sees `ctx` and may size-budget raw payloads of passed
 * sessions), without one as generic events embedded in full.
 */
export function buildEventStream(
  fileName: string,
  lines: string[],
  formatters: EventStreamFormatter[] = [],
  ctx?: FormatterContext,
): EventStream | null {
  const name = parseStreamFileName(fileName);
  if (!name) return null;
  const nonBlank = lines.filter((l) => l.trim());
  const entries = nonBlank.map(decodeEventLine).filter((e): e is FormatterEntry => e != null);
  if (!entries.length) return null;

  const formatter = formatterForStream(formatters, name);
  const rows = formatter ? formatRows(formatter, entries, ctx) : null;
  if (rows) {
    return {
      name,
      total: rows.length,
      truncated: false,
      events: [],
      rows,
    };
  }

  const events: SessionEvent[] = [];
  for (const entry of entries) {
    let d: string;
    try {
      d = JSON.stringify(entry.data) ?? String(entry.data);
    } catch {
      continue;
    }
    if (d.length > MAX_VALUE_CHARS) d = `${d.slice(0, MAX_VALUE_CHARS)}…`;
    events.push({ t: entry.t, d });
  }
  if (!events.length) return null;
  return {
    name,
    total: nonBlank.length,
    truncated: false,
    events,
  };
}
