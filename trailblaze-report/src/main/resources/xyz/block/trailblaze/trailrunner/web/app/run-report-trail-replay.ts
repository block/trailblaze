// The Replay projection's clock, as pure functions of the trail matrix.
//
// Replay asks one question the other trail projections can't: at instant t, what was on every
// device's screen at once? So the matrix — which is organized by authored step — is turned inside
// out into per-lane timelines on a single shared wall clock, and the view then only ever asks
// "where is lane L at time t". Keeping that here (and out of the DOM wiring) is what makes the
// synchronization testable: the view paints, these functions decide.
//
// The clock is true wall time, from 0 to the longest lane's end. Nothing is compressed: the point
// of watching five devices run the same trail is seeing which one falls behind, and a compressed
// axis would flatten exactly the differences the view exists to show. Idle stretches are skipped
// with the speed control and the boundary-jump keys instead.

import type { TrailAction, TrailCell, TrailFrame, TrailMatrix } from './run-report-trail-model';

export type ReplayOutcome = 'passed' | 'failed' | 'selfheal';

/** One capture on the shared clock — a moment this lane's screen changed. */
export interface ReplayCapture {
  atMs: number;
  file: string;
  label: string;
  /** Deep-link target: the capturing row in that run's own timeline. */
  rowId: number;
  kid: number | null;
  /** The authored step that was running when it was captured. */
  stepNum: number;
  stepLabel: string;
}

/** One lane's execution of one authored step, placed on the shared clock. */
export interface ReplayStep {
  num: number;
  label: string;
  startMs: number;
  endMs: number;
  outcome: ReplayOutcome;
  /** The step group's header row — where "open this step on this device" lands. */
  headerId: number;
}

/**
 * One interaction on the shared clock — the tap, swipe or assertion the device performed at that
 * instant. Replay draws these over whatever the pane is showing, so the reader sees the action land
 * rather than inferring it from a screen that changed.
 */
export interface ReplayEvent {
  atMs: number;
  mark: ActionMark;
  label: string;
  stepNum: number;
}

/** The instant a lane's run failure landed, on the shared clock — the moment worth jumping to. */
export interface ReplayLaneFailure {
  atMs: number;
  /** The authored step the failure landed in. */
  stepNum: number;
  label: string;
}

export interface ReplayLane {
  index: number;
  steps: ReplayStep[];
  captures: ReplayCapture[];
  /** Every positioned interaction this lane performed, in clock order. */
  events: ReplayEvent[];
  /** When this lane stops doing anything: its last step's end (or last capture, if that is later). */
  endMs: number;
  /** Where this lane's run failure landed, or null for a lane whose run passed. */
  failure: ReplayLaneFailure | null;
}

export interface ReplayTimeline {
  lanes: ReplayLane[];
  /** The shared axis length — the longest lane's end. Always at least 1, so t/total is safe. */
  totalMs: number;
  /**
   * Every instant any lane starts an authored step, plus 0 and the end. These are the stops the
   * transport lands on with no lane selected: the moments the trail as a whole moves on.
   */
  boundaries: number[];
  /**
   * The first instant ANY device has something on screen — where the view opens. A trail's first
   * step is app launch, which can run a minute before it captures anything, so opening at 0 would
   * land on a row of empty panes and read as broken. This is still a real instant on the clock.
   */
  firstCaptureMs: number;
}

const outcomeOf = (cell: TrailCell): ReplayOutcome => !cell.ok ? 'failed' : cell.selfHeal ? 'selfheal' : 'passed';

const sortedUnique = (values: number[]): number[] =>
  Array.from(new Set(values)).sort((a, b) => a - b);

/**
 * Turn the step-major matrix into lane-major timelines on one shared clock.
 *
 * A step or capture without timestamps is dropped rather than guessed at: this view's whole claim
 * is that the columns are synchronized, and a frame placed at an invented instant would show two
 * devices side by side at a moment neither of them was in. A lane whose steps are all untimed
 * simply has an empty timeline, and `replayable()` reports the trail as un-replayable.
 */
export function buildReplayTimeline(matrix: TrailMatrix): ReplayTimeline {
  const laneCount = matrix.rows.reduce((max, row) => Math.max(max, row.cells.length), 0);
  const lanes: ReplayLane[] = [];
  for (let index = 0; index < laneCount; index++) {
    const steps: ReplayStep[] = [];
    const captures: ReplayCapture[] = [];
    const events: ReplayEvent[] = [];
    let failure: ReplayLaneFailure | null = null;
    for (const row of matrix.rows) {
      const cell = row.cells[index];
      if (!cell) continue;
      // Picked up ahead of the timing gate below so a cell that can place its failure but not its
      // span still marks the lane. A lane fails once, so first pickup wins.
      if (failure == null && cell.failureAtMs != null) {
        failure = { atMs: cell.failureAtMs, stepNum: row.num, label: cell.label || row.label };
      }
      if (cell.startMs == null || cell.durationMs == null) continue;
      steps.push({
        num: row.num,
        // A positional row names no shared step, so the lane's own wording is all there is to show.
        // Under a step join row.label is the authored spine and wins, as it always did.
        label: row.label || cell.label || '',
        startMs: cell.startMs,
        endMs: cell.startMs + cell.durationMs,
        outcome: outcomeOf(cell),
        headerId: cell.headerId,
      });
      for (const frame of cell.frames as TrailFrame[]) {
        if (frame.atMs == null) continue;
        captures.push({
          atMs: frame.atMs,
          file: frame.file,
          label: frame.label,
          rowId: frame.rowId,
          kid: frame.kid,
          stepNum: row.num,
          stepLabel: row.label || cell.label || '',
        });
      }
      for (const action of (cell.actions || []) as TrailAction[]) {
        events.push({ atMs: action.atMs, mark: action.mark, label: action.label, stepNum: row.num });
      }
    }
    // Rows arrive in authored order, which is execution order for steps; captures inside a step are
    // already in execution order. Sorting both by clock keeps a lane monotonic even when a retry
    // group's timestamps interleave with the step that follows it.
    steps.sort((a, b) => a.startMs - b.startMs);
    captures.sort((a, b) => a.atMs - b.atMs);
    events.sort((a, b) => a.atMs - b.atMs);
    const endMs = Math.max(
      steps.reduce((max, step) => Math.max(max, step.endMs), 0),
      captures.reduce((max, capture) => Math.max(max, capture.atMs), 0),
    );
    lanes.push({ index, steps, captures, events, endMs, failure });
  }
  const totalMs = Math.max(1, matrix.maxEndMs, ...lanes.map((lane) => lane.endMs));
  // Failure instants are shared stops: the moment a device died is the moment every reader jumps
  // to, whether or not they have that lane selected.
  const boundaries = sortedUnique([
    0,
    ...lanes.flatMap((lane) => lane.steps.map((step) => step.startMs)),
    ...lanes.flatMap((lane) => (lane.failure ? [lane.failure.atMs] : [])),
    totalMs,
  ]).filter((value) => value >= 0 && value <= totalMs);
  const firsts = lanes.map((lane) => lane.captures.length ? lane.captures[0].atMs : Infinity).filter((at) => Number.isFinite(at));
  return { lanes, totalMs, boundaries, firstCaptureMs: firsts.length ? Math.min(...firsts) : 0 };
}

/** False when no lane has a single timed step — nothing to play, so the view says so instead. */
export const replayable = (timeline: ReplayTimeline): boolean =>
  timeline.lanes.some((lane) => lane.steps.length > 0);

export interface ReplayLaneState {
  /** `pending` before the lane's first step, `done` once it is past its last activity. */
  phase: 'pending' | 'running' | 'done';
  /** The step the lane is on — held through the gap after it ends, so a lane always reads as
   * somewhere rather than blinking to nothing between steps. Null before the lane starts. */
  step: ReplayStep | null;
  /** What is on screen: the most recent capture at or before t. Null before the first one. */
  capture: ReplayCapture | null;
}

/** What one lane looked like at instant t. */
export function laneStateAt(lane: ReplayLane, t: number): ReplayLaneState {
  let step: ReplayStep | null = null;
  for (const candidate of lane.steps) {
    if (candidate.startMs > t) break;
    step = candidate;
  }
  let capture: ReplayCapture | null = null;
  for (const candidate of lane.captures) {
    if (candidate.atMs > t) break;
    capture = candidate;
  }
  const phase = step == null ? 'pending' : t >= lane.endMs ? 'done' : 'running';
  return { phase, step, capture };
}

/**
 * Interactions still worth drawing at instant t: those that fired within `windowMs` before it. A
 * mark is an event, not a state — it has to linger to be seen at all, and linger for a span the
 * WATCHER can perceive, which is why the caller scales the window by playback speed rather than
 * baking a run-clock constant in here.
 */
export const laneEventsAt = (lane: ReplayLane, t: number, windowMs: number): ReplayEvent[] =>
  lane.events.filter((event) => event.atMs <= t && t < event.atMs + Math.max(1, windowMs));

/**
 * How long a mark lingers, in run-clock milliseconds — the span the view hands `laneEventsAt`.
 *
 * Scaled by speed only while something is MOVING. At 10× an unscaled 700ms of run time is 70ms of
 * real time and the cue is gone before it registers; but a reader stepping key by key is paused and
 * reading, and scaling there put a seven-second window on a still frame, lighting up seven marks at
 * once on one screen.
 */
export const markWindowMs = (baseMs: number, speed: number, playing: boolean): number =>
  baseMs * (playing && speed > 1 ? speed : 1);

/**
 * The marks a pane should actually draw at t: still lingering, in clock order so the NEWEST IS LAST,
 * and at most `max` of them. Both properties are load-bearing — the newest mark is the one the view
 * leaves solid while the ones it followed recede, and an uncapped window over a burst of taps covers
 * the screen in dots that say nothing about which one just landed.
 */
export const laneMarksAt = (lane: ReplayLane, t: number, windowMs: number, max: number): ReplayEvent[] =>
  laneEventsAt(lane, t, windowMs).slice(-Math.max(1, max));

/**
 * The stops one lane's ←/→ keys land on: every capture (a moment its screen changed), every
 * interaction (a moment it acted — on a lane recording video these outnumber the captures by far,
 * and they are the moments worth landing on), every step start, which keeps a step whose
 * captures all land late still reachable at its beginning — and the lane's failure instant,
 * which is the stop the reader came for.
 */
export const laneStops = (lane: ReplayLane): number[] =>
  sortedUnique([
    ...lane.captures.map((capture) => capture.atMs),
    ...lane.events.map((event) => event.atMs),
    ...lane.steps.map((step) => step.startMs),
    ...(lane.failure ? [lane.failure.atMs] : []),
  ]);

/**
 * The next stop strictly past t in the given direction, or t itself at either end — so holding a
 * key down walks to the boundary and stays there instead of wrapping around to the far side.
 */
export function nextStop(stops: number[], t: number, dir: number): number {
  if (!stops.length) return t;
  if (dir >= 0) {
    for (const stop of stops) if (stop > t) return stop;
    return t;
  }
  for (let i = stops.length - 1; i >= 0; i--) if (stops[i] < t) return stops[i];
  return t;
}

/** Clamp a scrub/playback instant onto the axis. */
export const clampTime = (t: number, totalMs: number): number =>
  Math.max(0, Math.min(totalMs, Number.isFinite(t) ? t : 0));

/** m:ss on the shared axis — the transport clock and the axis ticks read the same way. */
export function fmtReplayClock(ms: number): string {
  const total = Math.max(0, Math.round(ms / 1000));
  return `${Math.floor(total / 60)}:${String(total % 60).padStart(2, '0')}`;
}

/**
 * True when a pane should KEEP its current frame shape rather than adopt a new source's.
 *
 * Consecutive captures of one device are not pixel-identical — a system bar appears, a scaler
 * rounds differently — and re-deriving the frame from each one nudged the picture a few pixels
 * sideways all through playback, which reads as jitter. Anything under a percent is the same
 * shape; a real change (a rotation, video handing over to a differently-shaped capture) is a
 * many-percent jump and sails past. A frame with no shape yet (prev unset/zero) always adopts.
 */
export const aspectHeld = (prev: number, next: number): boolean =>
  prev > 0 && next > 0 && Math.abs(next - prev) / prev < 0.01;

/**
 * Media seconds per run-clock second for a lane's recording: `duration / window`.
 *
 * A recorder's own bookends routinely span longer than the file they produced — the iPad lane of
 * C5804013 declares a 182.1s window for a 178.0s mp4 — so a recording's own time runs slightly
 * slower than the run clock it is being replayed against. 1 when there is nothing to scale by
 * (unknown duration, or a window that isn't positive), which leaves the caller on the run clock.
 */
export function videoClipScale(
  clip: { startMs: number; endMs: number },
  durationSec: number | null,
): number {
  if (durationSec == null || !(durationSec > 0)) return 1;
  const windowMs = clip.endMs - clip.startMs;
  return windowMs > 0 ? (durationSec * 1000) / windowMs : 1;
}

/** Highest playback rate a media element will accept; browsers refuse anything past this. */
export const MAX_MEDIA_RATE = 16;

/**
 * Lowest NON-ZERO playback rate a media element will accept. Browsers take positive rates only
 * from here up to MAX_MEDIA_RATE and throw on anything in between, so a clip whose file is a small
 * fraction of its declared window — a recording the recorder truncated, say — would otherwise ask
 * for a rate the element rejects. Zero itself is legal: it simply doesn't advance.
 */
export const MIN_MEDIA_RATE = 0.0625;

/**
 * The rate a lane's recording must play at to stay on the shared clock at replay speed `speed`.
 *
 * It is the UI speed times the clip's own scale: an element playing at exactly the replay speed
 * advances through a clip whose duration disagrees with its declared window at the wrong pace, and
 * drifts off the clock at that ratio until the sync's drift correction seeks it back — the iPad's
 * 178.0s/182.1s at 10× needs 9.77× and re-seeks about once a second at 10×, which discards the
 * decode pipeline and stutters exactly during the fast playback that most needs it.
 *
 * Clamped into the range a media element accepts, so a pathological clip plays slightly out of step
 * — the drift correction picks that up — instead of asking for a rate the element throws on.
 */
export function videoClipRate(
  clip: { startMs: number; endMs: number },
  durationSec: number | null,
  speed: number,
): number {
  const rate = speed * videoClipScale(clip, durationSec);
  if (!(rate > 0)) return 0;
  return Math.min(MAX_MEDIA_RATE, Math.max(MIN_MEDIA_RATE, rate));
}

/**
 * Where in a lane's recording the shared clock's instant `t` falls, in seconds — or null when the
 * recording doesn't cover it (before it started, after it ended, or its duration isn't known yet),
 * which is the caller's signal to fall back to that lane's captures.
 *
 * The position is SCALED by videoClipScale, not offset from the start: subtracting would drift by
 * the whole window/duration difference, which was measured landing four seconds and two screens
 * late by the end of the run. Scaling keeps a mark over the pixels it was aimed at.
 *
 * `laneT0` is the lane's run-clock epoch origin (ReportTraceModel.traceT0); `durationSec` is the
 * loaded media duration.
 */
export function videoClipTimeAt(
  clip: { startMs: number; endMs: number },
  laneT0: number | null,
  t: number,
  durationSec: number | null,
): number | null {
  if (laneT0 == null || durationSec == null || !(durationSec > 0)) return null;
  const intoMs = laneT0 + t - clip.startMs;
  if (intoMs < 0) return null;
  const at = (intoMs * videoClipScale(clip, durationSec)) / 1000;
  return at <= durationSec ? at : null;
}

/** Axis tick interval (seconds): the smallest round step that keeps labels `minGapPx` apart. */
export function replayTickSeconds(totalMs: number, widthPx: number, minGapPx = 64): number {
  const options = [1, 2, 5, 10, 15, 30, 60, 120, 300, 600];
  const perMs = (widthPx || 1) / Math.max(1, totalMs);
  return options.find((sec) => sec * 1000 * perMs >= minGapPx) || options[options.length - 1];
}
