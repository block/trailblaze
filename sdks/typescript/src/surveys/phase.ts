// Which part of a run a record belongs to: the device setup, the trailhead that set the run up, the
// trail's own steps, or the trailhead's teardown after them. A survey reads one part by its
// `scope`, `SurveyScope.Trail` unless it says otherwise, so what a run's setup did — signing in, the first catalog sync — is
// never reported as what the trail tested.
//
// No filesystem access, like `run-one.ts`: a browser page scopes the sessions it runs surveys over.

import { deviceOffsetsOf, sessionWideOffsetOf, type DeviceOffsets } from "./log-clock.js";
import { LogRecords, Records, matchAnalytics, matchDeviceLog, matchEvent, matchNetwork, matchObjective, matchScreenText, matchTool, matchTrace } from "./match.js";
import type { SurveySession } from "./session.js";
import type { LogRecord, SessionRecord } from "./types.js";

/**
 * The parts of a run, in order, named as the runner names its history sections
 * (`ctx.history.toolCalls({ section })`). `phaseOf` says which one a record belongs to.
 */
export const RunPhase = {
  /** The run's device setup (`trailblaze run --device-setup`), before any trailhead. Absent when none was sent. */
  DeviceSetup: "deviceSetup",
  /** Trailhead steps setting the run up: before the trail, or a later device's mid-trail. */
  Trailhead: "trailhead",
  /** The trail's own steps. */
  Trail: "trail",
  /** After the trail ended, while the trailhead's teardown ran. */
  Teardown: "teardown",
} as const;
export type RunPhase = (typeof RunPhase)[keyof typeof RunPhase];

/**
 * What a survey's find phase reads: one part of the run (`Trail`, the default, or `DeviceSetup`,
 * `Trailhead`, `Teardown`), a list of parts, or `All` of it, for a survey that measures setup,
 * diagnoses any failure, or weighs one part against another with `phaseOf`.
 */
export const SurveyScope = { ...RunPhase, All: "all" } as const;
export type SurveyScope = (typeof SurveyScope)[keyof typeof SurveyScope] | ReadonlyArray<RunPhase>;

const RUN_PHASES: ReadonlyArray<string> = Object.values(RunPhase);

/** Why [scope] is not a scope, or undefined when it is one. */
export function scopeError(scope: unknown): string | undefined {
  const names = Object.values(SurveyScope).map((s) => `"${s}"`).join(", ");
  if (Array.isArray(scope)) {
    if (scope.length === 0) return `scope must list at least one of ${RUN_PHASES.map((s) => `"${s}"`).join(", ")}`;
    const bad = scope.find((s) => !RUN_PHASES.includes(s));
    return bad === undefined ? undefined : `a scope list holds only ${RUN_PHASES.map((s) => `"${s}"`).join(", ")}, not ${JSON.stringify(bad)}`;
  }
  return typeof scope === "string" && (scope === SurveyScope.All || RUN_PHASES.includes(scope)) ? undefined : `scope must be ${names}, or a list of phases, not ${JSON.stringify(scope)}`;
}

/** The phases [scope] reads, or undefined for all of them. */
function phasesOf(scope: SurveyScope): ReadonlySet<RunPhase> | undefined {
  if (scope === SurveyScope.All) return undefined;
  return new Set<RunPhase>(typeof scope === "string" ? [scope] : scope);
}

/**
 * What a survey reads when it declares no `scope`: the trail, unless it is a warning survey. A crash
 * or failed request during setup can be what went wrong later, so a warning reads the whole run.
 */
export function scopeOf(spec: { scope?: SurveyScope; warning?: boolean }): SurveyScope {
  return spec.scope ?? (spec.warning ? SurveyScope.All : SurveyScope.Trail);
}

/** How a survey's scope reads in a report: `trail`, `all`, `deviceSetup+trailhead`. */
export function describeScope(scope: SurveyScope): string {
  return typeof scope === "string" ? scope : scope.join("+");
}

/** Outcomes of a run that finished without passing. `UNKNOWN` (never ended, or still running) is not one. */
const ENDED_UNPASSED = new Set(["FAILED", "CANCELLED", "TIMEOUT"]);

/** The session a scoped view was built over, so the boundary is always read from the whole run. */
const UNSCOPED: unique symbol = Symbol.for("trailblaze.survey.unscoped");
export const unscopedOf = (session: SurveySession): SurveySession => (session as { [UNSCOPED]?: SurveySession })[UNSCOPED] ?? session;

/** What a scoped view keeps, so a derived stream's `of(view)` narrows its rows the way `view.stream()` does. */
const SCOPE_FILTER: unique symbol = Symbol.for("trailblaze.survey.scopeFilter");

/** The record filter a scoped view applies, or undefined for a whole session. */
export function scopeFilterOf(session: SurveySession): ((record: SessionRecord) => boolean) | undefined {
  return (session as { [SCOPE_FILTER]?: (record: SessionRecord) => boolean })[SCOPE_FILTER];
}

type Clock = "host" | "device";

/** A moment, on the clock that stamped it. */
interface Mark {
  ms: number;
  clock: Clock;
  /** On a device's clock, that device's offset onto the host's; undefined when nothing measured it. */
  offsetMs?: number;
}

interface Split {
  /** When the device setup finished; `Infinity` when it began and never did. Absent without one. */
  setupEnd?: Mark;
  /** When the trail's own steps began; `Infinity` when they never did. */
  start?: Mark;
  /** A trailhead began: what a run that never reached its trail spent its time on, if not device setup. */
  trailheadBegan: boolean;
  /** When the trail ended and teardown began, from the trailhead stream's `trailEnd` row. */
  end?: Mark;
  /** Later trailhead steps' windows, from start to completion, on the logs' clock. */
  reentries: Array<{ from: Mark; to: number }>;
  /** The logs' session-wide device→host offset; undefined when nothing anchored it. */
  offsetMs?: number;
  /** Each device's offset, for a device-stamped record of a multi-device session. */
  devices?: DeviceOffsets;
}

/** A finished session's split, read once. A live one's is read every time, since its markers still arrive. */
const splits = new WeakMap<SurveySession, Split>();

function splitOf(session: SurveySession): Split {
  const whole = unscopedOf(session);
  if (!whole.final) return findSplit(whole);
  let split = splits.get(whole);
  if (!split) splits.set(whole, (split = findSplit(whole)));
  return split;
}

/**
 * When the trail's own steps began, in epoch milliseconds, from the first of:
 * 1. the trailhead stream's `trailStart` row;
 * 2. the trailhead objective's successful completion log, which lands 1–25ms before `trailStart`
 *    where both exist. Only in a finished session, where the logs are readable;
 * 3. the first request Trailblaze's network capture tagged `trailPhase: trail`. Seconds off either
 *    way, so it is last.
 *
 * A run with no trailhead that ran device setup starts its trail when that setup completed.
 *
 * With none, a finished run whose trailhead or device setup began and that ended without passing
 * gets `Infinity`: it failed setting up, so everything it saw was setup and the trail covered
 * nothing. Any other run with none — a trail with no trailhead, one recorded before these markers,
 * or one still running — stays `undefined`: it can't be split, and every scope reads all of it.
 *
 * The time is on the clock that stamped the marker; `phaseOf` puts each record on the same clock.
 */
export function trailStartOf(session: SurveySession): number | undefined {
  return splitOf(session).start?.ms;
}

function findSplit(session: SurveySession): Split {
  const trailheadRows = session.stream("trailhead");
  const row = (type: string) => trailheadRows.find((e) => (e.data as { type?: string } | undefined)?.type === type);
  const logs = session.final ? [...session.trailblazeLogs] : [];
  const raw = logs.map((l) => l.log);
  const offsetMs = logs.length ? (sessionWideOffsetOf(raw) ?? undefined) : undefined;
  const devices = logs.length ? (deviceOffsetsOf(raw) ?? undefined) : undefined;
  // The trailhead stream is the start device's; a tagged request, the device its stream names.
  const markOf = (r: SessionRecord | undefined): Mark | undefined => (r ? recordMark(r, { devices, offsetMs }) : undefined);
  const logMark = (r: LogRecord): Mark | undefined => (r.timeMs === undefined ? undefined : { ms: r.timeMs, clock: logClock(r, offsetMs), offsetMs });
  const trailheadSteps = logs.filter((r) => (r.type === "ObjectiveStartLog" || r.type === "ObjectiveCompleteLog") && isTrailheadLog(r));

  const deviceSetup = logs.filter((r) => (r.type === "ObjectiveStartLog" || r.type === "ObjectiveCompleteLog") && isSetupLog(r));
  const setupDone = deviceSetup.find((r) => r.type === "ObjectiveCompleteLog");
  // An on-device run writes no trailhead stream, so its teardown is known only by its own objective.
  const teardownBegan = logs.find((r) => r.type === "ObjectiveStartLog" && isTeardownLog(r));
  const setupEnd = setupDone ? logMark(setupDone) : deviceSetup.length > 0 ? { ms: Number.POSITIVE_INFINITY, clock: "host" as const } : undefined;
  const trailheadBegan = trailheadSteps.length > 0 || trailheadRows.length > 0 || session.network.find((r) => r.trailPhase === "trailhead") !== undefined;

  let start = markOf(row("trailStart"));
  // A failed trailhead completes too, and never reached its trail.
  const done = trailheadSteps.find((r) => r.type === "ObjectiveCompleteLog" && succeeded(r));
  if (done) start ??= logMark(done);
  start ??= markOf(session.network.find((r) => r.trailPhase === "trail"));
  // No trailhead: the trail began when the device setup did its job.
  if (!trailheadBegan && setupDone && succeeded(setupDone)) start ??= setupEnd;
  // `summary.outcome` exists only once the session has finished.
  if (!start && session.final && (trailheadBegan || deviceSetup.length > 0) && ENDED_UNPASSED.has(session.summary.outcome)) {
    start = { ms: Number.POSITIVE_INFINITY, clock: "host" };
  }

  // A trailhead step that starts after the trail did is a later device's setup, as captures tag it.
  const reentries: Split["reentries"] = [];
  if (start && Number.isFinite(start.ms)) {
    let open: Mark | undefined;
    for (const r of trailheadSteps) {
      const at = logMark(r);
      if (!at) continue;
      if (r.type === "ObjectiveStartLog") {
        const onStart = toClock(at, start);
        if (onStart !== undefined && onStart >= start.ms) open = at;
      } else if (open) {
        reentries.push({ from: open, to: at.ms });
        open = undefined;
      }
    }
    if (open) reentries.push({ from: open, to: Number.POSITIVE_INFINITY });
  }

  const end = markOf(row("trailEnd")) ?? (teardownBegan ? logMark(teardownBegan) : undefined);
  return { setupEnd: start ? setupEnd : undefined, start, trailheadBegan, end: start ? end : undefined, reentries, offsetMs, devices };
}

/** A log's clock after the session's offset moved it: the host's unless it was device-stamped and nothing anchored it. */
const logClock = (r: LogRecord, offsetMs: number | undefined): Clock =>
  (r.log as { clock?: unknown }).clock === "device" && offsetMs === undefined ? "device" : "host";

const isTrailheadLog = (r: LogRecord) => ((r.log as { promptStep?: { isTrailhead?: unknown } }).promptStep?.isTrailhead === true);

/** The runner marks the trailhead's teardown objective `isTeardown`. */
const isTeardownLog = (r: LogRecord) => ((r.log as { promptStep?: { isTeardown?: unknown } }).promptStep?.isTeardown === true);

/** The runner marks the device setup's own objective `isSetup`, apart from the trail's steps. */
const isSetupLog = (r: LogRecord) => ((r.log as { promptStep?: { isSetup?: unknown } }).promptStep?.isSetup === true);

const succeeded = (r: LogRecord) => ((r.log as { objectiveResult?: { class?: string } }).objectiveResult?.class ?? "").includes(".Success.");

/**
 * [mark]'s time on the clock [on] was stamped by, or undefined when the session measured no offset
 * between the two. Two device times compare as they are only when they are one device's (the same
 * offset, or neither measured, as on a single unanchored device); otherwise both go through the host's.
 */
function toClock(mark: Mark, on: Mark): number | undefined {
  if (mark.clock === on.clock && (mark.clock === "host" || mark.offsetMs === on.offsetMs)) return mark.ms;
  const host = mark.clock === "host" ? mark.ms : mark.offsetMs === undefined ? undefined : mark.ms + mark.offsetMs;
  if (host === undefined || on.clock === "host") return host;
  return on.offsetMs === undefined ? undefined : host - on.offsetMs;
}

/** The stream a record came from, whose `.<device>` suffix names the device that stamped it. */
function streamOfRecord(record: SessionRecord): string | undefined {
  const own = (record as { clockStream?: unknown; stream?: unknown });
  if (typeof own.clockStream === "string") return own.clockStream;
  if (typeof own.stream === "string" && !(record as { derived?: unknown }).derived) return own.stream;
  if (record.kind === "trace") return "trace";
  const base = (record.file ?? "").slice((record.file ?? "").lastIndexOf("/") + 1);
  return base.endsWith(".ndjson") ? base.slice(0, -".ndjson".length) : undefined;
}

/**
 * The offset for a device-stamped [record]: its own device's, as the report converts it. A stream
 * named `<stream>.<device>` is that device's, and one with no device suffix the start device's. In a
 * session whose devices run on different offsets, a record whose stream can't be told is undefined.
 */
function recordOffset(record: SessionRecord, split: Pick<Split, "devices" | "offsetMs">): number | undefined {
  const { devices, offsetMs } = split;
  if (!devices || devices.single || record.kind === "log") return offsetMs;
  const stream = streamOfRecord(record);
  if (stream === undefined) return undefined;
  let owner: string | undefined;
  for (const name of devices.byName.keys()) {
    if (stream.length > name.length && stream.endsWith(`.${name}`) && (!owner || name.length > owner.length)) owner = name;
  }
  return owner !== undefined ? devices.byName.get(owner) : devices.startMs;
}

/** When [record] began, on the clock that stamped it, with that device's offset. A log is on the host's once the session's offset moved it. */
function recordMark(record: SessionRecord, split: Pick<Split, "devices" | "offsetMs">): Mark | undefined {
  const began = (record as { startMs?: number }).startMs ?? record.timeMs;
  if (began === undefined || !Number.isFinite(began)) return undefined;
  const clock = record.kind === "log" ? logClock(record as LogRecord, split.offsetMs) : (record as { clock?: unknown }).clock === "device" ? "device" : "host";
  return clock === "host" ? { ms: began, clock } : { ms: began, clock, offsetMs: recordOffset(record, split) };
}

/**
 * The part of the run `record` belongs to, or undefined when that can't be told: the run has no
 * boundary (see `trailStartOf`), the record has no time, or its clock differs from the boundary's
 * and the session measured no offset between them. A record is placed by when it began, so a
 * request sent during the trailhead is the trailhead's however late its response came. A network
 * request Trailblaze's capture tagged keeps its tag, and a trailhead step's own logs are the
 * trailhead's.
 */
export function phaseOf(session: SurveySession, record: SessionRecord): RunPhase | undefined {
  if (record.kind === "network") {
    const tagged = (record as { trailPhase?: unknown }).trailPhase;
    if (tagged === RunPhase.Trail || tagged === RunPhase.Trailhead || tagged === RunPhase.Teardown) return tagged;
  }
  if (record.kind === "log" && isSetupLog(record as LogRecord)) return RunPhase.DeviceSetup;
  if (record.kind === "log" && isTrailheadLog(record as LogRecord)) return RunPhase.Trailhead;
  if (record.kind === "log" && isTeardownLog(record as LogRecord)) return RunPhase.Teardown;
  const split = splitOf(session);
  const { setupEnd, start, trailheadBegan, end, reentries } = split;
  if (!start) return undefined;
  // A device setup that never finished holds the whole run, on whatever clock.
  if (setupEnd?.ms === Number.POSITIVE_INFINITY) return RunPhase.DeviceSetup;
  const at = recordMark(record, split);
  // A run that never reached its trail spent it all setting up, timed or not.
  if (!at) return start.ms === Number.POSITIVE_INFINITY && !setupEnd ? RunPhase.Trailhead : undefined;
  if (setupEnd) {
    const onSetup = toClock(at, setupEnd);
    if (onSetup === undefined) return undefined;
    if (onSetup < setupEnd.ms) return RunPhase.DeviceSetup;
  }
  // Teardown runs, and writes `trailEnd`, even after a setup that failed.
  if (end) {
    const onEnd = toClock(at, end);
    if (onEnd === undefined) return undefined;
    if (onEnd >= end.ms) return RunPhase.Teardown;
  }
  if (start.ms === Number.POSITIVE_INFINITY) return trailheadBegan ? RunPhase.Trailhead : RunPhase.DeviceSetup;
  const onStart = toClock(at, start);
  if (onStart === undefined) return undefined;
  if (onStart < start.ms) return RunPhase.Trailhead;
  for (const w of reentries) {
    const t = toClock(at, w.from);
    if (t === undefined) return undefined;
    if (t >= w.from.ms && t < w.to) return RunPhase.Trailhead;
  }
  return RunPhase.Trail;
}

/**
 * `session` as a survey of `scope` reads it: every record list less the records of the other parts,
 * read lazily, so a list the survey never reads is never filtered. A record whose part can't be
 * told stays in every scope, and a run with no boundary is returned whole. A scoped view rescoped
 * reads from the whole run again, never from the part it already narrowed to. A derived stream's
 * `of(view)` is narrowed the same way. Files (`readText`, `readJson`) and the summary are the whole
 * run's: a survey that reads a file splits it itself, with `phaseOf` or `trailStartOf`.
 */
export function scopedSession(session: SurveySession, scope: SurveyScope = SurveyScope.Trail): SurveySession {
  const whole = unscopedOf(session);
  const phases = phasesOf(scope);
  if (!phases || trailStartOf(whole) === undefined) return whole;
  const keep = (r: SessionRecord) => {
    const phase = phaseOf(whole, r);
    return phase === undefined || phases.has(phase);
  };
  const view = Object.create(whole) as SurveySession;
  const lazy = <V>(name: string, make: () => V) => {
    let value: V | undefined;
    Object.defineProperty(view, name, { get: () => (value ??= make()), enumerable: true, configurable: true });
  };
  lazy("trailblazeLogs", () => new LogRecords(whole.trailblazeLogs.filter(keep)));
  lazy("logs", () => view.trailblazeLogs);
  lazy("tools", () => new Records(whole.tools.filter(keep), matchTool));
  lazy("objectives", () => new Records(whole.objectives.filter(keep), matchObjective));
  lazy("network", () => new Records(whole.network.filter(keep), matchNetwork));
  lazy("analytics", () => new Records(whole.analytics.filter(keep), matchAnalytics));
  lazy("events", () => new Records(whole.events.filter(keep), matchEvent));
  lazy("screenText", () => new Records(whole.screenText.filter(keep), matchScreenText));
  lazy("deviceLog", () => new Records(whole.deviceLog.filter(keep), matchDeviceLog));
  lazy("trace", () => new Records(whole.trace.filter(keep), matchTrace));
  // Through the session's own `stream`, which derives only the stream asked for.
  Object.defineProperty(view, "stream", { value: (name: string) => whole.stream(name).filter(keep), enumerable: true });
  Object.defineProperty(view, UNSCOPED, { value: whole });
  Object.defineProperty(view, SCOPE_FILTER, { value: keep });
  return view;
}
