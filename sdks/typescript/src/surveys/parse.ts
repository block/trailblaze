// Turns capture rows into survey records. No filesystem access, so the same parsing serves a
// session read from disk after the run and one read through `ctx.events` while the trail runs.

import type { AnalyticsEvent, NetworkRequest, StreamEvent } from "./types.js";

const NETWORK_FILE = "network.ndjson";

export function asRecord(v: unknown): Record<string, unknown> | undefined {
  return v !== null && typeof v === "object" && !Array.isArray(v) ? (v as Record<string, unknown>) : undefined;
}

export function asString(v: unknown): string | undefined {
  return typeof v === "string" ? v : undefined;
}

export function asNumber(v: unknown): number | undefined {
  return typeof v === "number" && Number.isFinite(v) ? v : undefined;
}

/**
 * An absolute URL's parts, read without the `URL` global, which the scripted-tool sandbox lacks.
 * Undefined for anything that is not `scheme://authority...`.
 */
function splitUrl(url: string): { host: string; path: string; search: string } | undefined {
  const m = /^([a-z][a-z0-9+.-]*):\/\/([^/?#]*)([^?#]*)(\?[^#]*)?/i.exec(url);
  if (!m) return undefined;
  const scheme = m[1]!.toLowerCase();
  let host = m[2]!.replace(/^[^@]*@/, "").toLowerCase();
  const port = /:(\d*)$/.exec(host);
  if (port && (port[1] === "" || (scheme === "http" && port[1] === "80") || (scheme === "https" && port[1] === "443"))) {
    host = host.slice(0, port.index);
  }
  const path = m[3] || (host ? "/" : "");
  return { host, path, search: m[4] && m[4] !== "?" ? m[4] : "" };
}

function hostOf(url: string): string {
  return splitUrl(url)?.host ?? "";
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
  // Not an absolute URL: whatever the capture wrote stands.
  const u = splitUrl(url);
  if (u) {
    if (!path) path = u.path;
    if (query === undefined && u.search) query = u.search.slice(1);
  }
  return query ? { path, query } : { path };
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

export interface BodyRef {
  inlineText?: string;
  blobPath?: string;
}

/** Records the sidecar a body came out of, so a finding that rests on it can cite the file. */
function noteBodyFile(r: NetworkRequest, path: string | undefined): void {
  if (path === undefined) return;
  r.bodyFiles = [...new Set([...(r.bodyFiles ?? []), path])];
}

function describeRequest(r: NetworkRequest, finished: boolean): string {
  const status = r.failed ? "failed" : r.status !== undefined ? String(r.status) : finished ? "no response" : "in flight";
  return `${r.method} ${r.path || r.url} → ${status}`;
}

/**
 * Settles what a request's rows say. In a finished session a request that never got a response
 * failed; while the trail runs it may still be waiting for one, so it is neither ok nor failed.
 */
function finishRequest(r: NetworkRequest, finished: boolean): NetworkRequest {
  r.ok = r.status !== undefined && r.status >= 200 && r.status < 400;
  r.failed = r.failed || (finished && r.status === undefined);
  r.summary = describeRequest(r, finished);
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

/**
 * Joins capture rows into one request per exchange: a `REQUEST_START`, then a `RESPONSE_END` or
 * `FAILED`, paired by `id`. `readBody` turns a body reference into its text; a reader that cannot
 * open blob files returns only inline bodies. `finished: false` reads a session still running, where
 * a request with no response yet is in flight rather than failed.
 */
export function networkFromRows(
  rows: Iterable<{ line?: number; row: unknown }>,
  streams: StreamEvent[],
  readBody: (ref: unknown) => { text?: string; blobFile?: string },
  options: { file?: string; finished?: boolean } = {},
): NetworkRequest[] {
  const file = options.file ?? NETWORK_FILE;
  const finished = options.finished ?? true;
  const byId = new Map<string, NetworkRequest>();
  const order: string[] = [];
  let n = 0;
  {
    for (const { line, row } of rows) {
      n++;
      const rec = asRecord(row);
      if (!rec) continue;
      const id = asString(rec.id) ?? `line-${line ?? n}`;
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
          file,
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
        const reqBody = readBody(rec.requestBodyRef);
        r.requestBody = reqBody.text ?? r.requestBody;
        noteBodyFile(r, reqBody.blobFile);
      } else if (phase === "RESPONSE_END") {
        r.status = asNumber(rec.statusCode);
        r.durationMs = asNumber(rec.durationMs);
        r.endMs = asNumber(rec.timestampMs);
        r.responseHeaders = asRecord(rec.responseHeaders) as Record<string, string> | undefined;
        const resBody = readBody(rec.responseBodyRef);
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
  return order.map((id) => finishRequest(byId.get(id)!, finished));
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

/**
 * Turns rows of any stream whose name mentions analytics into named events. Two row shapes are
 * understood: a flat `{name|event, source?, properties?}` object, and a table-row shape
 * `{columnItems: {Event, Source?, Properties?}}` whose properties are a JSON string.
 */
export function analyticsFrom(streams: StreamEvent[]): AnalyticsEvent[] {
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

/**
 * One stream row as a record. A row in the `{timeMs, data}` envelope yields its `data`; any other
 * row is its own data, timed by `timeMs` or `timestampMs`.
 */
export function streamEventOf(stream: string, row: unknown, file: string, line?: number, timeMs?: number): StreamEvent {
  const rec = asRecord(row);
  const time = timeMs ?? asNumber(rec?.timeMs) ?? asNumber(rec?.timestampMs);
  const data = rec && "data" in rec ? rec.data : row;
  return { kind: "event", stream, data, file, line, timeMs: time, summary: `${stream} event` };
}

