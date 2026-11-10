// Derived event streams: one pure function that turns what a session captured into a new event stream.
//
//   export const screens = trailblaze.eventStream({
//     name: "screens",
//     description: "Each screen the app showed, in order",
//     derive: (session) => session.analytics.filter({ name: /^screen\./ }).map((e) => ({
//       timeMs: e.timeMs!, data: { screen: e.name.slice("screen.".length) }, from: e,
//     })),
//   });
//
// Once derived it reads like any event stream the session captured, marked `derived`:
// - a survey reads `session.stream("screens")` or `screens.of(session)`, after the run or mid-trail
//   (`ctx.surveys`), and gets the rows as records it can cite as evidence. The survey runner
//   re-derives every loaded stream first (`withDerivedStreams`), so it never reads old rows;
// - another stream lists it in `dependsOn` and reads it the same way, derived first;
// - `trailblaze report --surveys` writes it to the session's `events/<name>.ndjson`, so the report's
//   timeline shows each row as its own event, and readers without the code still have the rows
//   (see `stream-write.ts`).
//
// `derive` must be deterministic: its output depends only on the session (no clock, randomness,
// sampling, network, or environment). A written stream is rebuilt whenever its defining file or the
// session's files change, so a rule change reaches every stored session on its next report.
//
// Nothing here reads files, so a survey bundle that runs mid-trail can import it (`in-process.ts`).

import { matchEvent, Records } from "./match.js";
import { SurveyScope, scopeError, scopeFilterOf, scopedSession, unscopedOf } from "./phase.js";
import type { SurveySession } from "./session.js";
import type { SessionRecord, StreamEvent } from "./types.js";

const BRAND: unique symbol = Symbol.for("trailblaze.stream");

/**
 * A name that is safe as a file name under `events/`: `screens`, `experiment-eval`, `app.flags`. Not
 * ending in `.json`, which the report strips from a stream's file name as a legacy suffix.
 */
export const STREAM_NAME = /^(?!.*\.json$)[a-z0-9][a-z0-9._-]*$/i;

/**
 * The streams Trailblaze captures itself, and their `.<device>` forms. Some are written only as a run
 * shuts down, after its outcome is logged, so a derived stream of that name could claim the file first.
 */
export const CAPTURED_STREAM = /^(?:crash|memory|network|trailhead)(?:\.|$)/i;

/**
 * Other names the report shows on the lane of a stream Trailblaze captures: a `network`
 * segment, and `visible-strings` or `capture-notices` anywhere. A derived stream
 * named so would replace or hide that lane's captured rows. SISTER-IMPL-TAG: report-lane-identity —
 * `laneIdentity` in the report's `run-report-summary-model.ts` folds the same names.
 */
export const CAPTURED_LANE = /(?:^|\.)network(?:\.|$)|visible-strings|capture-notices/i;

/** One row `derive` returns. */
export interface DerivedRowInput<T> {
  /** When it happened, epoch ms. Rows are ordered by it, ties in the order `derive` returned them. */
  timeMs: number;
  /** The row's payload: what a report formatter and a survey read. */
  data: T;
  /** The captured records the row was derived from, so anyone can check it against the raw events. */
  from?: SessionRecord | ReadonlyArray<SessionRecord>;
  /**
   * Whose clock stamped `timeMs`. Absent: the device's when every `from` record's was, else the
   * host's. Give it when `timeMs` came from one record and `from` also cites others. A device time
   * is read on the clock of the device that captured the first device-clocked `from` record, so
   * list the record `timeMs` came from first.
   */
  clock?: "device" | "host";
}

/** Where a derived row came from: a captured record's file and line. */
export interface RowSource {
  file: string;
  line?: number;
}

/** One row of a derived stream, as a survey reads it: a stream event, ready to cite as evidence. */
export interface DerivedRow<T = unknown> extends StreamEvent<T> {
  timeMs: number;
  /** The captured records the row was derived from. Cite these when the row proves a finding. */
  from: ReadonlyArray<SessionRecord>;
  /**
   * With `clock: "device"`, the captured stream whose device's clock stamped `timeMs`: the first
   * device-clocked `from` record's. In a multi-device session the report converts the row on that
   * device's clock, not on the one its own file name would pick.
   */
  clockStream?: string;
}

/** How the report shows one row: its card's label, with optional tone, badges and key/value fields. */
export interface RowView {
  label: string;
  tone?: "ok" | "warn" | "error";
  badges?: ReadonlyArray<{ text: string; tone?: "ok" | "warn" | "error" }>;
  fields?: ReadonlyArray<{ k: string; v: string; href?: string }>;
}

export interface EventStreamSpec<T> {
  /** The stream's name and the file it is written to: `events/<name>.ndjson`. */
  name: string;
  /** The report's name for its lane. Unset, the lane is named from `name`. */
  title?: string;
  /** What each row is, for readers of the report. */
  description?: string;
  /**
   * How the report shows a row; its full `data` stays one click away. Unset, the report shows the
   * raw `data`. Like `derive`, a pure function of the row.
   */
  row?(data: T): RowView;
  /**
   * How the report's lane for this stream folds rows that land in the same instant: `any` (the
   * default) folds every burst into one card, `label` only runs of one label, `none` keeps every row.
   * A stream whose rows fire in bursts and each matter, such as flag evaluations at launch, wants `none`.
   */
  lane?: { fold?: "any" | "label" | "none" };
  /**
   * The rows of one session. Pure: the same session gives the same rows. A session the stream means
   * nothing for gets no rows, which also decides where the report shows it: a stream names no targets
   * of its own, so `of()` and the report can never disagree on which sessions it covers. Mid-trail the session holds
   * only what is captured so far in its event streams (`network`, `analytics`, `events`), so a stream
   * a mid-trail survey reads must derive from those alone.
   */
  derive(session: SurveySession): ReadonlyArray<DerivedRowInput<T>>;
  /**
   * The derived streams `derive` reads, as `session.stream("<name>")` or `<stream>.of(session)`.
   * They are derived first, and `derive` sees them fresh beside what the session captured, never
   * a derived row a session's files hold: a chain of streams gives the same rows wherever it runs.
   */
  dependsOn?: ReadonlyArray<EventStreamDefinition<any>>;
  /**
   * Which part of the run `derive` reads, as a survey's `scope` does: `SurveyScope.Trail`,
   * `DeviceSetup`, `Trailhead`, `Teardown`, a list of `RunPhase`s, or `All`. Unset, it reads `All`:
   * a stream is shared by surveys of every scope, so it keeps setup unless it says otherwise. Every
   * reader, and the report's lane, gets the same rows; a survey's own scope narrows them further.
   */
  scope?: SurveyScope;
}

export interface EventStreamDefinition<T = unknown> {
  readonly [BRAND]: true;
  readonly spec: EventStreamSpec<T>;
  readonly name: string;
  /** Set by discovery: a hash of the defining file and its local imports, so editing either rewrites stored streams. */
  readonly version?: string;
  /** This stream's rows for [session], derived once per session and reused. */
  of(session: SurveySession): DerivedRow<T>[];
}

/** Row lists by session, per spec: a stream read by several surveys of one session derives once. */
const memo = new WeakMap<EventStreamSpec<any>, WeakMap<object, DerivedRow<any>[]>>();

/** A clock in ms. QuickJS, which runs surveys mid-trail, has no `performance`. */
export const nowMs = (): number => (typeof performance !== "undefined" && typeof performance.now === "function" ? performance.now() : Date.now());

/** How long each spec's own `derive` ran per session, not counting the streams it read: see [deriveMsOf]. */
const deriveTimes = new WeakMap<EventStreamSpec<any>, WeakMap<object, number>>();

/**
 * How long [def]'s own `derive` took over [session] when it last ran, in ms, not counting the derived
 * streams it read: those are timed as their own. Undefined when it has not run over [session].
 */
export function deriveMsOf(def: EventStreamDefinition, session: SurveySession): number | undefined {
  return deriveTimes.get(def.spec)?.get(baseOf(unscopedOf(session)));
}

function list<T>(v: T | ReadonlyArray<T> | undefined): ReadonlyArray<T> {
  if (v === undefined) return [];
  return Array.isArray(v) ? (v as ReadonlyArray<T>) : [v as T];
}

/** The stream a captured `.ndjson` record's file holds (`events/analytics.tablet.ndjson` → `analytics.tablet`), as the report names it. */
function streamOfFile(file: string): string | undefined {
  const base = file.slice(file.lastIndexOf("/") + 1);
  // A trace span names no device, so its row converts as a stream with no device suffix: on the
  // device the session started on, as the trace does, whatever the derived stream is named.
  if (base === TRACE_CITATION) return "trace";
  if (!base.endsWith(".ndjson")) return undefined;
  return base.slice(0, -".ndjson".length).replace(/\.json$/, "") || undefined;
}

/** The file a trace span cites. */
const TRACE_CITATION = "trace.json";

function deriveRows<T>(spec: EventStreamSpec<T>, session: SurveySession): DerivedRow<T>[] {
  const input = spec.derive(session);
  if (!Array.isArray(input)) throw new Error(`stream "${spec.name}": derive must return an array of rows`);
  const file = `events/${spec.name}.ndjson`;
  return input
    .map((row, index) => {
      if (!row || typeof row.timeMs !== "number" || !Number.isFinite(row.timeMs)) {
        throw new Error(`stream "${spec.name}": row ${index} has no numeric timeMs`);
      }
      if (row.clock !== undefined && row.clock !== "device" && row.clock !== "host") {
        throw new Error(`stream "${spec.name}": row ${index} has clock ${JSON.stringify(row.clock)}; use "device" or "host"`);
      }
      return { row, index };
    })
    .sort((a, b) => a.row.timeMs - b.row.timeMs || a.index - b.index)
    .map(({ row }, i) => {
      const from = list(row.from);
      const isDevice = (r: SessionRecord) => (r as { clock?: string }).clock === "device";
      const deviceClock = row.clock === undefined ? from.length > 0 && from.every(isDevice) : row.clock === "device";
      const owner = deviceClock ? from.find(isDevice) : undefined;
      // A row derived from a derived row converts on the clock that row named, not on its stream's.
      const clockStream = owner && (owner.derived ? ((owner as DerivedRow).clockStream ?? (owner as DerivedRow).stream) : streamOfFile(owner.file));
      return {
        kind: "event" as const,
        stream: spec.name,
        derived: true as const,
        data: row.data,
        file,
        line: i + 1,
        // Whole milliseconds: the daemon's report reads a fractional time as none.
        timeMs: Math.round(row.timeMs),
        ...(deviceClock ? { clock: "device" as const } : {}),
        ...(clockStream && clockStream !== spec.name ? { clockStream } : {}),
        summary: `${spec.name} event`,
        from,
      };
    });
}

/** The session a view was built over, so a stream's rows are derived once per session, whatever view reads them. */
const BASE: unique symbol = Symbol.for("trailblaze.stream.base");
const baseOf = (session: SurveySession): SurveySession => (session as { [BASE]?: SurveySession })[BASE] ?? session;

/**
 * [session] with [fresh]'s rows as those streams' events. Every other derived row goes too unless
 * [keepOther]: a stream derives from what was captured and its own `dependsOn`, nothing a file holds.
 */
function viewWith(session: SurveySession, fresh: ReadonlyMap<string, ReadonlyArray<StreamEvent>>, keepOther: boolean): SurveySession {
  const base = baseOf(session);
  const kept = base.events.all().filter((e) => !e.derived || (keepOther && !fresh.has(e.stream)));
  const added = [...fresh.values()].flat();
  const events = added.length ? [...kept, ...added].sort((a, b) => (a.timeMs ?? 0) - (b.timeMs ?? 0)) : kept;
  const view = Object.create(base) as SurveySession;
  Object.defineProperty(view, "events", { value: new Records(events, matchEvent), enumerable: true });
  // Its own `stream`, since a session may read its events through a closure rather than `this`.
  Object.defineProperty(view, "stream", { value: (name: string) => view.events.filter({ stream: name }), enumerable: true });
  Object.defineProperty(view, BASE, { value: base });
  return view;
}

/** Streams deriving right now, so a `dependsOn` loop fails with its path instead of overflowing the stack. */
const deriving: EventStreamSpec<any>[] = [];

function define<T>(spec: EventStreamSpec<T>, version?: string): EventStreamDefinition<T> {
  const derivedOnce = (base: SurveySession): DerivedRow<T>[] => {
    let bySession = memo.get(spec);
    if (!bySession) memo.set(spec, (bySession = new WeakMap()));
    let rows = bySession.get(base);
    if (rows) return rows;
    if (deriving.includes(spec)) {
      const loop = [...deriving.slice(deriving.indexOf(spec)), spec].map((s) => s.name).join(" → ");
      throw new Error(`stream "${spec.name}" depends on itself: ${loop}`);
    }
    deriving.push(spec);
    try {
      const inputs = new Map((spec.dependsOn ?? []).map((d) => [d.name, d.of(base)] as const));
      const started = nowMs();
      try {
        const view = viewWith(base, inputs, false);
        rows = deriveRows(spec, spec.scope === undefined ? view : scopedSession(view, spec.scope));
      } finally {
        let times = deriveTimes.get(spec);
        if (!times) deriveTimes.set(spec, (times = new WeakMap()));
        times.set(base, nowMs() - started);
      }
    } finally {
      deriving.pop();
    }
    bySession.set(base, rows);
    return rows;
  };
  return Object.freeze({
    [BRAND]: true as const,
    spec,
    name: spec.name,
    version,
    of(session: SurveySession): DerivedRow<T>[] {
      // Rows are derived once from the whole run, never from a scoped view's narrowed inputs; the
      // view reads only its part of them.
      const rows = derivedOnce(baseOf(unscopedOf(session)));
      const keep = scopeFilterOf(session);
      return keep ? rows.filter(keep) : rows;
    },
  });
}

/**
 * [session] with every stream in [streams] re-derived from its definition, in place of what its
 * files hold; a derived stream with no definition here keeps its written rows. A stream that throws
 * has no rows, and its error is returned, so one broken stream never hides the session.
 */
export function withDerivedStreams(session: SurveySession, streams: ReadonlyArray<EventStreamDefinition>): { session: SurveySession; errors: Array<{ stream: string; message: string }> } {
  const fresh = new Map<string, ReadonlyArray<StreamEvent>>();
  const errors: Array<{ stream: string; message: string }> = [];
  for (const def of streams) {
    try {
      fresh.set(def.name, def.of(session));
    } catch (e) {
      fresh.set(def.name, []);
      errors.push({ stream: def.name, message: e instanceof Error ? e.message : String(e) });
    }
  }
  return { session: streams.length ? viewWith(session, fresh, true) : session, errors };
}

/**
 * [session] with [streams] derived only when read, as [withDerivedStreams] would add them:
 * `stream(name)` derives that one stream, and `events` derives them all. A stream that throws has
 * no rows and goes to [onError], once, so a stream a survey never reads can never fail it.
 */
export function withLazyDerivedStreams(
  session: SurveySession,
  streams: ReadonlyArray<EventStreamDefinition>,
  onError: (stream: string, message: string) => void,
): SurveySession {
  if (streams.length === 0) return session;
  const byName = new Map(streams.map((def) => [def.name, def] as const));
  const rows = new Map<string, ReadonlyArray<StreamEvent>>();
  const derive = (def: EventStreamDefinition): ReadonlyArray<StreamEvent> => {
    let out = rows.get(def.name);
    if (out) return out;
    try {
      out = def.of(session);
    } catch (e) {
      out = [];
      onError(def.name, e instanceof Error ? e.message : String(e));
    }
    rows.set(def.name, out);
    return out;
  };
  let whole: SurveySession | undefined;
  const all = () => (whole ??= viewWith(session, new Map(streams.map((def) => [def.name, derive(def)] as const)), true));
  const base = baseOf(session);
  const view = Object.create(base) as SurveySession;
  Object.defineProperty(view, "events", { get: () => all().events, enumerable: true });
  Object.defineProperty(view, "stream", {
    value: (name: string) => {
      const def = byName.get(name);
      if (!def) return session.events.filter({ stream: name });
      return [...derive(def)].sort((a, b) => (a.timeMs ?? 0) - (b.timeMs ?? 0));
    },
    enumerable: true,
  });
  Object.defineProperty(view, BASE, { value: base });
  return view;
}

/** Declares a derived event stream. Export it from a `*.survey.ts` file so `trailblaze report --surveys` writes it. */
export function eventStream<T = Record<string, unknown>>(spec: EventStreamSpec<T>): EventStreamDefinition<T> {
  if (!spec || typeof spec !== "object") throw new Error("eventStream(): the argument must be a spec object");
  if (typeof spec.name !== "string" || !STREAM_NAME.test(spec.name)) {
    throw new Error(`eventStream(): name must be letters, digits, '.', '_' or '-', not ending in '.json' (got ${JSON.stringify(spec.name)})`);
  }
  if (CAPTURED_STREAM.test(spec.name)) throw new Error(`eventStream(): ${JSON.stringify(spec.name)} is a stream Trailblaze captures; pick another name`);
  if (CAPTURED_LANE.test(spec.name)) {
    throw new Error(`eventStream(): the report shows ${JSON.stringify(spec.name)} on the lane of a stream Trailblaze captures, where its rows would replace the captured ones; pick another name`);
  }
  if (typeof spec.derive !== "function") throw new Error(`stream "${spec.name}": derive must be a function`);
  if (spec.lane !== undefined && (typeof spec.lane !== "object" || spec.lane === null || (spec.lane.fold !== undefined && !["any", "label", "none"].includes(spec.lane.fold)))) {
    throw new Error(`stream "${spec.name}": lane.fold must be "any", "label" or "none"`);
  }
  if (spec.dependsOn !== undefined && (!Array.isArray(spec.dependsOn) || !spec.dependsOn.every(isEventStream))) {
    throw new Error(`stream "${spec.name}": dependsOn must list streams made with eventStream()`);
  }
  const badScope = spec.scope === undefined ? undefined : scopeError(spec.scope);
  if (badScope) throw new Error(`stream "${spec.name}": ${badScope}`);
  return define(spec);
}

/** True when `value` came out of `eventStream()`. */
export function isEventStream(value: unknown): value is EventStreamDefinition {
  return typeof value === "object" && value !== null && (value as { [BRAND]?: unknown })[BRAND] === true;
}

/** A copy of `def` carrying `version`. It shares `def`'s spec, so both read the same memoized rows. */
export function withEventStreamVersion<T>(def: EventStreamDefinition<T>, version: string | undefined): EventStreamDefinition<T> {
  return def.version === version ? def : define(def.spec, version);
}

/**
 * The `events/<name>.ndjson` line a written stream holds for [row]: the `{timeMs, data}` envelope
 * every reader takes, plus its lineage and, when [view] is given, the report's `row` for it. A row
 * whose time the device's clock stamped is marked `clock: "device"`, with `clockStream` naming whose
 * device it was when that is not this stream's own, so the report moves it onto the host timeline.
 */
export function envelopeOf<T>(row: DerivedRow<T>, view?: (data: T) => RowView): { timeMs: number; clock?: "device"; clockStream?: string; data: unknown; from?: RowSource[]; row?: RowView } {
  const from = row.from.map((r) => (r.line !== undefined ? { file: r.file, line: r.line } : { file: r.file }));
  const clock = row.clock === "device" ? { clock: "device" as const, ...(row.clockStream ? { clockStream: row.clockStream } : {}) } : {};
  return { timeMs: row.timeMs, ...clock, data: row.data, ...(from.length ? { from } : {}), ...(view ? { row: view(row.data) } : {}) };
}

/** What the report calls a record with no stream of its own: a Trailblaze log's kind. */
const SOURCE_OF_KIND: Partial<Record<SessionRecord["kind"], string>> = {
  log: "logs",
  tool: "tools",
  objective: "objectives",
  "screen-text": "screen text",
  "device-log": "device log",
  trace: "trace",
};

/** The stream or log a record came from, as a reader of the report knows it. */
function sourceOf(record: SessionRecord): string | undefined {
  const stream = (record as { stream?: unknown }).stream;
  if (typeof stream === "string" && stream) return stream;
  return streamOfFile(record.file) ?? SOURCE_OF_KIND[record.kind];
}

/**
 * What [def]'s [rows] were derived from, for the report to say beside its lane: the streams in its
 * `dependsOn`, and every stream or log its rows cite in `from`. Sorted, so the same rows give the same list.
 */
export function sourcesOf(def: EventStreamDefinition<any>, rows: ReadonlyArray<DerivedRow<any>>): string[] {
  const out = new Set<string>((def.spec.dependsOn ?? []).map((d) => d.name));
  for (const row of rows) for (const r of row.from) {
    const source = sourceOf(r);
    if (source && source !== def.name) out.add(source);
  }
  return [...out].sort();
}
