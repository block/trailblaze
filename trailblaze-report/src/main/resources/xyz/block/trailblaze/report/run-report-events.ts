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
