// A survey run while the trail is still running.
//
// The session is read through `ctx.events`, so the same code runs on the host and on a device, and
// nothing here touches the filesystem. Only the event streams exist mid-run: `network`, `analytics`
// and `events` hold what was captured so far. Everything that exists only once the session ends —
// its outcome and duration, the log records and tool calls, screen text, the device log, the trace,
// files — throws when read, naming `requiresFinishedSession`, the flag that moves the survey to
// after the run.

import { type LogRecords, Records, matchAnalytics, matchEvent, matchNetwork } from "./match.js";
import { analyticsFrom, networkFromRows, redactUrl } from "./parse.js";
import { NO_RECORD, READ_EVERYTHING, TABLE_ROW, planReads, type ReadPlan, type StreamRead } from "./live-plan.js";
import { runSurvey } from "./run-one.js";
import type { SurveySession } from "./session.js";
import { surveyApplies, type TargetCatalog } from "./scope.js";
import { explainFinding, findingOf, isDeclarative, isSurvey, provesOnlyPresence, withId, type FindingExplanation, type SurveyDefinition } from "./survey.js";
import type { Finding, NetworkRequest, SessionSummary, StreamEvent } from "./types.js";

/** What one `ctx.events` read reports, besides its records. */
interface LiveScanResult {
  captured: boolean;
  complete: boolean;
  incompleteReason?: string;
  cursor: number;
  /** The session-relative file the stream is read from, when it has one. */
  file?: string;
  /** What the host scanned to answer. A host that sends none can't have its filtering checked. */
  scan?: { lines?: number };
}

/** What a live session reads through: `ctx.events`, or a fake of it in a test. */
export interface LiveEventSource {
  list(): Promise<string[]>;
  query(
    stream: string,
    options?: { match?: Record<string, unknown>; after?: number; waitMs?: number; limit?: number },
  ): Promise<LiveScanResult & { events: Array<{ timeMs: number; data: unknown }>; truncated?: boolean }>;
  /**
   * How many records match, none of them returned. Without it every stream is read whole, as is
   * every stream when a host answers without a `count`.
   */
  count?(stream: string, options?: { match?: Record<string, unknown>; after?: number }): Promise<LiveScanResult & { count?: number }>;
}

/** Thrown when a survey running mid-trail reads something that exists only once the session ends. */
export class FinishedSessionRequiredError extends Error {
  constructor(what: string) {
    super(
      `${what} only exists once the session has finished, and this survey is running while the trail runs. ` +
        `Add requiresFinishedSession: true to the survey to run it after the session instead.`,
    );
    this.name = "FinishedSessionRequiredError";
  }
}

/** The root capture stream; every other stream is an `events/<name>.ndjson` file. */
const NETWORK_STREAM = "network";
/**
 * Read even when not listed: a stream whose capture lost its first write has no file, and an
 * absence check must still see that loss.
 */
const ALWAYS_READ = [NETWORK_STREAM, "analytics"];
/** Records per read, the most a query returns. */
const PAGE = 1000;
/**
 * The most records, and characters of record JSON, one survey holds mid-trail. A device runs the
 * survey in the instrumentation's heap, beside the app under test; a survey that would hold more
 * stops reading and fails rather than take the run down with it.
 */
const MAX_RECORDS = 20_000;
const MAX_RECORD_CHARS = 16_000_000;

export interface LiveSessionOptions {
  /** This session's id, for findings. */
  id?: string;
  /** What is already known about the run: platform, target, app, driver, device classifiers. */
  summary?: Partial<Pick<SessionSummary, "title" | "trailId" | "target" | "platform" | "appId" | "driver" | "deviceClassifiers">>;
  /** The most records a read may hold. Default 20,000. */
  maxRecords?: number;
  /** The most characters of record JSON a read may hold. Default 16,000,000. */
  maxRecordChars?: number;
}

export interface LiveRead {
  session: SurveySession;
  /** False when a capture writing a stream read here recorded losing data, or the read was [capped]. */
  complete: boolean;
  incompleteReason?: string;
  /** Streams read here that a capture is writing; one missing was never captured. */
  captured: ReadonlySet<string>;
  /** What the reader holds: records, and characters of their JSON. */
  held: { records: number; chars: number };
  /** Why the reader stopped holding records, when it reached its limit. The read can't judge the session. */
  capped?: string;
  /** Whether the read that passed the limit was filtered on an analytics event name, so a narrower name would hold fewer. */
  cappedOnFilter?: boolean;
}

const RANK = { health: 0, filtered: 1, full: 2 } as const;

/**
 * Reads the session's streams through `ctx.events`. Each read fetches only what arrived since the
 * last one, so polling a survey costs the new records, not the whole session again. A [ReadPlan]
 * narrows what each stream contributes; the host filters, so only what the plan keeps reaches here.
 */
export class LiveSessionReader {
  private readonly rows = new Map<string, Array<{ timeMs: number; data: unknown }>>();
  private readonly cursors = new Map<string, number>();
  private readonly losses = new Map<string, string>();
  private readonly files = new Map<string, string>();
  private readonly captured = new Set<string>();
  private readonly reads = new Map<string, StreamRead>();
  private readonly chars = new Map<string, number>();
  /** False once the host answers a count without one: from then on every stream is read whole. */
  private filtering: boolean;
  private capped?: string;
  private cappedOnFilter = false;

  constructor(
    private readonly source: LiveEventSource,
    private readonly options: LiveSessionOptions = {},
  ) {
    this.filtering = typeof source.count === "function";
  }

  /** What is known about the run, for a survey's `platforms`, `appIds` and `targets`. */
  get summary(): LiveSessionOptions["summary"] {
    return this.options.summary;
  }

  /**
   * Reads every listed stream, plus [alsoCheck] (streams a survey names) and the network and
   * analytics streams even when they have no file, so a loss recorded for any of them counts.
   * [plan] says which records each one contributes; by default, all of them.
   */
  async read(alsoCheck: Iterable<string> = [], plan: ReadPlan = READ_EVERYTHING): Promise<LiveRead> {
    const streams = await this.source.list();
    for (const stream of new Set([...streams, ...ALWAYS_READ, ...alsoCheck])) {
      // Past the limit nothing more is held, and the read can't pass, so nothing more is read.
      if (this.capped) break;
      await this.readStream(stream, plan.readFor(stream));
    }
    const reasons = [...this.losses].map(([stream, reason]) => `${stream}: ${reason}`);
    if (this.capped) reasons.push(this.capped);
    return {
      session: this.build(streams),
      complete: reasons.length === 0,
      ...(reasons.length > 0 ? { incompleteReason: reasons.join("; ") } : {}),
      captured: new Set(this.captured),
      held: this.held(),
      ...(this.capped ? { capped: this.capped, cappedOnFilter: this.cappedOnFilter } : {}),
    };
  }

  /**
   * Waits up to `ms` for more records. Returns early when one arrives on the stream that last grew,
   * so a burst is picked up quickly; anything on another stream is read at the next `read()`.
   */
  async waitForMore(ms: number): Promise<void> {
    if (ms <= 0) return;
    const stream = this.lastGrown ?? this.cursors.keys().next().value ?? NETWORK_STREAM;
    const after = this.cursors.get(stream);
    // A wait only sleeps: what it returns is read again, in order, by the next `read()`.
    await this.source.query(stream, after === undefined ? { waitMs: ms, limit: 1 } : { after, waitMs: ms, limit: 1 });
  }

  private lastGrown?: string;

  private held(): { records: number; chars: number } {
    let records = 0;
    for (const rows of this.rows.values()) records += rows.length;
    let chars = 0;
    for (const n of this.chars.values()) chars += n;
    return { records, chars };
  }

  private async readStream(stream: string, wanted: StreamRead): Promise<void> {
    // Every stream gets its place on the first read, whatever it holds, so streams keep one order.
    if (!this.rows.has(stream)) this.rows.set(stream, []);
    const read = this.settle(stream, this.filtering ? wanted : { kind: "full" });
    if (read.kind === "full") return this.drain(stream);
    const done = read.kind === "health" ? await this.readHealth(stream) : await this.drainFiltered(stream, read);
    if (done) return;
    // The host could not be shown to keep what the survey needs: read the stream whole, from where
    // this read started, as every read of it will be from now on.
    this.reads.set(stream, { kind: "full" });
    this.restart(stream);
    return this.drain(stream);
  }

  /** The read to use for [stream]: what was used before, unless [wanted] needs more of it. */
  private settle(stream: string, wanted: StreamRead): StreamRead {
    const had = this.reads.get(stream);
    let next = wanted;
    if (had) {
      if (RANK[had.kind] > RANK[wanted.kind] || (had.kind === wanted.kind && had.kind !== "filtered")) return had;
      if (had.kind === "filtered" && wanted.kind === "filtered") {
        if (JSON.stringify(had.match) === JSON.stringify(wanted.match)) return had;
        next = { kind: "full" };
      }
      if (had.kind === "full") return had;
      this.restart(stream);
    }
    this.reads.set(stream, next);
    return next;
  }

  /** Forgets what [stream] holds, so it is read again from its start. Its losses stay. */
  private restart(stream: string): void {
    this.rows.get(stream)!.length = 0;
    this.chars.delete(stream);
    this.cursors.delete(stream);
  }

  /** Notes what any read of [stream] says about its capture. False when it has no file. */
  private note(stream: string, result: LiveScanResult): boolean {
    // A loss stays: rows dropped earlier are still missing after a later page comes through whole.
    // It counts with no file too: a capture can lose a stream's first write.
    if (!result.complete && !this.losses.has(stream)) this.losses.set(stream, result.incompleteReason ?? "the capture recorded losing data");
    if (!result.captured) return false;
    this.captured.add(stream);
    if (result.file) this.files.set(stream, result.file);
    return true;
  }

  private hold(stream: string, events: Array<{ timeMs: number; data: unknown }>, chars = charsOf(events)): void {
    if (events.length === 0) return;
    // One push per record: spreading a stream's worth of matches would pass the engine's argument limit.
    const rows = this.rows.get(stream)!;
    for (const e of events) rows.push(e);
    this.chars.set(stream, (this.chars.get(stream) ?? 0) + chars);
  }

  /**
   * Whether holding [records] more records, [chars] characters of them, would take the reader past
   * its limit; when it would, the read is capped and nothing more is read. [filtered] says the
   * records came through an analytics name match.
   */
  private overLimit(stream: string, records: number, chars: number, filtered = false): boolean {
    const max = { records: this.options.maxRecords ?? MAX_RECORDS, chars: this.options.maxRecordChars ?? MAX_RECORD_CHARS };
    const held = this.held();
    if (held.records + records <= max.records && held.chars + chars <= max.chars) return false;
    this.cappedOnFilter = filtered;
    this.capped =
      `reading ${stream} took this survey past the ${max.records} records or ${max.chars} characters of records ` +
      `one survey may hold mid-trail, so it stopped reading`;
    return true;
  }

  private async drain(stream: string): Promise<void> {
    for (;;) {
      const after = this.cursors.get(stream);
      const result = await this.source.query(stream, after === undefined ? { limit: PAGE } : { after, limit: PAGE });
      if (!this.note(stream, result)) return;
      if (result.events.length > 0) this.lastGrown = stream;
      const chars = charsOf(result.events);
      if (this.overLimit(stream, result.events.length, chars)) return;
      this.hold(stream, result.events, chars);
      this.cursors.set(stream, result.cursor);
      if (!result.truncated && result.events.length < PAGE) return;
    }
  }

  /** A count that reads no record: captured, lost data, and where the stream ends. False when the host can't count. */
  private async readHealth(stream: string): Promise<boolean> {
    const after = this.cursors.get(stream);
    const result = await this.source.count!(stream, after === undefined ? { match: NO_RECORD } : { match: NO_RECORD, after });
    if (!this.counted(result)) return false;
    if (this.note(stream, result)) this.cursors.set(stream, result.cursor);
    return true;
  }

  /**
   * The records [read]'s match keeps, then a check that the host saw every record as a table row:
   * the match only keeps every record the survey needs when each is one. False when it can't be
   * shown, and nothing is held.
   */
  private async drainFiltered(stream: string, read: Extract<StreamRead, { kind: "filtered" }>): Promise<boolean> {
    const start = this.cursors.get(stream);
    const from = (after: number | undefined) => (after === undefined ? {} : { after });
    let cursor = start;
    const kept: Array<{ timeMs: number; data: unknown }> = [];
    let keptChars = 0;
    let settled = false;
    if (read.absentOnly) {
      // Only an absence rests on these records: none matching settles it without reading one.
      const result = await this.source.count!(stream, { match: read.match, ...from(start) });
      if (!this.counted(result)) return false;
      if (!this.note(stream, result)) return true;
      if (result.count === 0) {
        cursor = result.cursor;
        settled = true;
      }
    }
    if (!settled) {
      for (;;) {
        const result = await this.source.query(stream, { match: read.match, ...from(cursor), limit: PAGE });
        if (!this.note(stream, result)) return true;
        kept.push(...result.events);
        keptChars += charsOf(result.events);
        // Checked on every page, not once the stream is staged. Read whole, the stream would hold
        // these records and more, so a match that passes the limit caps the read either way.
        if (this.overLimit(stream, kept.length, keptChars, true)) return true;
        cursor = result.cursor;
        if (!result.truncated && result.events.length < PAGE) break;
      }
    }
    const shape = await this.source.count!(stream, { match: TABLE_ROW, ...from(start) });
    if (!this.counted(shape) || shape.scan?.lines !== shape.count || shape.cursor < cursor!) return false;
    this.note(stream, shape);
    if (cursor !== start) this.lastGrown = stream;
    this.hold(stream, kept, keptChars);
    this.cursors.set(stream, cursor!);
    return true;
  }

  /** Whether [result] is a count. A host that ignores `count` answers with records, and is read whole from then on. */
  private counted(result: LiveScanResult & { count?: number }): result is LiveScanResult & { count: number } {
    if (typeof result.count === "number" && typeof result.scan?.lines === "number") return true;
    this.filtering = false;
    return false;
  }

  private build(streams: string[]): SurveySession {
    // Evidence names the file the host read and the record's time, not a line: the host skips
    // other devices' records and oversized lines, so a position in what came back is not one in
    // the file.
    const events: StreamEvent[] = [];
    for (const [stream, rows] of this.rows) {
      if (stream === NETWORK_STREAM) continue;
      const file = this.files.get(stream) ?? `events/${stream}.ndjson`;
      for (const r of rows) events.push({ kind: "event", stream, data: r.data, file, timeMs: r.timeMs, summary: `${stream} event` });
    }
    events.sort((a, b) => (a.timeMs ?? 0) - (b.timeMs ?? 0));
    // The host moved each record onto its clock (`timeMs`); the row's own stamp is the device's, so
    // network and other streams would order differently on a device whose clock is off.
    const networkRows = (this.rows.get(NETWORK_STREAM) ?? []).map((r) => ({
      row: typeof r.timeMs === "number" ? { ...(r.data as object), timestampMs: r.timeMs } : r.data,
    }));
    // A body the capture kept in its own file is not read mid-run; an inline body is. Reading one
    // that is in a file throws, so neither a presence nor an absence check is judged without it.
    const network = networkFromRows(
      networkRows,
      events,
      (ref) => {
        if (ref === undefined || ref === null) return {};
        const text = (ref as { inlineText?: unknown }).inlineText;
        return { text: typeof text === "string" ? text : BODY_IN_FILE };
      },
      { finished: false, ...(this.files.has(NETWORK_STREAM) ? { file: this.files.get(NETWORK_STREAM) } : {}) },
    );
    for (const r of network) {
      for (const field of ["requestBody", "responseBody"] as const) {
        if (r[field] === BODY_IN_FILE) bodyInFile(r, field);
      }
    }
    return liveSession(this.options, streams, network, events);
  }
}

/** Characters of record JSON in [events], as the reader's limit counts them. */
function charsOf(events: Array<{ data: unknown }>): number {
  let chars = 0;
  for (const e of events) chars += JSON.stringify(e.data)?.length ?? 0;
  return chars;
}

/** Stands in for a body kept in its own file until [bodyInFile] replaces it with a throwing getter. */
const BODY_IN_FILE = "\u0000trailblaze:body-in-file\u0000";

function bodyInFile(r: NetworkRequest, field: "requestBody" | "responseBody"): void {
  const which = field === "requestBody" ? "request" : "response";
  Object.defineProperty(r, field, {
    enumerable: false,
    configurable: true,
    get: () => {
      throw new FinishedSessionRequiredError(`The ${which} body of ${r.method} ${redactUrl(r.url)}, which the capture kept in its own file,`);
    },
  });
}

function liveSession(
  options: LiveSessionOptions,
  streams: string[],
  network: ReturnType<typeof networkFromRows>,
  events: StreamEvent[],
): SurveySession {
  const ended = (what: string) => (): never => {
    throw new FinishedSessionRequiredError(what);
  };
  const id = options.id ?? "live";
  const summary = {
    id,
    path: "",
    deviceClassifiers: [],
    metadata: {},
    eventStreams: streams.filter((s) => s !== NETWORK_STREAM),
    hasNetworkCapture: streams.includes(NETWORK_STREAM),
    ...options.summary,
  } as Partial<SessionSummary>;
  for (const field of ["outcome", "failureReason", "startedAtMs", "endedAtMs", "durationMs", "appVersion", "ci", "hasDeviceLog"] as const) {
    Object.defineProperty(summary, field, { enumerable: false, get: ended(`summary.${field}`) });
  }
  // Any use of a collection that only exists at the end throws, whichever method a survey calls.
  // `then` stays undefined so that returning one from an async function is not mistaken for a promise.
  const unavailable = <T>(name: string): T =>
    new Proxy({}, { get: (_, prop) => (prop === "then" || typeof prop === "symbol" ? undefined : ended(`session.${name}`)()) }) as T;
  const session: SurveySession = {
    dir: "",
    final: false,
    summary: summary as SessionSummary,
    get id() {
      return id;
    },
    network: new Records(network, matchNetwork),
    analytics: new Records(analyticsFrom(events), matchAnalytics),
    events: new Records(events, matchEvent),
    trailblazeLogs: unavailable<LogRecords>("trailblazeLogs"),
    logs: unavailable<LogRecords>("trailblazeLogs"),
    tools: unavailable<SurveySession["tools"]>("tools"),
    objectives: unavailable<SurveySession["objectives"]>("objectives"),
    screenText: unavailable<SurveySession["screenText"]>("screenText"),
    deviceLog: unavailable<SurveySession["deviceLog"]>("deviceLog"),
    trace: unavailable<SurveySession["trace"]>("trace"),
    stream: <T = unknown>(name: string) => session.events.filter({ stream: name }) as StreamEvent<T>[],
    hasFile: ended("session.hasFile()"),
    readText: ended("session.readText()"),
    readJson: ended("session.readJson()"),
    evidence: (summaryLine, file, extra = {}) => ({ kind: "custom", summary: summaryLine, file, ...extra }),
  } as SurveySession;
  return session;
}

export interface LiveSurveyOptions {
  /** How long to wait for a finding. Default 0: judge what is captured now. */
  waitMs?: number;
  /** How long one wait for new records lasts before the survey runs again. Default 500. */
  pollMs?: number;
  log?: (message: string) => void;
  /** The workspace's targets, so a survey scoped to a target applies to every session on its apps. */
  targets?: TargetCatalog;
  /** Clock, for tests. */
  now?: () => number;
}

/**
 * Streams an absence in [def] may rest on. One never captured holds no records, so "not seen there"
 * proves nothing. A declarative finding names its own; a handler's reads cannot be seen, so it is every
 * stream a mid-trail survey can read.
 */
function absenceStreams(def: SurveyDefinition, named: string[]): string[] {
  if (!isDeclarative(def)) return [...new Set([...ALWAYS_READ, ...named])];
  const out = new Set<string>();
  const f = findingOf(def.spec);
  if (f) {
    for (const queries of [f, f.absent ?? {}]) {
      if (queries.network !== undefined) out.add(NETWORK_STREAM);
      if (queries.analytics !== undefined) out.add("analytics");
    }
  }
  for (const stream of named) out.add(stream);
  return [...out];
}

/** [read], incomplete when one of [streams] was never captured. Any stream named like analytics counts for `analytics`. */
function withUncaptured(read: LiveRead, streams: string[]): LiveRead {
  const has = (s: string) => read.captured.has(s) || (s === "analytics" && [...read.captured].some((c) => /analytic/i.test(c)));
  const missing = streams.filter((s) => !has(s));
  if (missing.length === 0) return read;
  const reason = `${missing.join(", ")} ${missing.length === 1 ? "was" : "were"} never captured`;
  return { ...read, complete: false, incompleteReason: read.incompleteReason ? `${read.incompleteReason}; ${reason}` : reason };
}

/** Stream names a survey's finding queries filter on, so their capture health is read too. */
function streamsNamedBy(def: SurveyDefinition): string[] {
  const out: string[] = [];
  const visit = (q: unknown) => {
    for (const one of Array.isArray(q) ? q : [q]) {
      const stream = (one as { stream?: unknown } | undefined)?.stream;
      if (typeof stream === "string") out.push(stream);
    }
  };
  const f = findingOf(def.spec);
  if (f) {
    for (const queries of [f, f.absent ?? {}]) {
      visit(queries.analytics);
      visit(queries.events);
    }
  }
  return out;
}

export interface LiveSurveyResult {
  passed: boolean;
  findings: Finding[];
  /** Why it failed, written for a test report. */
  message: string;
  waitedMs: number;
}

/**
 * Runs a survey against what is captured so far and passes on its first finding. Until then it waits
 * for more records and runs again, up to `waitMs`. It fails when the time runs out, naming which of
 * a finding's queries matched and which did not, and it fails when the capture lost data, since a
 * record that never arrived may be the one the survey looks for.
 */
export async function runLiveSurvey(def: SurveyDefinition, reader: LiveSessionReader, options: LiveSurveyOptions = {}): Promise<LiveSurveyResult> {
  const now = options.now ?? (() => Date.now());
  const started = now();
  const deadline = started + Math.max(0, options.waitMs ?? 0);
  const pollMs = Math.max(1, options.pollMs ?? 500);
  const name = def.id ?? "survey";
  if (def.spec.requiresFinishedSession) {
    return {
      passed: false,
      findings: [],
      waitedMs: 0,
      message: `Survey ${name} sets requiresFinishedSession: true, so it runs only after the session ends and cannot run mid-trail.`,
    };
  }
  const about = reader.summary;
  if (!surveyApplies(def.spec, (about ?? {}) as SessionSummary, options.targets)) {
    return {
      passed: false,
      findings: [],
      waitedMs: 0,
      message: `Survey ${name} is scoped to ${scopeOf(def.spec)}, and this session is ${describeSession(about)}, so it does not apply here.`,
    };
  }
  const named = streamsNamedBy(def);
  // Run against one read, collecting what the survey reports through `ctx.error()`.
  const judge = async (read: LiveRead) => {
    const errors: string[] = [];
    const findings = await runSurvey(def, read.session, name, options.log, (e) => errors.push(e.message));
    return { findings, errors };
  };
  // A presence-only survey needs no stream it does not find a record on; any other needs every
  // stream its absence rests on to be captured.
  const needed = provesOnlyPresence(def) ? [] : absenceStreams(def, named);
  const plan = planReads(def);
  const readAll = async () => withUncaptured(await reader.read(named, plan), needed);
  const waitedMs = () => now() - started;
  const errorResult = (errors: string[]) => ({
    passed: false,
    findings: [],
    waitedMs: waitedMs(),
    message: `Survey ${name} reported an error: ${errors.join("; ")}.`,
  });
  for (;;) {
    let read = await readAll();
    if (read.capped) return { passed: false, findings: [], waitedMs: waitedMs(), message: capped(def, name, read) };
    let { findings, errors } = await judge(read);
    if (errors.length > 0) return errorResult(errors);
    // Proof of absence needs every record; proof of presence does not.
    if (findings.length > 0 && (read.complete || provesOnlyPresence(def))) {
      if (!provesOnlyPresence(def)) {
        // Streams are read one after another, so a record that landed on a stream already read,
        // before the one that completed the match, is missed. Every record that existed when the
        // first read ended is in a second, so an absence that holds there held then.
        read = await readAll();
        if (read.capped) return { passed: false, findings: [], waitedMs: waitedMs(), message: capped(def, name, read) };
        ({ findings, errors } = await judge(read));
        if (errors.length > 0) return errorResult(errors);
      }
      if (findings.length > 0 && (read.complete || provesOnlyPresence(def))) {
        return { passed: true, findings, waitedMs: waitedMs(), message: `Survey ${name} found ${findings.map((f) => f.summary).join("; ")}.` };
      }
    }
    // A pass the second read took back is not a failure while there is time: what ruled it out may
    // be the start of something whose end has not arrived yet.
    const remaining = deadline - now();
    if (remaining <= 0 || !read.complete) {
      return { passed: false, findings: [], waitedMs: waitedMs(), message: failure(def, name, read, waitedMs(), findings) };
    }
    await reader.waitForMore(Math.min(pollMs, remaining));
  }
}

function scopeOf(spec: SurveyDefinition["spec"]): string {
  return [
    spec.platforms ? `platforms ${spec.platforms.join(", ")}` : null,
    spec.appIds ? `app ids ${spec.appIds.join(", ")}` : null,
    spec.targets ? `targets ${spec.targets.join(", ")}` : null,
  ]
    .filter((p): p is string => p !== null)
    .join("; ");
}

function describeSession(about: LiveSessionOptions["summary"]): string {
  const parts = [
    about?.platform ? `platform ${about.platform}` : null,
    about?.appId ? `app ${about.appId}` : null,
    about?.target ? `target ${about.target}` : null,
  ].filter((p): p is string => p !== null);
  return parts.length > 0 ? parts.join(", ") : "of unknown platform, app and target";
}

function capped(def: SurveyDefinition, name: string, read: LiveRead): string {
  // Only a finding filtered on an analytics event name can hold fewer by narrowing; one on network
  // requests or events, or a name the host can't match, reads its streams whole whatever it names.
  const instead = !isDeclarative(def)
    ? `A handler reads every stream whole. Write the check as a finding on an analytics event name, which reads only that event's records, or`
    : read.cappedOnFilter
      ? `Narrow the finding to an analytics event name fewer records carry, or`
      : `This finding reads its streams whole, so`;
  return `Survey ${name} cannot judge this session mid-trail: ${read.capped}. ${instead} move it after the session with requiresFinishedSession: true.`;
}

function failure(def: SurveyDefinition, name: string, read: LiveRead, waitedMs: number, findings: Finding[]): string {
  // A finding on a lossy read is not trusted, since what was lost could have ruled it out; saying
  // "found nothing" then would send a reader after the wrong cause.
  const lead = read.complete
    ? `Survey ${name} found nothing in ${waitedMs} ms.`
    : findings.length > 0
      ? `Survey ${name} found ${findings.map((f) => f.summary).join("; ")}, but the capture lost data, so a dropped record could have ruled that out and it cannot pass (${read.incompleteReason}).`
      : `Survey ${name} found nothing, and the capture lost data, so the record it needs may have been dropped (${read.incompleteReason}).`;
  const finding = findingOf(def.spec);
  if (!finding) return lead;
  return `${lead} ${describe(explainFinding(read.session, finding))}`;
}

function describe(e: FindingExplanation): string {
  const out: string[] = [];
  if (e.matched.length > 0) out.push(`matched ${e.matched.join(", ")}`);
  if (e.missed.length > 0) out.push(`missed ${e.missed.join(", ")}`);
  if (e.vetoedBy.length > 0) out.push(`ruled out by absent ${e.vetoedBy.join(", ")}`);
  const text = out.join("; ");
  return `${text.charAt(0).toUpperCase()}${text.slice(1)}.`;
}

/** The JSON a mid-trail survey run hands back to the tool that asked for it. */
export interface LiveSurveyOutcome {
  survey: string;
  passed: boolean;
  message: string;
  findings: Finding[];
  waitedMs: number;
}

/**
 * Runs the survey exported as `name` from `module`, a survey file's exports. The entry point of a
 * survey bundle: `ctx.surveys.<name>(...)` loads the bundle that holds the survey and calls this.
 */
export async function runNamedLiveSurvey(
  module: Readonly<Record<string, unknown>>,
  name: string,
  events: LiveEventSource,
  options: {
    waitMs?: number;
    sessionId?: string;
    summary?: LiveSessionOptions["summary"];
    targets?: TargetCatalog;
    log?: (message: string) => void;
  } = {},
): Promise<LiveSurveyOutcome> {
  const def = module[name];
  if (!isSurvey(def)) {
    const found = Object.keys(module).filter((k) => isSurvey(module[k]));
    throw new Error(`No survey is exported as ${name}. This file exports: ${found.join(", ") || "(none)"}.`);
  }
  const reader = new LiveSessionReader(events, { id: options.sessionId, summary: options.summary });
  const result = await runLiveSurvey(withId(def, name), reader, { waitMs: options.waitMs, targets: options.targets, log: options.log });
  return { survey: name, ...result };
}
