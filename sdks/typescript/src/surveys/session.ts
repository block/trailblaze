// Loads one session directory into the queryable view surveys run against.
//
// A session directory is what the runner writes and what a CI artifact zip unpacks to: numbered or
// hash-prefixed `<n>_<LogType>.json` records, screenshots, and optional sidecars — `network.ndjson`
// (one request-start and one response-end row per exchange, joined here by id), `events/*.ndjson`
// (any producer, `{timeMs, data}` envelope), `visible-strings.ndjson`, `device.log`, `trace.json`,
// and on CI a `session_result.json` with the verdict. Everything is read eagerly so the survey
// API stays synchronous; the runner keeps one session in memory at a time.

import { existsSync, readdirSync, readFileSync, realpathSync, statSync } from "node:fs";
import { basename, join, relative, resolve, sep } from "node:path";

import type {
  ObjectiveCompleteLog,
  ObjectiveStartLog,
  SessionStatus,
  Started,
  TrailblazeLog,
  TrailblazeSessionStatusChangeLog,
  TrailblazeToolLog,
} from "../generated/trailrunner-dtos.js";
import {
  type AnalyticsQuery,
  type DeviceLogQuery,
  type EventQuery,
  matchAnalytics,
  matchDeviceLog,
  matchEvent,
  matchLog,
  matchNetwork,
  matchObjective,
  matchScreenText,
  matchTool,
  matchTrace,
  type LogQuery,
  type NetworkQuery,
  type ObjectiveQuery,
  Records,
  type ScreenTextQuery,
  type ToolQuery,
  type TraceQuery,
} from "./match.js";
import type {
  AnalyticsEvent,
  DeviceLogLine,
  SessionRecord,
  LogRecord,
  NetworkRequest,
  Objective,
  ScreenText,
  SessionSummary,
  StreamEvent,
  ToolCall,
  TraceSpan,
} from "./types.js";

const NETWORK_FILE = "network.ndjson";
const EVENTS_DIR = "events";
const VISIBLE_STRINGS_FILE = "visible-strings.ndjson";
const DEVICE_LOG_FILES = ["device.log", "logcat.txt", "system_log.txt"];
const TRACE_FILE = "trace.json";
const SESSION_RESULT_FILE = "session_result.json";
const CI_CONTEXT_FILE = "host_ci_context.json";

/**
 * Runner-written `0001_Name.json`, CI-reassembled `<hash>_Name.json`, and the resumed-session
 * form `0001_<timestampMs>_Name.json` the runtime writes. Same rule as the runtime's log repository
 * (hex prefix, `.json`, not the capture metadata) minus its permissiveness about the rest.
 */
const LOG_FILE = /^[0-9a-f]+_[^/]*\.json$/i;
const SEQUENTIAL_LOG_FILE = /^(\d+)_/;
const BODY_READ_LIMIT_BYTES = 1_000_000;

/** True when `dir` holds Trailblaze log records, i.e. is a session directory. */
export function isSessionDirectory(dir: string): boolean {
  try {
    if (!statSync(dir).isDirectory()) return false;
    return readdirSync(dir).some((name) => LOG_FILE.test(name) && name !== "capture_metadata.json");
  } catch {
    return false;
  }
}

/** Parses an ISO timestamp, tolerating the six-digit fractions the runtime writes. */
export function parseIsoMs(iso: string | undefined | null): number | undefined {
  if (!iso) return undefined;
  const trimmed = iso.replace(/(\.\d{3})\d+/, "$1");
  const ms = Date.parse(trimmed);
  return Number.isNaN(ms) ? undefined : ms;
}

function shortClass(cls: string | undefined): string {
  if (!cls) return "Unknown";
  const i = cls.lastIndexOf(".");
  return i >= 0 ? cls.slice(i + 1) : cls;
}

function readJsonIfPresent(path: string): unknown {
  if (!existsSync(path)) return undefined;
  try {
    return JSON.parse(readFileSync(path, "utf8"));
  } catch {
    return undefined;
  }
}

function readLines(path: string): string[] {
  const text = readFileSync(path, "utf8");
  const lines = text.split("\n");
  if (lines.length > 0 && lines[lines.length - 1] === "") lines.pop();
  return lines;
}

function readNdjson(path: string): Array<{ line: number; row: unknown }> {
  const out: Array<{ line: number; row: unknown }> = [];
  readLines(path).forEach((text, i) => {
    if (!text.trim()) return;
    try {
      out.push({ line: i + 1, row: JSON.parse(text) });
    } catch {
      // A torn final line from a capture cut off mid-write is normal; skip it.
    }
  });
  return out;
}

function asRecord(v: unknown): Record<string, unknown> | undefined {
  return v !== null && typeof v === "object" && !Array.isArray(v) ? (v as Record<string, unknown>) : undefined;
}

function asString(v: unknown): string | undefined {
  return typeof v === "string" ? v : undefined;
}

function asNumber(v: unknown): number | undefined {
  return typeof v === "number" && Number.isFinite(v) ? v : undefined;
}

function hostOf(url: string): string {
  try {
    return new URL(url).host;
  } catch {
    return "";
  }
}

/**
 * Splits what a capture recorded into the bare path and the query. The Android capture keeps the
 * query on `urlPath` so consumers can group by route and query key; the iOS and web captures
 * strip it. `path: "/v2/orders"` has to match all three, so `path` never carries a `?`.
 */
function pathAndQuery(url: string, urlPath: string | undefined): { path: string; query?: string } {
  let path = urlPath ?? "";
  let query: string | undefined;
  const q = path.indexOf("?");
  if (q >= 0) {
    query = path.slice(q + 1);
    path = path.slice(0, q);
  }
  try {
    const u = new URL(url);
    if (!path) path = u.pathname;
    if (query === undefined && u.search) query = u.search.slice(1);
  } catch {
    // Not an absolute URL; whatever the capture wrote stands.
  }
  return query ? { path, query } : { path };
}

// ---------------------------------------------------------------------------------------------
// Log records
// ---------------------------------------------------------------------------------------------

function loadLogs(dir: string): LogRecord[] {
  const names = readdirSync(dir).filter((n) => LOG_FILE.test(n) && n !== "capture_metadata.json");
  const parsed: Array<{ name: string; seq?: number; log: TrailblazeLog }> = [];
  for (const name of names) {
    const json = readJsonIfPresent(join(dir, name));
    const rec = asRecord(json);
    if (!rec || typeof rec.class !== "string") continue;
    const seqMatch = SEQUENTIAL_LOG_FILE.exec(name);
    parsed.push({ name, seq: seqMatch ? Number(seqMatch[1]) : undefined, log: json as TrailblazeLog });
  }
  // Runner-written sessions number their files in write order; CI-reassembled ones use a hash
  // prefix and only the record timestamps order them.
  //
  // The ordinals have to be DISTINCT before they are an order. A session the runtime resumed after
  // a restart has two records numbered `001`: the write counter lives in memory, so it starts over
  // and the later batch reuses the numbers the earlier one already took. Ordering by ordinal then
  // interleaves the two batches, and the session reads as going backwards in time — which breaks
  // the last-matching-record lookups, the start/complete pairing below, and anything asking what
  // happened after what. Repeated ordinals mean they describe two runs rather than one sequence,
  // so the timestamps are used instead, being the only thing that still describes one.
  const seqs = parsed.map((p) => p.seq);
  const ordinalsAreAnOrder = parsed.length > 0 && seqs.every((s) => s !== undefined) && new Set(seqs).size === seqs.length;
  parsed.sort((a, b) => {
    if (ordinalsAreAnOrder) return a.seq! - b.seq!;
    // `timestamp` is required on every log type the runtime writes, so a record missing one is
    // corrupted, not a real shape. It still has to sort somewhere: a resumed session now reaches
    // this branch on colliding ordinals, where it used to be trusted outright, so this path is no
    // longer rare enough to ignore. `?? 0` would hoist it to the very front — the epoch predates
    // every real timestamp — recreating the backwards-in-time read this sort exists to close, just
    // by a different record. Its own ordinal is not a substitute time value (write-order numbers
    // are tiny next to an epoch-ms timestamp, so it would land at the front just the same); sorting
    // it last is the answer that doesn't fabricate a time nothing recorded.
    const ta = parseIsoMs((a.log as { timestamp?: string }).timestamp) ?? Number.POSITIVE_INFINITY;
    const tb = parseIsoMs((b.log as { timestamp?: string }).timestamp) ?? Number.POSITIVE_INFINITY;
    return ta - tb || a.name.localeCompare(b.name);
  });
  return parsed.map((p, index) => {
    const type = shortClass(p.log.class);
    return {
      kind: "log",
      type,
      index,
      log: p.log,
      file: p.name,
      timeMs: parseIsoMs((p.log as { timestamp?: string }).timestamp),
      summary: describeLog(type, p.log),
    };
  });
}

function describeLog(type: string, log: TrailblazeLog): string {
  switch (type) {
    case "TrailblazeToolLog": {
      const l = log as TrailblazeToolLog;
      return `${l.toolName} ${l.successful ? "succeeded" : "failed"}`;
    }
    case "ObjectiveStartLog":
      return `step started: ${promptOf(log as ObjectiveStartLog)}`;
    case "ObjectiveCompleteLog":
      return `step ended: ${promptOf(log as ObjectiveCompleteLog)}`;
    case "TrailblazeSessionStatusChangeLog":
      return `session ${shortClass((log as TrailblazeSessionStatusChangeLog).sessionStatus.class)}`;
    default:
      return type;
  }
}

/** A direction step carries its prompt in `step`; a verification step carries it in `verify`. */
function promptOf(log: ObjectiveStartLog | ObjectiveCompleteLog): string {
  const step = log.promptStep as { step?: string; verify?: string };
  return step.step ?? step.verify ?? "";
}

// `canonicalLogType`, `LogQuery` and `matchLog` live in `match.js` now, beside every other
// matcher. A session read back from a snapshot matches logs the same way, and that
// reader cannot import this file: it runs in a browser and this one reads the filesystem.
export { canonicalLogType } from "./match.js";
export type { LogQuery } from "./match.js";

function toolCallsFrom(logs: LogRecord[]): ToolCall[] {
  const calls: ToolCall[] = [];
  for (const rec of logs) {
    if (rec.type !== "TrailblazeToolLog") continue;
    const log = rec.log as TrailblazeToolLog;
    const tool = asRecord(log.trailblazeTool);
    const args = asRecord(tool?.raw) ?? {};
    // Surveys match on the real arguments; the summary and detail travel into reports and sites.
    const shown = redactSecrets(args);
    calls.push({
      kind: "tool",
      name: log.toolName,
      args,
      successful: log.successful,
      durationMs: log.durationMs,
      deviceName: log.deviceName ?? undefined,
      log,
      file: rec.file,
      timeMs: rec.timeMs,
      summary: `${log.toolName}(${summarizeArgs(shown)}) ${log.successful ? "succeeded" : "failed"}`,
      detail: { name: log.toolName, args: shown, successful: log.successful, durationMs: log.durationMs },
    });
  }
  return calls;
}

/**
 * Argument names whose values are credentials wherever they appear. Matched on the key, not the value.
 *
 * `jwt` is here because signed URLs commonly carry a bearer under that name, and no spelling of
 * `token` covers it.
 */
const SECRET_KEY = /pass(word|code|phrase)?|pin\b|secret|token|jwt|otp|cvv|credential|ssn|api[_-]?key/i;
const REDACTED = "[redacted]";

/** Replaces the value under every credential-named key, at any depth, and leaves the rest alone. */
export function redactSecrets(args: Record<string, unknown>): Record<string, unknown> {
  const out: Record<string, unknown> = {};
  for (const [k, v] of Object.entries(args)) {
    if (SECRET_KEY.test(k)) out[k] = REDACTED;
    else if (Array.isArray(v)) out[k] = v.map((x) => (asRecord(x) ? redactSecrets(x as Record<string, unknown>) : x));
    else out[k] = asRecord(v) ? redactSecrets(v as Record<string, unknown>) : v;
  }
  return out;
}

function summarizeArgs(args: Record<string, unknown>): string {
  const parts = Object.entries(args)
    .slice(0, 4)
    .map(([k, v]) => `${k}=${typeof v === "string" ? JSON.stringify(v.length > 40 ? v.slice(0, 40) + "…" : v) : JSON.stringify(v)}`);
  return parts.join(", ") + (Object.keys(args).length > 4 ? ", …" : "");
}

function objectivesFrom(logs: LogRecord[]): Objective[] {
  const objectives: Objective[] = [];
  let step = 0;
  for (const rec of logs) {
    if (rec.type === "ObjectiveStartLog") {
      const log = rec.log as ObjectiveStartLog;
      step += 1;
      const recording = asRecord((log.promptStep as { recording?: unknown }).recording);
      const tools = Array.isArray(recording?.tools) ? (recording!.tools as Array<{ name?: string }>) : [];
      const prompt = promptOf(log);
      objectives.push({
        kind: "objective",
        step,
        prompt,
        status: "unknown",
        recordedTools: tools.map((t) => t.name ?? "").filter(Boolean),
        startMs: rec.timeMs,
        file: rec.file,
        timeMs: rec.timeMs,
        summary: `step ${step}: ${prompt}`,
        detail: { step, prompt, status: "unknown" },
      });
    } else if (rec.type === "ObjectiveCompleteLog") {
      const log = rec.log as ObjectiveCompleteLog;
      const prompt = promptOf(log);
      const open = [...objectives].reverse().find((o) => o.status === "unknown" && o.prompt === prompt) ??
        [...objectives].reverse().find((o) => o.status === "unknown");
      if (!open) continue;
      const resultClass = log.objectiveResult.class;
      const startLog = open.file;
      open.status = resultClass.includes(".Success.") ? "complete" : resultClass.includes(".Failure.") ? "failed" : "unknown";
      open.endMs = rec.timeMs;
      // The record that proves the outcome is the completion log, so that is the one the evidence
      // cites; the start stays reachable through `startMs` and the detail below.
      open.file = rec.file;
      open.timeMs = rec.timeMs;
      open.explanation = asString((log.objectiveResult as { llmExplanation?: unknown }).llmExplanation);
      open.summary = `step ${open.step} ${open.status}: ${prompt}`;
      open.detail = { step: open.step, prompt, status: open.status, explanation: open.explanation, startLog };
    }
  }
  return objectives;
}

// ---------------------------------------------------------------------------------------------
// Network
// ---------------------------------------------------------------------------------------------

interface BodyRef {
  inlineText?: string;
  blobPath?: string;
}

/** Records the sidecar a body came out of, so a finding that rests on it can cite the file. */
function noteBodyFile(r: NetworkRequest, path: string | undefined): void {
  if (path === undefined) return;
  r.bodyFiles = [...new Set([...(r.bodyFiles ?? []), path])];
}

/**
 * Where a referenced blob really is, or undefined when it is absent or outside the session.
 *
 * The capture wrote the blob beside its index; a zip from elsewhere must not read outside the
 * session. Both sides are resolved THROUGH their symlinks before the prefix comparison, because
 * `resolve` is lexical and `readFileSync` is not: a blob that is a symlink to a host path satisfies
 * a lexical prefix check and is then followed anyway, so unpacking someone else's session zip could
 * put any file the reader can open into a survey's evidence. The session directory is resolved too
 * — a session under a symlinked parent, which `/tmp` is on macOS, would otherwise fail the check
 * with nothing wrong with it.
 */
function blobInsideSession(dir: string, blobPath: string): string | undefined {
  try {
    const real = realpathSync(resolve(dir, blobPath));
    return real.startsWith(realpathSync(dir) + sep) ? real : undefined;
  } catch {
    // No such file, or a symlink with no target. `realpathSync` throws where `existsSync` was false.
    return undefined;
  }
}

/**
 * The body text, and the blob path to cite as evidence — only the path the blob actually
 * validated at, never the raw reference. A blob outside the session is refused as a body AND as a
 * citation: citing a path this loader would not read is its own leak, naming a host location in
 * report text that a reader might otherwise trust and act on.
 */
function readBody(dir: string, ref: unknown): { text?: string; blobFile?: string } {
  const r = asRecord(ref) as BodyRef | undefined;
  if (!r) return {};
  if (typeof r.inlineText === "string") return { text: r.inlineText };
  if (typeof r.blobPath === "string") {
    const path = blobInsideSession(dir, r.blobPath);
    if (path === undefined) return {};
    try {
      // Too big to inline is still a legitimate, in-session blob; cite it even without a body.
      if (statSync(path).size > BODY_READ_LIMIT_BYTES) return { blobFile: r.blobPath };
      return { text: readFileSync(path, "utf8"), blobFile: r.blobPath };
    } catch {
      return {};
    }
  }
  return {};
}

function describeRequest(r: NetworkRequest): string {
  const status = r.failed ? "failed" : r.status !== undefined ? String(r.status) : "no response";
  return `${r.method} ${r.path || r.url} → ${status}`;
}

function finishRequest(r: NetworkRequest): NetworkRequest {
  r.ok = r.status !== undefined && r.status >= 200 && r.status < 400;
  r.failed = r.failed || r.status === undefined;
  r.summary = describeRequest(r);
  // The detail travels into the report; the record's own `url` and `query` stay raw for matching.
  // `at` names every location that backs this exchange. A survey matching on `status` or a
  // response body is proven by the response row, which `line` does not point at.
  const at = [`${r.file}:${r.line}`];
  const responseAt = r.responseLine === undefined ? undefined : `${r.responseFile ?? r.file}:${r.responseLine}`;
  if (responseAt !== undefined && responseAt !== at[0]) at.push(responseAt);
  at.push(...(r.bodyFiles ?? []));
  r.detail = { method: r.method, url: redactUrl(r.url), status: r.status, durationMs: r.durationMs, at };
  return r;
}

/**
 * The URL with credentials taken out: the value of every credential-named query parameter, and the
 * userinfo the authority may carry. The path is untouched.
 *
 * Userinfo is redacted whatever it is named, unlike a query parameter — `https://user:secret@host/x`
 * puts the secret in a position, not under a name, so there is nothing to test it against and no
 * legitimate reason for it to reach a shareable artifact.
 *
 * Both fields go, including the one that looks like a username. `https://TOKEN:@host/x` is a common
 * way to pass a bearer token, and nothing in the URL distinguishes it from a person's login name, so
 * keeping the first field means publishing a credential whenever a caller uses that form.
 */
export function redactUrl(url: string): string {
  url = url.replace(/^([a-z][a-z0-9+.-]*:\/\/)[^/?#@]*@/i, `$1${REDACTED}@`);
  const q = url.indexOf("?");
  if (q < 0) return url;
  const query = url.slice(q + 1).replace(/([^&=]+)(=[^&]*)?/g, (whole, key: string) => {
    let name = key;
    try {
      name = decodeURIComponent(key);
    } catch {
      // Not percent-encoded; judge the raw key.
    }
    return SECRET_KEY.test(name) ? `${key}=${REDACTED}` : whole;
  });
  return `${url.slice(0, q + 1)}${query}`;
}

function loadNetwork(dir: string, streams: StreamEvent[]): NetworkRequest[] {
  const byId = new Map<string, NetworkRequest>();
  const order: string[] = [];
  const path = join(dir, NETWORK_FILE);
  if (existsSync(path)) {
    for (const { line, row } of readNdjson(path)) {
      const rec = asRecord(row);
      if (!rec) continue;
      const id = asString(rec.id) ?? `line-${line}`;
      const phase = asString(rec.phase) ?? "";
      let r = byId.get(id);
      if (!r) {
        const url = asString(rec.url) ?? "";
        r = {
          kind: "network",
          id,
          method: (asString(rec.method) ?? "").toUpperCase(),
          url,
          host: hostOf(url),
          ...pathAndQuery(url, asString(rec.urlPath)),
          ok: false,
          failed: false,
          startMs: asNumber(rec.timestampMs) ?? 0,
          source: asString(rec.source) ?? "",
          file: NETWORK_FILE,
          line,
          timeMs: asNumber(rec.timestampMs),
          summary: "",
        };
        byId.set(id, r);
        order.push(id);
      }
      if (phase === "REQUEST_START") {
        if (rec.url) {
          r.url = asString(rec.url) ?? r.url;
          r.host = hostOf(r.url);
          const pq = pathAndQuery(r.url, asString(rec.urlPath));
          r.path = pq.path;
          if (pq.query) r.query = pq.query;
          else delete r.query;
        }
        if (rec.method) r.method = (asString(rec.method) ?? r.method).toUpperCase();
        r.startMs = asNumber(rec.timestampMs) ?? r.startMs;
        r.timeMs = r.startMs;
        r.requestHeaders = asRecord(rec.requestHeaders) as Record<string, string> | undefined;
        const reqBody = readBody(dir, rec.requestBodyRef);
        r.requestBody = reqBody.text ?? r.requestBody;
        noteBodyFile(r, reqBody.blobFile);
      } else if (phase === "RESPONSE_END") {
        r.status = asNumber(rec.statusCode);
        r.durationMs = asNumber(rec.durationMs);
        r.endMs = asNumber(rec.timestampMs);
        r.responseHeaders = asRecord(rec.responseHeaders) as Record<string, string> | undefined;
        const resBody = readBody(dir, rec.responseBodyRef);
        r.responseBody = resBody.text ?? r.responseBody;
        noteBodyFile(r, resBody.blobFile);
        r.responseLine = line;
      } else if (phase === "FAILED") {
        r.failed = true;
        r.endMs = asNumber(rec.timestampMs);
        r.responseLine = line;
      }
    }
  }
  enrichFromInAppNetworkStreams(byId, order, streams);
  return order.map((id) => finishRequest(byId.get(id)!));
}

/**
 * Some in-app captures also publish an `events/*network*.ndjson` stream whose rows carry the
 * request and response bodies the canonical file leaves out. Rows come as `{request: {...}}` and
 * `{finalizedResponse: {...}}` (sometimes wrapped one level in `_0`), paired by request id. When the
 * canonical file is missing entirely the stream alone is enough to build the request list.
 */
function enrichFromInAppNetworkStreams(byId: Map<string, NetworkRequest>, order: string[], streams: StreamEvent[]): void {
  for (const ev of streams) {
    if (!/network/i.test(ev.stream)) continue;
    const data = asRecord(ev.data);
    if (!data) continue;
    const request = unwrap(data.request);
    const response = unwrap(data.finalizedResponse ?? data.response);
    if (request) {
      const id = asString(request.id);
      if (!id) continue;
      let r = byId.get(id);
      const url = asString(request.url) ?? "";
      if (!r) {
        r = {
          kind: "network",
          id,
          method: (asString(request.httpMethod) ?? asString(request.method) ?? "").toUpperCase(),
          url,
          host: hostOf(url),
          ...pathAndQuery(url, undefined),
          ok: false,
          failed: false,
          startMs: ev.timeMs ?? 0,
          source: "IN_APP",
          file: ev.file,
          line: ev.line,
          timeMs: ev.timeMs,
          summary: "",
        };
        byId.set(id, r);
        order.push(id);
      }
      r.requestHeaders ??= asRecord(request.headers) as Record<string, string> | undefined;
      r.requestBody ??= asString(request.humanReadableBody) ?? asString(request.rawBody) ?? asString(request.body);
    } else if (response) {
      const id = asString(response.requestID) ?? asString(response.requestId) ?? asString(response.id);
      if (!id) continue;
      const r = byId.get(id);
      if (!r) continue;
      r.status ??= asNumber(response.statusCode);
      r.endMs ??= ev.timeMs;
      r.responseLine ??= ev.line;
      r.responseFile ??= ev.file;
      r.responseHeaders ??= asRecord(response.headers) as Record<string, string> | undefined;
      r.responseBody ??= asString(response.humanReadableBody) ?? asString(response.body);
    }
  }
}

function unwrap(v: unknown): Record<string, unknown> | undefined {
  const rec = asRecord(v);
  if (!rec) return undefined;
  const inner = asRecord(rec._0);
  return inner ?? rec;
}

// ---------------------------------------------------------------------------------------------
// Event streams, analytics
// ---------------------------------------------------------------------------------------------

function loadStreams(dir: string): StreamEvent[] {
  const eventsDir = join(dir, EVENTS_DIR);
  if (!existsSync(eventsDir)) return [];
  const out: StreamEvent[] = [];
  for (const name of readdirSync(eventsDir).sort()) {
    if (!name.endsWith(".ndjson")) continue;
    const stream = name.slice(0, -".ndjson".length);
    const file = `${EVENTS_DIR}/${name}`;
    for (const { line, row } of readNdjson(join(eventsDir, name))) {
      const rec = asRecord(row);
      const timeMs = asNumber(rec?.timeMs) ?? asNumber(rec?.timestampMs);
      const data = rec && "data" in rec ? rec.data : row;
      out.push({ kind: "event", stream, data, file, line, timeMs, summary: `${stream} event` });
    }
  }
  out.sort((a, b) => (a.timeMs ?? 0) - (b.timeMs ?? 0));
  return out;
}

/**
 * Turns rows of any stream whose name mentions analytics into named events. Two row shapes are
 * understood: a flat `{name|event, source?, properties?}` object, and a table-row shape
 * `{columnItems: {Event, Source?, Properties?}}` whose properties are a JSON string.
 */
function analyticsFrom(streams: StreamEvent[]): AnalyticsEvent[] {
  const out: AnalyticsEvent[] = [];
  for (const ev of streams) {
    if (!/analytic/i.test(ev.stream)) continue;
    const data = asRecord(ev.data);
    if (!data) continue;
    const columns = asRecord(data.columnItems);
    let name: string | undefined;
    let source: string | undefined;
    let props: unknown;
    if (columns) {
      name = asString(columns.Event) ?? asString(columns.event) ?? asString(columns.Name);
      source = asString(columns.Source) ?? asString(columns.source);
      props = columns.Properties ?? columns.properties;
    } else {
      name = asString(data.name) ?? asString(data.event) ?? asString(data.eventName) ?? asString(data.event_name);
      source = asString(data.source);
      props = data.properties ?? data.props ?? data.params;
    }
    if (!name) continue;
    out.push({
      kind: "analytics",
      stream: ev.stream,
      name,
      source,
      properties: parseProperties(props),
      raw: ev.data,
      file: ev.file,
      line: ev.line,
      timeMs: ev.timeMs,
      summary: `${source ? source + ": " : ""}${name}`,
      detail: { name, source },
    });
  }
  return out;
}

function parseProperties(v: unknown): Record<string, unknown> {
  if (typeof v === "string") {
    try {
      return asRecord(JSON.parse(v)) ?? {};
    } catch {
      return {};
    }
  }
  return asRecord(v) ?? {};
}

// ---------------------------------------------------------------------------------------------
// Screen text, device log, trace
// ---------------------------------------------------------------------------------------------

function loadScreenText(dir: string): ScreenText[] {
  const path = join(dir, VISIBLE_STRINGS_FILE);
  if (!existsSync(path)) return [];
  const out: ScreenText[] = [];
  // A capture that showed the same screen as an earlier step is written with no strings and a
  // `repeatOfStepIndex` naming that step. It is still a capture of those strings at its own time,
  // so it gets them back: without them, a screen the run returned to reads as never shown again.
  const stringsByStep = new Map<number, unknown[]>();
  for (const { line, row } of readNdjson(path)) {
    const rec = asRecord(row);
    if (!rec || rec.kind !== "screen" || !Array.isArray(rec.strings)) continue;
    const captureId = asString(rec.captureId) ?? "";
    const stepIndex = asNumber(rec.stepIndex) ?? -1;
    const timeMs = parseIsoMs(asString(rec.timestamp));
    const repeatOf = asNumber(rec.repeatOfStepIndex);
    const own = rec.strings as unknown[];
    if (own.length > 0) stringsByStep.set(stepIndex, own);
    const strings = own.length === 0 && repeatOf !== undefined ? (stringsByStep.get(repeatOf) ?? []) : own;
    for (const s of strings) {
      const str = asRecord(s);
      const text = asString(str?.text);
      if (!text) continue;
      out.push({
        kind: "screen-text",
        text,
        source: asString(str?.source) ?? "text",
        captureId,
        stepIndex,
        file: VISIBLE_STRINGS_FILE,
        line,
        timeMs,
        summary: `saw "${text.length > 80 ? text.slice(0, 80) + "…" : text}"`,
        detail: { text, captureId },
      });
    }
  }
  return out;
}

function loadDeviceLog(dir: string): { file?: string; lines: DeviceLogLine[] } {
  for (const name of DEVICE_LOG_FILES) {
    const path = join(dir, name);
    if (!existsSync(path)) continue;
    const lines = readLines(path).map<DeviceLogLine>((text, i) => ({
      kind: "device-log",
      text,
      file: name,
      line: i + 1,
      summary: text.length > 160 ? text.slice(0, 160) + "…" : text,
    }));
    return { file: name, lines };
  }
  return { lines: [] };
}

function loadTrace(dir: string): TraceSpan[] {
  const json = readJsonIfPresent(join(dir, TRACE_FILE));
  if (!Array.isArray(json)) return [];
  const out: TraceSpan[] = [];
  json.forEach((raw, i) => {
    const rec = asRecord(raw);
    if (!rec) return;
    const ts = asNumber(rec.ts) ?? 0;
    const name = asString(rec.name) ?? "";
    const category = asString(rec.cat) ?? "";
    out.push({
      kind: "trace",
      name,
      category,
      ts,
      durationUs: asNumber(rec.dur) ?? 0,
      args: asRecord(rec.args) ?? {},
      file: TRACE_FILE,
      line: i,
      timeMs: Math.round(ts / 1000),
      summary: `${category}: ${name}`,
    });
  });
  return out;
}

// ---------------------------------------------------------------------------------------------
// Summary
// ---------------------------------------------------------------------------------------------

function normalizeOutcome(v: string | undefined): string | undefined {
  if (!v) return undefined;
  const upper = v.toUpperCase();
  if (upper.startsWith("PASS") || upper.startsWith("SUCCEEDED")) return "PASSED";
  if (upper.startsWith("FAIL")) return "FAILED";
  if (upper.startsWith("CANCEL")) return "CANCELLED";
  if (upper.startsWith("TIMEOUT")) return "TIMEOUT";
  return upper;
}

function outcomeFromStatus(status: SessionStatus | undefined): string | undefined {
  if (!status) return undefined;
  const short = shortClass(status.class);
  switch (short) {
    case "Succeeded":
    case "SucceededWithSelfHeal":
      return "PASSED";
    case "Failed":
    case "FailedWithSelfHeal":
    case "MaxCallsLimitReached":
      return "FAILED";
    case "Cancelled":
      return "CANCELLED";
    case "TimeoutReached":
      return "TIMEOUT";
    default:
      return undefined;
  }
}

function buildSummary(
  dir: string,
  logs: LogRecord[],
  streams: StreamEvent[],
  hasNetwork: boolean,
  deviceLogFile: string | undefined,
): SessionSummary {
  const statusLogs = logs.filter((l) => l.type === "TrailblazeSessionStatusChangeLog");
  const started = statusLogs
    .map((l) => (l.log as TrailblazeSessionStatusChangeLog).sessionStatus)
    .find((s) => shortClass(s.class) === "Started") as Started | undefined;
  const ended = statusLogs
    .map((l) => (l.log as TrailblazeSessionStatusChangeLog).sessionStatus)
    .find((s) => s.class.includes(".Ended."));
  const endedLog = statusLogs.find((l) => (l.log as TrailblazeSessionStatusChangeLog).sessionStatus.class.includes(".Ended."));

  const result = asRecord(readJsonIfPresent(join(dir, SESSION_RESULT_FILE)));
  const ci = asRecord(readJsonIfPresent(join(dir, CI_CONTEXT_FILE)));
  const firstLog = logs[0]?.log as { session?: string } | undefined;

  const trailConfig = started?.trailConfig ?? undefined;
  const device = started?.trailblazeDeviceInfo;
  const classifiers = device?.classifiers ?? [];
  const appInfo = asRecord(started?.targetAppInfo);

  const platform =
    asString(result?.platform)?.toLowerCase() ??
    device?.platform?.toLowerCase() ??
    trailConfig?.platform?.toLowerCase() ??
    classifiers.find((c) => ["android", "ios", "web", "desktop"].includes(c.toLowerCase()))?.toLowerCase();

  const startedAtMs = asNumber(result?.started_at_epoch_ms) ?? statusLogs[0]?.timeMs ?? logs[0]?.timeMs;
  const endedAtMs = asNumber(result?.completed_at_epoch_ms) ?? endedLog?.timeMs;
  const durationMs = asNumber(result?.duration_ms) ?? asNumber((ended as { durationMs?: unknown } | undefined)?.durationMs) ??
    (startedAtMs !== undefined && endedAtMs !== undefined ? endedAtMs - startedAtMs : undefined);

  // A metadata value may be a list or map; keep it as JSON text rather than dropping it.
  const metadataText = (v: unknown): string | undefined =>
    typeof v === "string" ? v : v !== null && typeof v === "object" ? JSON.stringify(v) : undefined;
  const metadata: Record<string, string> = {};
  for (const [k, v] of Object.entries({ ...trailConfig?.metadata, ...asRecord(result?.metadata) })) {
    const text = metadataText(v);
    if (text !== undefined) metadata[k] = text;
  }

  const summary: SessionSummary = {
    id: asString(result?.session_id) ?? firstLog?.session ?? basename(dir),
    path: dir,
    title: asString(result?.title) ?? trailConfig?.title ?? undefined,
    trailId: trailConfig?.id ?? undefined,
    testKey: asString(result?.test_key) ?? trailConfig?.id ?? undefined,
    target: trailConfig?.target ?? undefined,
    platform,
    appId: asString(result?.app_id) ?? asString(appInfo?.appId) ?? asString(appInfo?.packageName) ?? asString(appInfo?.bundleId),
    appVersion: asString(result?.app_version_name) ?? asString(appInfo?.versionName) ?? asString(appInfo?.version),
    driver: trailConfig?.driver ?? device?.trailblazeDriverType ?? undefined,
    deviceClassifiers: classifiers,
    outcome: normalizeOutcome(asString(result?.outcome)) ?? outcomeFromStatus(ended) ?? "UNKNOWN",
    failureReason: asString(result?.failure_reason) ?? asString((ended as { exceptionMessage?: unknown } | undefined)?.exceptionMessage),
    startedAtMs,
    endedAtMs,
    durationMs,
    metadata,
    eventStreams: [...new Set(streams.map((s) => s.stream))],
    hasNetworkCapture: hasNetwork,
    hasDeviceLog: deviceLogFile !== undefined,
  };
  const jobId = asString(ci?.ci_job_id) ?? asString(result?.ci_job_id);
  if (jobId || ci) {
    summary.ci = {
      jobId,
      agentName: asString(ci?.ci_agent_name) ?? asString(result?.ci_agent_name),
      zipFilename: asString(ci?.logs_zip_filename) ?? asString(result?.logs_zip_filename),
    };
  }
  return summary;
}

// ---------------------------------------------------------------------------------------------
// The session
// ---------------------------------------------------------------------------------------------

/**
 * One loaded session. Every accessor returns a `Records` list whose items are ready-made evidence,
 * so `session.network.find({...})` can go straight into a footprint's `evidence`.
 */
export class SurveySession {
  /** Absolute path of the session directory. */
  readonly dir: string;
  readonly summary: SessionSummary;
  /** Every Trailblaze log record, in session order. */
  readonly logs: Records<LogRecord, LogQuery>;
  /** Every tool invocation. */
  readonly tools: Records<ToolCall, ToolQuery>;
  /** Every prompt step of the trail. */
  readonly objectives: Records<Objective, ObjectiveQuery>;
  /** Every HTTP exchange the capture saw. Empty when the session had no network capture. */
  readonly network: Records<NetworkRequest, NetworkQuery>;
  /** Analytics events normalized from every analytics stream. */
  readonly analytics: Records<AnalyticsEvent, AnalyticsQuery>;
  /** Raw rows of every `events/*.ndjson` stream. */
  readonly events: Records<StreamEvent, EventQuery>;
  /** Every string that was visible on screen at some capture. */
  readonly screenText: Records<ScreenText, ScreenTextQuery>;
  /** Lines of the device log, when captured. */
  readonly deviceLog: Records<DeviceLogLine, DeviceLogQuery>;
  /** Spans of the session trace. */
  readonly trace: Records<TraceSpan, TraceQuery>;

  private constructor(dir: string) {
    this.dir = dir;
    const logs = loadLogs(dir);
    const streams = loadStreams(dir);
    const network = loadNetwork(dir, streams);
    const deviceLog = loadDeviceLog(dir);
    this.summary = buildSummary(dir, logs, streams, existsSync(join(dir, NETWORK_FILE)) || network.length > 0, deviceLog.file);
    this.logs = new Records(logs, matchLog);
    this.tools = new Records(toolCallsFrom(logs), matchTool);
    this.objectives = new Records(objectivesFrom(logs), matchObjective);
    this.network = new Records(network, matchNetwork);
    this.events = new Records(streams, matchEvent);
    this.analytics = new Records(analyticsFrom(streams), matchAnalytics);
    this.screenText = new Records(loadScreenText(dir), matchScreenText);
    this.deviceLog = new Records(deviceLog.lines, matchDeviceLog);
    this.trace = new Records(loadTrace(dir), matchTrace);
  }

  /** Loads the session at `dir`. Throws when `dir` holds no Trailblaze log records. */
  static load(dir: string): SurveySession {
    if (!isSessionDirectory(dir)) {
      throw new Error(`Not a session directory (no Trailblaze log records found): ${dir}`);
    }
    return new SurveySession(dir);
  }

  get id(): string {
    return this.summary.id;
  }

  /** Rows of one named stream, e.g. `session.stream("crash")` for `events/crash.ndjson`. */
  stream(name: string): StreamEvent[] {
    return this.events.filter({ stream: name });
  }

  /** Whether a session-relative file exists. */
  hasFile(relativePath: string): boolean {
    return existsSync(join(this.dir, relativePath));
  }

  /** Reads a session-relative text file, or undefined when absent. */
  readText(relativePath: string): string | undefined {
    const path = join(this.dir, relativePath);
    return existsSync(path) ? readFileSync(path, "utf8") : undefined;
  }

  /** Reads and parses a session-relative JSON file, or undefined when absent or malformed. */
  readJson<T = unknown>(relativePath: string): T | undefined {
    return readJsonIfPresent(join(this.dir, relativePath)) as T | undefined;
  }

  /**
   * Hand-built evidence for an observation no accessor covers. `file` is session-relative; pass
   * the absolute path of something inside the session and it is relativized.
   */
  evidence(summary: string, file: string, extra: Partial<Omit<SessionRecord, "kind" | "summary" | "file">> = {}): SessionRecord {
    const rel = file.startsWith("/") ? relative(this.dir, file) : file;
    return { kind: "custom", summary, file: rel, ...extra };
  }
}

