// Query matching shared by every session accessor.
//
// One rule for authors to remember: a string matches exactly, a RegExp tests, a function decides.
// The exceptions are the free-text fields (`contains`, bodies, device-log lines) where a string is
// a case-insensitive substring, because an exact match of a whole body is never what anyone wants.

import type {
  AnalyticsEvent,
  DeviceLogLine,
  SessionRecord,
  LogRecord,
  NetworkRequest,
  Objective,
  ScreenText,
  StreamEvent,
  ToolCall,
  TraceSpan,
} from "./types.js";

/** Exact string, regular expression, or predicate. */
export type TextMatch = string | RegExp | ((value: string) => boolean);

/** Case-insensitive substring, regular expression, or predicate. */
export type ContainsMatch = string | RegExp | ((value: string) => boolean);

/** Exact value, regular expression over its string form, or predicate. */
export type ValueMatch = string | number | boolean | null | RegExp | ((value: unknown) => boolean);

/**
 * `RegExp.test` on a `/g` or `/y` pattern resumes from `lastIndex`, so the same author-supplied
 * regex would alternate between matching and missing across records. Every test starts at 0.
 */
export function regexTest(m: RegExp, value: string): boolean {
  m.lastIndex = 0;
  return m.test(value);
}

export function matchText(value: string | undefined | null, m: TextMatch | undefined): boolean {
  if (m === undefined) return true;
  if (value === undefined || value === null) return false;
  if (typeof m === "string") return value === m;
  if (m instanceof RegExp) return regexTest(m, value);
  return m(value);
}

export function matchContains(value: string | undefined | null, m: ContainsMatch | undefined): boolean {
  if (m === undefined) return true;
  if (value === undefined || value === null) return false;
  if (typeof m === "string") return value.toLowerCase().includes(m.toLowerCase());
  if (m instanceof RegExp) return regexTest(m, value);
  return m(value);
}

export function matchValue(value: unknown, m: ValueMatch | undefined): boolean {
  if (m === undefined) return true;
  if (m instanceof RegExp) return value !== undefined && value !== null && regexTest(m, String(value));
  if (typeof m === "function") return m(value);
  return value === m;
}

/** Every listed key must match the object's value under the same key. */
export function matchFields(
  obj: Record<string, unknown> | undefined,
  fields: Record<string, ValueMatch> | undefined,
): boolean {
  if (!fields) return true;
  if (!obj) return false;
  return Object.entries(fields).every(([k, m]) => matchValue(obj[k], m));
}

/** Any of a list, or the single value. */
type OneOrMany<T> = T | ReadonlyArray<T>;

function anyOf<T>(m: OneOrMany<T> | undefined, test: (one: T) => boolean): boolean {
  if (m === undefined) return true;
  if (Array.isArray(m)) return (m as ReadonlyArray<T>).some(test);
  return test(m as T);
}

export interface NetworkQuery {
  /** HTTP method, exact and case-insensitive. */
  method?: OneOrMany<string>;
  url?: TextMatch;
  host?: TextMatch;
  /** Path component only, e.g. `/v2/orders`. */
  path?: TextMatch;
  /**
   * The query string, without its leading `?`. Separate from `path`, which never carries one, so
   * a survey can select on a parameter without also having to match the whole URL.
   */
  query?: TextMatch;
  /** A status code, `"ok"` (2xx/3xx), `"error"` (4xx/5xx), `"failed"` (no response), or a predicate. */
  status?: number | "ok" | "error" | "failed" | ((status: number | undefined) => boolean);
  /** Substring or pattern over the request body. Only matches when the capture kept bodies. */
  requestBody?: ContainsMatch;
  /** Substring or pattern over the response body. */
  responseBody?: ContainsMatch;
  /** Request header name → value match. Header names compare case-insensitively. */
  requestHeaders?: Record<string, TextMatch>;
  responseHeaders?: Record<string, TextMatch>;
  where?: (request: NetworkRequest) => boolean;
}

function matchStatus(r: NetworkRequest, m: NetworkQuery["status"]): boolean {
  if (m === undefined) return true;
  if (typeof m === "number") return r.status === m;
  if (typeof m === "function") return m(r.status);
  if (m === "ok") return r.ok;
  if (m === "error") return r.status !== undefined && r.status >= 400;
  return r.failed;
}

function matchHeaders(headers: Record<string, string> | undefined, m: Record<string, TextMatch> | undefined): boolean {
  if (!m) return true;
  if (!headers) return false;
  const lower = new Map(Object.entries(headers).map(([k, v]) => [k.toLowerCase(), v]));
  return Object.entries(m).every(([k, tm]) => matchText(lower.get(k.toLowerCase()), tm));
}

export function matchNetwork(r: NetworkRequest, q: NetworkQuery): boolean {
  return (
    anyOf(q.method, (method) => r.method.toUpperCase() === method.toUpperCase()) &&
    matchText(r.url, q.url) &&
    matchText(r.host, q.host) &&
    matchText(r.path, q.path) &&
    matchText(r.query, q.query) &&
    matchStatus(r, q.status) &&
    // Guarded rather than left to `matchContains`, which would answer the same but only after
    // reading the body. A request that has no body to read can say so by throwing — one read back
    // from a snapshot does — and a query that never asked about a body should not be
    // the thing that trips it.
    (q.requestBody === undefined || matchContains(r.requestBody, q.requestBody)) &&
    (q.responseBody === undefined || matchContains(r.responseBody, q.responseBody)) &&
    matchHeaders(r.requestHeaders, q.requestHeaders) &&
    matchHeaders(r.responseHeaders, q.responseHeaders) &&
    (q.where === undefined || q.where(r))
  );
}

export interface AnalyticsQuery {
  /** Event name. */
  name?: TextMatch;
  /** Emitting subsystem, when the stream records one. */
  source?: TextMatch;
  /** Stream the event came from. */
  stream?: TextMatch;
  /** Property name → value match. */
  properties?: Record<string, ValueMatch>;
  /** Substring or pattern over the whole row serialized as JSON. */
  contains?: ContainsMatch;
  where?: (event: AnalyticsEvent) => boolean;
}

export function matchAnalytics(e: AnalyticsEvent, q: AnalyticsQuery): boolean {
  return (
    matchText(e.name, q.name) &&
    matchText(e.source, q.source) &&
    matchText(e.stream, q.stream) &&
    matchFields(e.properties, q.properties) &&
    (q.contains === undefined || matchContains(JSON.stringify(e.raw), q.contains)) &&
    (q.where === undefined || q.where(e))
  );
}

export interface EventQuery {
  stream?: TextMatch;
  /** Substring or pattern over the row serialized as JSON. */
  contains?: ContainsMatch;
  where?: (event: StreamEvent) => boolean;
}

export function matchEvent(e: StreamEvent, q: EventQuery): boolean {
  return (
    matchText(e.stream, q.stream) &&
    (q.contains === undefined || matchContains(JSON.stringify(e.data), q.contains)) &&
    (q.where === undefined || q.where(e))
  );
}

export interface ToolQuery {
  name?: TextMatch;
  successful?: boolean;
  /** Argument name → value match. */
  args?: Record<string, ValueMatch>;
  deviceName?: TextMatch;
  where?: (call: ToolCall) => boolean;
}

export function matchTool(t: ToolCall, q: ToolQuery): boolean {
  return (
    matchText(t.name, q.name) &&
    (q.successful === undefined || t.successful === q.successful) &&
    matchFields(t.args, q.args) &&
    matchText(t.deviceName, q.deviceName) &&
    (q.where === undefined || q.where(t))
  );
}

export interface ObjectiveQuery {
  /** Substring or pattern over the prompt text. */
  prompt?: ContainsMatch;
  status?: Objective["status"];
  /** A tool the step's recording replayed. */
  recordedTool?: TextMatch;
  where?: (objective: Objective) => boolean;
}

export function matchObjective(o: Objective, q: ObjectiveQuery): boolean {
  return (
    matchContains(o.prompt, q.prompt) &&
    (q.status === undefined || o.status === q.status) &&
    (q.recordedTool === undefined || o.recordedTools.some((t) => matchText(t, q.recordedTool))) &&
    (q.where === undefined || q.where(o))
  );
}

export interface ScreenTextQuery {
  /** Substring or pattern over the visible text. */
  text?: ContainsMatch;
  source?: TextMatch;
  where?: (s: ScreenText) => boolean;
}

export function matchScreenText(s: ScreenText, q: ScreenTextQuery): boolean {
  return matchContains(s.text, q.text) && matchText(s.source, q.source) && (q.where === undefined || q.where(s));
}

export interface DeviceLogQuery {
  /** Substring or pattern over the line. */
  text?: ContainsMatch;
  where?: (line: DeviceLogLine) => boolean;
}

export function matchDeviceLog(l: DeviceLogLine, q: DeviceLogQuery): boolean {
  return matchContains(l.text, q.text) && (q.where === undefined || q.where(l));
}

export interface TraceQuery {
  name?: TextMatch;
  category?: TextMatch;
  args?: Record<string, ValueMatch>;
  where?: (span: TraceSpan) => boolean;
}

export function matchTrace(s: TraceSpan, q: TraceQuery): boolean {
  return (
    matchText(s.name, q.name) &&
    matchText(s.category, q.category) &&
    matchFields(s.args, q.args) &&
    (q.where === undefined || q.where(s))
  );
}

/** The wire name of the driver log kept its old class name; accept both spellings. */
const LOG_TYPE_ALIASES: Record<string, string> = {
  AgentDriverLog: "MaestroDriverLog",
};

/**
 * The one name a type answers to, for anything that STORES it rather than matching through
 * `matchLog`. A stored string cannot accept two spellings the way a query can: a session that
 * happened to write `AgentDriverLog` answers nothing to `WHERE type = 'MaestroDriverLog'`, which
 * reads as a run that never touched the device.
 */
export function canonicalLogType(type: string): string {
  return LOG_TYPE_ALIASES[type] ?? type;
}

export interface LogQuery {
  /** Short type name, e.g. `TrailblazeToolLog`. The driver log answers to both of its names. */
  type?: string | ReadonlyArray<string>;
  /** Substring or pattern over the record serialized as JSON. Slow on big sessions; prefer a typed accessor. */
  contains?: string | RegExp;
  where?: (record: LogRecord) => boolean;
}

/**
 * Reads every field a snapshot left out of `log`, so the first one throws. A snapshot marks each as
 * a non-enumerable getter that throws when read, and `JSON.stringify` skips non-enumerable fields —
 * so without this a search over a record whose view tree was dropped answers "not there" instead of
 * "cannot tell".
 */
function readOmittedFields(log: unknown): void {
  if (log === null || typeof log !== "object") return;
  for (const name of Object.getOwnPropertyNames(log)) {
    const field = Object.getOwnPropertyDescriptor(log, name);
    if (field && !field.enumerable && field.get) field.get.call(log);
  }
}

export function matchLog(rec: LogRecord, q: LogQuery): boolean {
  if (q.type !== undefined) {
    const wanted = (Array.isArray(q.type) ? q.type : [q.type]) as ReadonlyArray<string>;
    const names = new Set<string>();
    for (const t of wanted) {
      names.add(t);
      const alias = LOG_TYPE_ALIASES[t];
      if (alias) names.add(alias);
      for (const [k, v] of Object.entries(LOG_TYPE_ALIASES)) if (v === t) names.add(k);
    }
    if (!names.has(rec.type)) return false;
  }
  if (q.contains !== undefined) {
    readOmittedFields(rec.log);
    const text = JSON.stringify(rec.log);
    if (typeof q.contains === "string" ? !text.toLowerCase().includes(q.contains.toLowerCase()) : !regexTest(q.contains, text)) return false;
  }
  return q.where === undefined || q.where(rec);
}

/**
 * A queryable, ordered list of one record kind. Every accessor on a session returns one of these,
 * so the same five verbs work on requests, analytics events, tool calls, and the rest.
 */
export class Records<T extends SessionRecord, Q> implements Iterable<T> {
  constructor(
    private readonly items: ReadonlyArray<T>,
    private readonly matcher: (item: T, query: Q) => boolean,
  ) {}

  /** Every record, in session order. */
  all(): T[] {
    return [...this.items];
  }

  /** First record matching the query, or undefined. */
  find(query: Q = {} as Q): T | undefined {
    return this.items.find((i) => this.matcher(i, query));
  }

  /** Last record matching the query, or undefined. */
  findLast(query: Q = {} as Q): T | undefined {
    for (let i = this.items.length - 1; i >= 0; i--) {
      const item = this.items[i]!;
      if (this.matcher(item, query)) return item;
    }
    return undefined;
  }

  /** Every record matching the query, in session order. */
  filter(query: Q = {} as Q): T[] {
    return this.items.filter((i) => this.matcher(i, query));
  }

  /** Whether at least one record matches. */
  has(query: Q = {} as Q): boolean {
    return this.find(query) !== undefined;
  }

  /** How many records match. */
  count(query: Q = {} as Q): number {
    return this.filter(query).length;
  }

  get length(): number {
    return this.items.length;
  }

  [Symbol.iterator](): Iterator<T> {
    return this.items[Symbol.iterator]();
  }
}
