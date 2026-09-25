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
 * A recorder's own bookends routinely span longer than the file they produced — one real iPad lane
 * declares a 182.1s window for a 178.0s mp4 — so a recording's own time runs slightly
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

/**
 * The same, except that a lane the axis HOLDS at its own start falls back to the recording's first
 * frame rather than declining.
 *
 * A lane with no timed step is held at instant 0 for the whole axis — see [ReplayAlignment.toLane].
 * A recording that started rolling after the run's clock origin doesn't cover that instant, and an
 * untimed cell contributes no captures to fall back to either, so declining leaves the pane empty
 * for the entire replay, which reads as a device that did nothing. The earliest frame it is on
 * record for, frozen, is what being held at its start looks like.
 */
export function heldClipTimeAt(
  clip: { startMs: number; endMs: number },
  laneT0: number | null,
  t: number,
  durationSec: number | null,
  heldAtStart: boolean,
): number | null {
  const at = videoClipTimeAt(clip, laneT0, t, durationSec);
  if (at != null) return at;
  // The duration gate is the one videoClipTimeAt applies: until the browser has read the container
  // there is no frame to hold, and forcing the video on top of nothing is worse than the still.
  return heldAtStart && laneT0 != null && durationSec != null && durationSec > 0 ? 0 : null;
}

// ── Aligning the clock by step ─────────────────────────────────────────────────────────────────
//
// The wall clock shows which device falls behind. It also means that by step 4 a fast phone and a
// slow tablet are showing unrelated screens, and a device that took a different path to the same
// place never lines up with the others again. The step-aligned clock trades the pacing for the
// comparison: the axis is one segment per authored step, each as wide as the SLOWEST device's
// time on it, and every device's time on that step is laid into that segment from its start.
// Nothing is compressed — a device just waits at the end of a segment for the others to catch up,
// so the columns realign at every step boundary and "the same step on every device" is what the
// stage shows at any instant inside one.

/** One segment of the step-aligned axis: an authored step, as wide as the slowest device's take on it. */
export interface ReplaySegment {
  /** The authored step, or null for the lead-in before any device's first step. */
  num: number | null;
  label: string;
  startMs: number;
  endMs: number;
}

export interface ReplayAlignment {
  /** The timeline with every instant moved onto the aligned axis — what the view paints from. */
  timeline: ReplayTimeline;
  segments: ReplaySegment[];
  /** A lane's own run-clock instant → its place on the aligned axis. */
  toAligned(lane: number, t: number): number;
  /**
   * An aligned-axis instant → the lane's own run clock: what that device was actually doing then.
   * Inside a segment past the lane's own time on the step, the lane is WAITING and this holds at
   * the end of its span — the recording pauses, the frame stays.
   */
  toLane(lane: number, a: number): number;
  /** True when the lane is waiting at aligned instant `a` — its own clock is not advancing. */
  frozenAt(lane: number, a: number): boolean;
  /**
   * The stretches of axis no instant of the lane's own clock lands in: from the end of its time on
   * a step to the start of its next one. Anything drawn as a continuous line has to be held flat
   * across one of these or it animates the device through a wait — see [memoryPoints].
   *
   * Narrower than `frozenAt`, which is also true after the lane's last step. There the map runs on
   * 1:1, so a reading taken after the run does land past it and nothing is unaccounted for.
   */
  waits(lane: number): Array<{ startMs: number; endMs: number }>;
}

/** One lane's time on one step: from the step's start until the lane's NEXT step starts. */
interface AlignedPiece {
  num: number;
  startMs: number;
  spanMs: number;
  segStart: number;
}

/**
 * Lay the timeline onto the step-aligned axis.
 *
 * A lane's span for a step runs to the start of ITS next step (or its end), so the idle stretch
 * after a step travels with the step and nothing is squeezed out. Segments follow the step
 * numbers — the authored order under a step join, the row position under a positional one — not
 * the order the devices happened to reach them, which is exactly what differs between lanes. A
 * lane that skipped a step has no piece in that segment and simply waits through it; a step no
 * lane timed has no segment. The lead-in (before a lane's first step) is a segment of its own when
 * any lane has one, so a device that launched late still starts its first step at the same instant
 * as the rest.
 */
export function alignReplayByStep(timeline: ReplayTimeline): ReplayAlignment {
  const labels = new Map<number, string>();
  const nums = new Set<number>();
  for (const lane of timeline.lanes) {
    for (const step of lane.steps) {
      nums.add(step.num);
      if (!labels.has(step.num) && step.label) labels.set(step.num, step.label);
    }
  }
  const order = Array.from(nums).sort((a, b) => a - b);
  const laneSpans = timeline.lanes.map((lane) => {
    const spans = new Map<number, { startMs: number; spanMs: number }>();
    lane.steps.forEach((step, i) => {
      const next = lane.steps[i + 1];
      const end = next ? next.startMs : Math.max(lane.endMs, step.endMs);
      spans.set(step.num, { startMs: step.startMs, spanMs: Math.max(0, end - step.startMs) });
    });
    return spans;
  });
  // Lead-in is the time before the trail's FIRST step, so only a lane that actually started on
  // that step can measure it. A lane whose earliest step is a later one was not waiting to launch,
  // it was idling through the steps it never ran — counting that as lead-in lays a segment of dead
  // axis AND still charges the steps it skipped their own width after it.
  const firstNum = order.length ? order[0] : null;
  const leadMs = Math.max(0, ...timeline.lanes.map((lane) =>
    (lane.steps.length && lane.steps[0].num === firstNum ? lane.steps[0].startMs : 0)));
  const segments: ReplaySegment[] = [];
  let cursor = 0;
  if (leadMs > 0) {
    // No label: `label` is the AUTHORED step's name and the lead-in is not a step. Wording for it
    // belongs to the view, which already has to say it differently in a tooltip and on a chip.
    segments.push({ num: null, label: '', startMs: 0, endMs: leadMs });
    cursor = leadMs;
  }
  const segStartByNum = new Map<number, number>();
  for (const num of order) {
    // At least 1ms: `segmentAt` matches on `a < endMs`, so a step every lane timed at 0ms would be
    // a segment no instant can ever fall in — attributed to its neighbour, and drawn zero-wide.
    const width = Math.max(1, ...laneSpans.map((spans) => (spans.get(num) || { spanMs: 0 }).spanMs));
    segStartByNum.set(num, cursor);
    segments.push({ num, label: labels.get(num) || '', startMs: cursor, endMs: cursor + width });
    cursor += width;
  }
  // Each lane's pieces in aligned order (the axis's), and in the lane's own order (its clock's);
  // the two agree for any lane that ran its steps in order, and each map walks the one it needs.
  const byAxis: AlignedPiece[][] = laneSpans.map((spans) =>
    order.filter((num) => spans.has(num)).map((num) => ({ num, ...(spans.get(num) as { startMs: number; spanMs: number }), segStart: segStartByNum.get(num) as number })));
  const byClock: AlignedPiece[][] = byAxis.map((pieces) => pieces.slice().sort((a, b) => a.startMs - b.startMs));

  const toAligned = (lane: number, t: number): number => {
    const pieces = byClock[lane] || [];
    if (!pieces.length) return t;
    // The lead-in keeps its own clock: a lane's pre-step time is laid from 0, and the lane then
    // waits until its first segment opens. Clamped there — mirroring `toLane`'s clamp — because a
    // lane whose earliest step is a LATER one has a first segment that starts before its own first
    // step does, and an unclamped identity would run the map BACKWARDS across that boundary. Every
    // reader downstream assumes clock order: `laneStateAt` scans with an early break and
    // `memorySampleAt` binary-searches, so both would silently answer with the wrong frame.
    // Not clamped at 0 — a capture taken before the axis opens keeps its real negative instant,
    // which is the lead-in's own contract; the view is what clamps it for painting.
    if (t < pieces[0].startMs) return Math.min(t, pieces[0].segStart);
    let last = pieces[0];
    for (const piece of pieces) {
      if (t < piece.startMs) break;
      last = piece;
      if (t < piece.startMs + piece.spanMs) return piece.segStart + (t - piece.startMs);
    }
    // Past the lane's last piece: its aligned end, plus however far past it t is, so the map stays
    // monotonic for a final reading or capture that landed after the last step.
    return last.segStart + last.spanMs + (t - (last.startMs + last.spanMs));
  };
  const toLane = (lane: number, a: number): number => {
    const pieces = byAxis[lane] || [];
    // A lane with no timed step has no place on an axis made of steps — and one lane's timed step
    // is enough to make the axis alignable, so such a lane really does get laid on it. Held at its
    // own start rather than run on its own clock: the identity map plays its recording straight
    // through while every other lane is held to the steps, showing screens from a different moment
    // under a heading that names the step the rest of the stage is on.
    if (!pieces.length) return 0;
    if (a < pieces[0].segStart) return Math.min(a, pieces[0].startMs);
    let wall = pieces[0].startMs;
    for (const piece of pieces) {
      if (a < piece.segStart) break;
      wall = piece.startMs + Math.min(a - piece.segStart, piece.spanMs);
    }
    return wall;
  };
  // Read off the piece rather than probing `toLane` at `a` and `a + 1`: this runs for every lane on
  // every animation frame, and the piece already holds the answer.
  const frozenAt = (lane: number, a: number): boolean => {
    const pieces = byAxis[lane] || [];
    // Frozen throughout, for the same reason `toLane` holds it at its start: there is no step of
    // this lane's for the axis to advance it against.
    if (!pieces.length) return true;
    // Before its first segment a lane is still spending its own lead-in, and frozen once that runs out.
    if (a < pieces[0].segStart) return a >= pieces[0].startMs;
    let piece = pieces[0];
    for (const candidate of pieces) {
      if (a < candidate.segStart) break;
      piece = candidate;
    }
    return a - piece.segStart >= piece.spanMs;
  };
  // Read off the pieces for the same reason `frozenAt` is: the gaps between them ARE the waits.
  const waitsByLane: Array<Array<{ startMs: number; endMs: number }>> = byAxis.map((pieces) => {
    const gaps: Array<{ startMs: number; endMs: number }> = [];
    if (!pieces.length) return gaps;
    // A lane's lead-in is laid from its own 0, so one that reached its first step sooner than the
    // slowest device waits from its own start instant until the segment opens.
    if (pieces[0].segStart > pieces[0].startMs) gaps.push({ startMs: pieces[0].startMs, endMs: pieces[0].segStart });
    pieces.forEach((piece, i) => {
      const next = pieces[i + 1];
      const from = piece.segStart + piece.spanMs;
      // Ascending, because `pieces` is: whoever walks these may rely on it.
      if (next && next.segStart > from) gaps.push({ startMs: from, endMs: next.segStart });
    });
    return gaps;
  });
  const waits = (lane: number): Array<{ startMs: number; endMs: number }> => waitsByLane[lane] || [];

  const lanes: ReplayLane[] = timeline.lanes.map((lane) => {
    const f = (t: number) => toAligned(lane.index, t);
    const spans = laneSpans[lane.index];
    /**
     * An instant inside a KNOWN step, placed in that step's own segment.
     *
     * Steps, captures, events and the failure all carry the step they belong to, and using it beats
     * inferring one from the clock. The two agree for any step with width — but a step whose
     * successor started in the same millisecond has a zero-wide span, so the time map walks
     * straight past it into the next step's segment, filing that step's screens and taps under the
     * step after it while its own segment stays unreachable.
     *
     * Only for an instant inside the step's own window. Outside it the clock map is the right
     * answer and its answers are load-bearing: a capture taken before the axis opens keeps its
     * negative lead-in instant, and a failure stamped after the last step has to map past the
     * step's end so the axis still reaches it.
     */
    const inStep = (num: number, t: number): number => {
      const piece = spans.get(num);
      const segStart = segStartByNum.get(num);
      if (!piece || segStart == null) return f(t);
      if (t < piece.startMs || t > piece.startMs + piece.spanMs) return f(t);
      return segStart + (t - piece.startMs);
    };
    // A step's bar keeps its own duration inside its segment.
    const barEnd = (step: ReplayStep) => inStep(step.num, step.startMs) + Math.min(Math.max(0, step.endMs - step.startMs), (spans.get(step.num) || { spanMs: 0 }).spanMs);
    // Re-sorted, not just mapped. `buildReplayTimeline` sorts these by the clock because a retry
    // group's timestamps interleave with the step that follows it, and for such a lane the step
    // NUMBERS disagree with the clock — so the aligned axis, which follows the numbers, reorders
    // them. `laneStateAt` scans with an early break and `memorySampleAt` binary-searches, so an
    // out-of-order array does not fail loudly, it just answers with the wrong frame.
    const byTime = <T>(items: T[], at: (item: T) => number): T[] => items.slice().sort((x, y) => at(x) - at(y));
    const steps = byTime(lane.steps.map((step) => ({ ...step, startMs: inStep(step.num, step.startMs), endMs: barEnd(step) })), (step) => step.startMs);
    const captures = byTime(lane.captures.map((capture) => ({ ...capture, atMs: inStep(capture.stepNum, capture.atMs) })), (capture) => capture.atMs);
    const events = byTime(lane.events.map((event) => ({ ...event, atMs: inStep(event.stepNum, event.atMs) })), (event) => event.atMs);
    const failure = lane.failure ? { ...lane.failure, atMs: inStep(lane.failure.stepNum, lane.failure.atMs) } : null;
    return {
      index: lane.index,
      steps,
      captures,
      events,
      // The lane's aligned end has to cover everything laid on it. A failure stamped after the
      // lane's last step maps past `f(lane.endMs)`, and an end short of it puts the marker and the
      // lane's own rail in disagreement about where the lane stops.
      endMs: Math.max(f(lane.endMs), failure ? failure.atMs : 0, ...steps.map((step) => step.endMs)),
      failure,
    };
  });
  // The axis has to reach every instant on it, so the widest lane decides — not the sum of the
  // segment widths, which a post-last-step failure or reading maps beyond.
  const totalMs = Math.max(1, cursor, ...lanes.map((lane) => lane.endMs));
  const boundaries = sortedUnique([
    0,
    ...segments.map((segment) => segment.startMs),
    ...lanes.flatMap((lane) => (lane.failure ? [lane.failure.atMs] : [])),
    totalMs,
  ]).filter((value) => value >= 0 && value <= totalMs);
  const firsts = lanes.map((lane) => (lane.captures.length ? lane.captures[0].atMs : Infinity)).filter((at) => Number.isFinite(at));
  return {
    timeline: { lanes, totalMs, boundaries, firstCaptureMs: firsts.length ? Math.min(...firsts) : 0 },
    segments,
    toAligned,
    toLane,
    frozenAt,
    waits,
  };
}

/** The segment an aligned instant falls in (the last one at and past the end), or null with none. */
export function segmentAt(segments: ReplaySegment[], a: number): ReplaySegment | null {
  if (!segments.length) return null;
  for (const segment of segments) if (a >= segment.startMs && a < segment.endMs) return segment;
  return a < segments[0].startMs ? segments[0] : segments[segments.length - 1];
}

/**
 * A memory series with every instant moved through `f` — onto the aligned axis, in practice.
 *
 * Re-sorted after the move, for the same reason the aligned lanes are: `f` follows the step numbers
 * and a lane can run them out of clock order, so the mapped readings need not come out ascending.
 * `memorySampleAt` binary-searches this, and a binary search over an unsorted array returns a
 * plausible wrong answer rather than an error.
 */
export function remapMemorySeries(series: ReplayMemorySeries, f: (t: number) => number): ReplayMemorySeries {
  // Every instant the reading carries, the stop that followed it included — that one is on the same
  // clock as the reading, and left on the lane's it would be held against aligned instants. The
  // lane's clock runs behind the axis wherever the lane waited, so a raw stop figure reads as
  // already past and the lookup reports the app dead through a stretch it was still running.
  const move = (sample: ReplayMemorySample): ReplayMemorySample => {
    const atMs = f(sample.atMs);
    const stopAfterMs = sample.stopAfterMs == null ? null : f(sample.stopAfterMs);
    return {
      ...sample,
      atMs,
      // Dropped when the map puts the stop BEFORE the reading it followed, which it can: `f` follows
      // the step numbers, so a lane that ran a lower-numbered step later lands that step's instants
      // earlier on the axis. The stop is still drawn, from `stops`, in the segment of the step it
      // happened in — but it is no longer in this reading's future here, and held against it the
      // readout would report the app dead through a step the device spent alive.
      stopAfterMs: stopAfterMs != null && stopAfterMs >= atMs ? stopAfterMs : null,
    };
  };
  const byTime = (samples: ReplayMemorySample[]): ReplayMemorySample[] => samples.map(move).sort((a, b) => a.atMs - b.atMs);
  return {
    ...series,
    samples: byTime(series.samples),
    segments: series.segments.map(byTime),
    stops: series.stops.map(f).sort((a, b) => a - b),
    spikes: byTime(series.spikes),
    peak: move(series.peak),
  };
}

/** Axis tick interval (seconds): the smallest round step that keeps labels `minGapPx` apart. */
export function replayTickSeconds(totalMs: number, widthPx: number, minGapPx = 64): number {
  const options = [1, 2, 5, 10, 15, 30, 60, 120, 300, 600];
  const perMs = (widthPx || 1) / Math.max(1, totalMs);
  // A run longer than the list covers takes the zoomed ruler's hour and day steps.
  return options.find((sec) => sec * 1000 * perMs >= minGapPx) || replayRulerStep(totalMs, widthPx, minGapPx).majorMs / 1000;
}

// ── App memory on the shared clock ─────────────────────────────────────────────────────────────
//
// A run that captured memory (events/memory.ndjson, see MemoryCapture in :trailblaze-capture)
// gets a second rail under its device rail: the app's heap over the run, on the SAME axis as the
// steps above it. Every reading is stamped with the host clock, like the trace rows, so it lands
// on the lane's run clock by the same subtraction the captures use — and two devices' spikes line
// up against the steps that caused them, which is the whole point of drawing it here rather than
// in the Timeline's event list.

/** One reading of the app's memory, on the shared clock. */
export interface ReplayMemorySample {
  atMs: number;
  /** Heap in use (Android) or resident footprint (iOS), in kB. */
  usedKb: number;
  /** The heap limit the reading was taken against, when the platform has one. */
  limitKb: number | null;
  /** Movement since the previous reading, when the producer reported it. */
  deltaKb: number | null;
  reason: string;
  /** The tool a before/after reading brackets, when it does. */
  tool: string | null;
  /**
   * The instant of the first stop recorded after this reading IN STREAM ORDER, or null if the app
   * was never seen to stop again.
   *
   * A timestamp alone cannot say whether a stop sharing a reading's instant happened before or after
   * it, and both orders really occur. A restart marks its stop at the first reading of the NEW
   * process, so that stop and that reading always share an instant and the reading is nonetheless
   * live. A death found at a tool boundary can carry the instant of the reading before it, because
   * capture holds a queued row's stamp at or after the last row it emitted. Recording which stop
   * followed each reading answers what comparing clocks cannot.
   *
   * Carried on the reading rather than as an array beside `samples` because every transform here
   * re-times and re-sorts the series, and a parallel array would have to be permuted in step with it
   * — silently wrong the first time one forgot.
   */
  stopAfterMs: number | null;
}

export interface ReplayMemorySeries {
  kind: 'heap' | 'footprint';
  /** Every reading with a figure, in clock order. */
  samples: ReplayMemorySample[];
  /**
   * The readings drawn as one continuous line: consecutive readings of ONE process. The line
   * breaks where the process was gone, had not started, or was replaced by another pid, because a
   * stroke across that gap would claim the app held that memory the whole way through.
   */
  segments: ReplayMemorySample[][];
  /**
   * Instants a RUNNING app was found gone, or found replaced by a different pid. Not "died": the
   * same mark covers a crash, a kill and an orderly teardown, and the readings cannot tell those
   * apart. An app the trail had not launched yet is absent, not stopped, and is not marked.
   */
  stops: number[];
  /** Readings that jumped by at least MEMORY_SPIKE_KB in one step — the ones worth a look. */
  spikes: ReplayMemorySample[];
  /** The highest reading of the run. */
  peak: ReplayMemorySample;
  /**
   * The last limit the run reported, for naming the ceiling the line is drawn under. Whether the
   * run came NEAR its limit is [memoryNearLimit]'s question, and it asks it per reading — this one
   * figure cannot answer it for a limit that moved.
   */
  limitKb: number | null;
}

/**
 * The session event stream the memory rails read, as `MemoryCapture` names it: `STREAM_NAME` on
 * the producer, `events/<name>.ndjson` on disk, `<name>.formatter.ts` in the formatter directory.
 *
 * Named here rather than written inline at the lookup because the lookup's every mismatch path
 * answers null, and a null is drawn as "this device reported no memory" — indistinguishable from
 * a rename. One constant, and a test that holds it against the formatter that actually exists,
 * turns a drift into a failing test instead of a rail that quietly stops appearing.
 */
export const MEMORY_STREAM_NAME = 'memory';

/**
 * The row fields a series is built FROM: the reading, either platform's spelling of it, and the
 * limit it was read against — see [buildReplayMemorySeries], which reads exactly these.
 *
 * Named because the Perfetto export has to drop exactly these from the stream's generic size
 * counter and no more: the dedicated memory track graphs them already, in MB, while a row's other
 * figures (a device's free memory, a footprint alongside a heap) are graphed nowhere else.
 */
export const MEMORY_SERIES_FIELDS = ['heapUsedKb', 'footprintKb', 'heapLimitKb'];

/**
 * A jump of 50 MB in one reading is a spike whatever prompted the sample — the same rule the
 * memory formatter uses to tone its Timeline row, so a row that reads as a warning there is
 * marked here.
 */
export const MEMORY_SPIKE_KB = 50 * 1024;

/** Within a tenth of the limit is where the next allocation may be the OutOfMemoryError. */
export const MEMORY_NEAR_LIMIT_RATIO = 0.9;

/** One decoded memory row: the reading's host-clock instant and its payload object. */
export interface ReplayMemoryEntry {
  t: number | null;
  data: unknown;
}

const isNum = (value: unknown): value is number => typeof value === 'number' && Number.isFinite(value);

/**
 * Turn a session's memory event stream into a lane's series, on that lane's run clock — or null
 * when there is nothing to draw: no lane clock to place the readings on, or no reading ever
 * carried a figure (an app that never ran).
 *
 * `laneT0` is the lane's run-clock epoch origin (ReportTraceModel.traceT0), the same one the
 * recordings are placed by. A reading before it (capture starts before the first trace row) is
 * kept at a negative instant, which the view clamps to the axis start: it is the baseline the
 * first step's movement is read against.
 */
export function buildReplayMemorySeries(entries: ReplayMemoryEntry[], laneT0: number | null): ReplayMemorySeries | null {
  if (laneT0 == null) return null;
  const samples: ReplayMemorySample[] = [];
  // Every reading on one clock, carrying a figure or not, each with the pid it saw. Segments and
  // deaths are both cut out of this AFTER the sort: which process a reading belongs to is a
  // statement about order, and a stream's file order is not its clock order.
  const marks: Array<{ atMs: number; sample: ReplayMemorySample | null; pid: number | null }> = [];
  // Which samples came from a heap reading, so the series' kind can be taken from the EARLIEST
  // reading rather than whichever one the file happened to list first — see the sort below.
  const heapSamples = new Set<ReplayMemorySample>();
  for (const entry of entries) {
    if (entry.t == null || !Number.isFinite(entry.t)) continue;
    const d = entry.data && typeof entry.data === 'object' ? entry.data as Record<string, unknown> : {};
    const atMs = entry.t - laneT0;
    const reason = typeof d.reason === 'string' ? d.reason : '';
    // A heap reading names the platform; a footprint does. A row with neither is the app not running.
    const usedKb = isNum(d.heapUsedKb) ? d.heapUsedKb : isNum(d.footprintKb) ? d.footprintKb : null;
    const pid = isNum(d.pid) ? d.pid : null;
    if (usedKb == null) {
      marks.push({ atMs, sample: null, pid });
      continue;
    }
    const sample: ReplayMemorySample = {
      atMs,
      usedKb,
      limitKb: isNum(d.heapLimitKb) && d.heapLimitKb > 0 ? d.heapLimitKb : null,
      deltaKb: isNum(d.deltaKb) ? d.deltaKb : null,
      reason,
      tool: typeof d.tool === 'string' && d.tool ? d.tool : null,
      stopAfterMs: null,
    };
    if (isNum(d.heapUsedKb)) heapSamples.add(sample);
    samples.push(sample);
    marks.push({ atMs, sample, pid });
  }
  if (!samples.length) return null;
  samples.sort((a, b) => a.atMs - b.atMs);
  marks.sort((a, b) => a.atMs - b.atMs);
  // Kind and limit are read off the sorted series, not the input order. The sort is here precisely
  // because a stream's file order is not its clock order, and both of these are statements about
  // WHEN: the kind is what the run started reporting, and the limit is the most recent one it
  // reported. Taking the limit from the last row in the file lets an Android heap limit that grew
  // mid-run be judged against an earlier, smaller one — so a series reads as near its limit when
  // it is not, or the reverse.
  const kind: 'heap' | 'footprint' = heapSamples.has(samples[0]) ? 'heap' : 'footprint';
  // A footprint reading has no limit to be near, so it reports none rather than a stale heap one.
  if (kind !== 'heap') for (const sample of samples) sample.limitKb = null;
  // Every reading carries the limit in force AT ITS OWN INSTANT: the one it reported, or the last
  // one reported before it. Not every reading names the limit, and the limit can grow mid-run — so
  // asking whether the run came near its limit has to compare each reading against its own
  // denominator. Judging the run's peak against the run's final limit answers the wrong question
  // twice over: a reading at 95% of the then-smaller limit reads as safe, and a modest peak under
  // a limit that later shrank reads as pressed.
  let inForce: number | null = null;
  for (const sample of samples) {
    if (sample.limitKb != null) inForce = sample.limitKb;
    else sample.limitKb = inForce;
  }
  const limitKb = kind === 'heap' ? inForce : null;
  // The line is cut by PROCESS IDENTITY, not by the reason a reading was taken. A reading's reason
  // says what prompted it — a sample taken at a tool boundary says `after_tool` even when what it
  // found was the app gone — so reading deaths off `reason` misses exactly the deaths that matter,
  // the ones a tool caused. The pid is the fact: no figure and no pid is the app not running (the
  // same reading the memory formatter labels "not running" rather than "not readable", which keeps
  // a failed reading of a live process from being reported as a death), and a figure under a
  // DIFFERENT pid is a different process, so the stroke must not carry the old one's level into it.
  const segments: ReplayMemorySample[][] = [];
  const stops: number[] = [];
  let current: ReplayMemorySample[] = [];
  // The readings no stop has followed yet. Every stop is marked through here so that each of those
  // readings learns which stop came after it — see [ReplayMemorySample.stopAfterMs].
  let awaitingStop: ReplayMemorySample[] = [];
  const markStop = (atMs: number) => {
    stops.push(atMs);
    for (const sample of awaitingStop) sample.stopAfterMs = atMs;
    awaitingStop = [];
  };
  let livePid: number | null = null;
  // Whether the app was seen GONE since the last figure, as opposed to merely unreadable.
  let wasGone = false;
  for (const mark of marks) {
    if (!mark.sample) {
      // No figure and no pid is the app not running. A pid with no figure is a reading that
      // failed on a live process: the line still breaks, but nothing stopped and the pid carries
      // on, so the next reading of that same pid is not a restart.
      if (mark.pid == null) {
        // Only an app that was RUNNING can have stopped. A trail that launches the app part way
        // in is read before it exists, and marking those readings would put a stop mark on every
        // run that starts its own app — the mark means "it was here and now it isn't".
        if (livePid != null || current.length) markStop(mark.atMs);
        livePid = null;
        wasGone = true;
      }
      if (current.length) segments.push(current);
      current = [];
      continue;
    }
    // A restart is taken from the producer's own word for it as well as from the pid changing. The
    // pid comparison alone misses a restart whose reading carries no pid, and cannot tell a reused
    // pid from the same process — and the producer already knows, because it is what set `reason`.
    const restarted = mark.sample.reason === MEMORY_REASON_PROCESS_RESTARTED
      || (livePid != null && mark.pid != null && mark.pid !== livePid);
    // Not when the app was already seen gone: that reading marked the stop at the instant the
    // process was observed dead, which is earlier and truer than this one. Marking again would put
    // two stop flags on one death.
    if (restarted && !wasGone) {
      // The old process ended at an instant nobody sampled, so the break is marked at the first
      // reading of the new one — the earliest instant the report can honestly point at.
      markStop(mark.atMs);
      if (current.length) segments.push(current);
      current = [];
    }
    if (restarted || wasGone) {
      // Movement measured against a reading of a DIFFERENT process is not movement. The producer
      // takes deltaKb against whatever it last emitted, which across a restart is the old
      // process's heap; left in place, a fresh app's first reading draws as a spike or a plunge
      // it never had.
      mark.sample.deltaKb = null;
    }
    wasGone = false;
    if (mark.pid != null) livePid = mark.pid;
    current.push(mark.sample);
    awaitingStop.push(mark.sample);
  }
  if (current.length) segments.push(current);
  const peak = samples.reduce((best, sample) => (sample.usedKb > best.usedKb ? sample : best), samples[0]);
  const spikes = samples.filter((sample) => sample.deltaKb != null && sample.deltaKb >= MEMORY_SPIKE_KB);
  return { kind, samples, segments, stops, spikes, peak, limitKb };
}

/**
 * The top of the memory rails' shared y-axis, in kB: the highest reading on ANY lane, with a
 * little headroom so the peak marker isn't clipped. Shared, not per lane — the rails exist to
 * compare devices, and a tablet's 300 MB drawn to the same height as a phone's 90 MB would read
 * as the same pressure. Never below 1, so used/scale is always safe.
 */
export function replayMemoryScaleKb(series: Array<ReplayMemorySeries | null>): number {
  const top = series.reduce((max, entry) => Math.max(max, entry ? entry.peak.usedKb : 0), 0);
  return Math.max(1, top * 1.08);
}

/**
 * True when ANY reading got within MEMORY_NEAR_LIMIT_RATIO of the limit in force at that reading's
 * own instant. Each reading against its own denominator, not the run's peak against the run's last
 * limit: an Android heap limit can grow mid-run, and comparing the whole run to the final figure
 * would let a reading at 95% of the then-smaller limit read as safe.
 */
/** The producer's `reason` for the first reading of a replacement process — MemoryChangeDetector. */
export const MEMORY_REASON_PROCESS_RESTARTED = 'process_restarted';

export const memoryNearLimit = (series: ReplayMemorySeries): boolean =>
  series.samples.some((sample) => sample.limitKb != null && sample.usedKb >= sample.limitKb * MEMORY_NEAR_LIMIT_RATIO);

/**
 * The app's memory at instant t: the latest reading at or before it — unless the app stopped
 * between that reading and t, in which case there is no figure to show, and null says so.
 * Null before the first reading too.
 *
 * Binary search, not a scan: this is called for every lane on every animation frame while Replay
 * plays and on every pointer move while the reader scrubs, and a long session's stream is thousands
 * of readings. `samples` is in clock order, which is what makes that sound.
 */
export function memorySampleAt(series: ReplayMemorySeries, t: number): ReplayMemorySample | null {
  const sample = series.samples[lastIndexAtOrBefore(series.samples, (s) => s.atMs, t)] || null;
  if (!sample) return null;
  // The stop that followed this reading has arrived by t, so there is nothing to report at t. Which
  // stop followed the reading is settled when the series is built, where the order is known; asking
  // here whether a stop's time beats the reading's got both same-instant cases exactly backwards —
  // it reported a live figure at the instant a death was observed, and would have reported none at
  // every restart's first reading.
  return sample.stopAfterMs != null && sample.stopAfterMs <= t ? null : sample;
}

/**
 * A series with every instant past `totalMs` moved onto it.
 *
 * Capture forces a final reading as it stops, which lands after the last step — past the end of the
 * axis. The rail already draws it at the endpoint, because drawing clamps; the lookup did not, so
 * scrubbing to 100% reported an older reading than the one visibly drawn there. Clamping the series
 * once makes the two agree by construction instead of by both remembering.
 */
export function clampMemorySeries(series: ReplayMemorySeries, totalMs: number): ReplayMemorySeries {
  if (!series.samples.some((sample) => sample.atMs > totalMs) && !series.stops.some((at) => at > totalMs)) return series;
  // The stop a reading is waiting on moves with it, or a death clamped onto the endpoint would stop
  // counting against the reading it followed and the app would read as alive at 100%.
  const move = (sample: ReplayMemorySample): ReplayMemorySample => ({
    ...sample,
    atMs: Math.min(sample.atMs, totalMs),
    stopAfterMs: sample.stopAfterMs == null ? null : Math.min(sample.stopAfterMs, totalMs),
  });
  return {
    ...series,
    samples: series.samples.map(move),
    segments: series.segments.map((segment) => segment.map(move)),
    stops: series.stops.map((at) => Math.min(at, totalMs)),
    spikes: series.spikes.map(move),
    peak: move(series.peak),
  };
}

/** The index of the last entry whose key is <= `t`, or -1 when every entry is later. */
function lastIndexAtOrBefore<T>(sorted: T[], keyOf: (entry: T) => number, t: number): number {
  let low = 0;
  let high = sorted.length - 1;
  let found = -1;
  while (low <= high) {
    const mid = (low + high) >> 1;
    if (keyOf(sorted[mid]) <= t) { found = mid; low = mid + 1; } else { high = mid - 1; }
  }
  return found;
}

/** A reading's height in a `height`-tall box, higher use drawn HIGHER (SVG y grows downward). */
export const memoryY = (usedKb: number, scaleKb: number, height: number): number =>
  height - Math.min(1, Math.max(0, usedKb / Math.max(1, scaleKb))) * height;

/**
 * One reading's level at an instant — what the line is drawn through. Not a reading: it carries no
 * `reason`, no tool and no pid, because some of these are the level HELD across a wait rather than
 * anything a device reported.
 */
interface MemoryLevel {
  atMs: number;
  usedKb: number;
}

/**
 * One segment's readings with the level held flat across every wait they span.
 *
 * On the step-aligned axis a lane's readings can sit either side of a stretch of axis its own clock
 * never entered, and a stroke drawn straight between them climbs while the device stood still — the
 * one part of a lane that keeps moving through a wait the rest of it visibly sits out. Two points at
 * the wait's edges, both at the level the pair had reached by then, make the aligned line the wall
 * clock's line with its waits held: the slope outside a wait is exactly what it was, and inside one
 * it is flat.
 *
 * The level is taken by the LANE's elapsed time, which a wait contributes none of, so holding a wait
 * does not move the line anywhere else. Nothing is invented: these points exist only in the
 * coordinates returned for drawing, never in the series the readout, spikes and peak come from.
 */
function holdAcrossWaits(segment: ReplayMemorySample[], waits: Array<{ startMs: number; endMs: number }>): MemoryLevel[] {
  if (!waits.length || segment.length < 2) return segment;
  const levels: MemoryLevel[] = [];
  // A wait that begins at a reading's own instant, or ends at one, holds at that reading's level, so
  // its edge point lands exactly on it. Dropped rather than drawn twice — but only on an exact
  // match: a wait that swallows a whole gap has an edge at the LATER reading's instant and the
  // earlier one's level, and that point is the flat stretch.
  const add = (level: MemoryLevel) => {
    const last = levels[levels.length - 1];
    if (last && last.atMs === level.atMs && last.usedKb === level.usedKb) return;
    levels.push(level);
  };
  segment.forEach((sample, i) => {
    add(sample);
    const next = segment[i + 1];
    if (!next) return;
    // A mapped reading can sit ON a wait's edge but never inside one — a wait is the axis with no
    // instant of this lane's clock in it — so the bounds are inclusive and a reading at an edge
    // simply shares its point with the hold.
    const spanned = waits.filter((wait) => wait.startMs >= sample.atMs && wait.endMs <= next.atMs && wait.endMs > wait.startMs);
    let waited = 0;
    const moving = next.atMs - sample.atMs - spanned.reduce((sum, wait) => sum + (wait.endMs - wait.startMs), 0);
    for (const wait of spanned) {
      // How much of the lane's own clock had run when this wait began. All of the pair's movement is
      // spent over `moving`; with none of it outside the waits, the earlier reading's level stands
      // and the line steps up at the later one instead of sloping out of a wait it never left.
      const elapsed = wait.startMs - sample.atMs - waited;
      const usedKb = moving > 0
        ? Math.round(sample.usedKb + ((next.usedKb - sample.usedKb) * elapsed) / moving)
        : sample.usedKb;
      add({ atMs: wait.startMs, usedKb });
      add({ atMs: wait.endMs, usedKb });
      waited += wait.endMs - wait.startMs;
    }
  });
  return levels;
}

/**
 * One segment as points in a `width`×`height` box over the whole axis. Instants off the axis are
 * clamped onto it rather than dropped: the baseline reading before the first step is the line's
 * start, and the final reading after the last step is its end.
 *
 * `waits` are the stretches of axis the lane spent waiting — `ReplayAlignment.waits` — and the line
 * is held level across each. Empty on the wall clock, where there are none.
 *
 * A ONE-reading segment is returned as two points a hair apart, because a one-point polyline paints
 * nothing at all — and a lone reading is the common case, not an exotic one: every app the trail
 * launches itself starts with a segment of one.
 */
export function memoryPoints(
  segment: ReplayMemorySample[],
  totalMs: number,
  scaleKb: number,
  width: number,
  height: number,
  waits: Array<{ startMs: number; endMs: number }> = [],
): Array<{ x: number; y: number }> {
  const total = Math.max(1, totalMs);
  const points = holdAcrossWaits(segment, waits).map((level) => ({
    x: round2((clampTime(level.atMs, total) / total) * width),
    y: round2(memoryY(level.usedKb, scaleKb, height)),
  }));
  if (points.length === 1) {
    const only = points[0];
    return [{ x: Math.max(0, only.x - 1), y: only.y }, { x: Math.min(width, only.x + 1), y: only.y }];
  }
  return points;
}

/** [memoryPoints] as an SVG `points`/`d` coordinate list. */
export const memoryPointsAttr = (points: Array<{ x: number; y: number }>): string =>
  points.map((point) => `${point.x},${point.y}`).join(' ');

const round2 = (value: number): number => Math.round(value * 100) / 100;

/**
 * kB as the reader thinks of it: MB past a megabyte, GB past a gigabyte.
 *
 * A deliberate second copy of the memory formatter's `formatKb`, which renders the same readings as
 * Timeline rows. They cannot share one function: a formatter is STAGED as a standalone module (see
 * RunReportGenerator.EVENT_FORMATTERS_RESOURCE_DIR) and may only import from a subdirectory staged
 * with it, never from the report modules above. So a test holds the two to the same output instead
 * — they had already drifted on sub-megabyte figures, and one reading printing two ways in one
 * report reads as a fault in the measurement rather than in the rendering.
 */
export function fmtMemoryKb(kb: number): string {
  const abs = Math.abs(kb);
  if (abs >= 1024 * 1024) return `${(kb / (1024 * 1024)).toFixed(2)} GB`;
  if (abs >= 1024) return `${(kb / 1024).toFixed(1)} MB`;
  return `${kb} kB`;
}

/**
 * The event stream a lane's memory rail reads: the plain stream for the session's own device, and
 * the device-scoped `memory.<device>` for a companion device in a multi-device run — both names the
 * memory formatter already declares it owns (`streams: ["memory", "memory.*"]`).
 *
 * Called with no argument for the session's own device — the one capture is scoped to. A companion
 * device names its own stream; a companion whose device has no name identifies NO stream and
 * answers null, because it must not fall back to the launch device's: drawing one device's memory
 * under another device's rail is a wrong answer, where an absent rail is merely a missing one.
 */
export function memoryStreamName(device?: string | null): string | null {
  if (device === undefined) return MEMORY_STREAM_NAME;
  return device ? `${MEMORY_STREAM_NAME}.${device}` : null;
}

/**
 * One embedded stream's rows as memory entries. A stream arrives in either of two shapes: the
 * memory formatter's rows, whose `raw[0]` is the decoded payload, or the generic fallback's events,
 * whose `d` is that payload serialized. An unparseable payload becomes a null-data entry rather
 * than throwing — this runs inside the render, where a throw blanks the page, not just the rail.
 */
export function memoryEntriesFromStream(stream: {
  rows?: Array<{ t: number | null; raw?: unknown[] }> | null;
  events?: Array<{ t: number | null; d: string }> | null;
} | null | undefined): ReplayMemoryEntry[] {
  if (!stream) return [];
  if (stream.rows && stream.rows.length) {
    return stream.rows.map((row) => ({ t: row.t, data: row.raw && row.raw.length ? row.raw[0] : null }));
  }
  return (stream.events || []).map((event) => {
    try { return { t: event.t, data: JSON.parse(event.d) }; } catch { return { t: event.t, data: null }; }
  });
}

/**
 * A reading in words, for the hover readout and the markers' titles:
 * `heap 84.2 MB of 192.0 MB (+52.0 MB) · after tapOnElement`. Only what the reading carries: an iOS
 * footprint has no limit, a periodic reading names no tool.
 */
export function describeMemorySample(series: ReplayMemorySeries, sample: ReplayMemorySample): string {
  let text = `${series.kind} ${fmtMemoryKb(sample.usedKb)}`;
  if (sample.limitKb != null) text += ` of ${fmtMemoryKb(sample.limitKb)}`;
  if (sample.deltaKb != null && sample.deltaKb !== 0) text += ` (${sample.deltaKb > 0 ? '+' : '−'}${fmtMemoryKb(Math.abs(sample.deltaKb))})`;
  if (sample.tool && (sample.reason === 'before_tool' || sample.reason === 'after_tool')) {
    text += ` · ${sample.reason === 'before_tool' ? 'before' : 'after'} ${sample.tool}`;
  }
  return text;
}

// ── Zooming the strip ──────────────────────────────────────────────────────────────────────────
//
// The strip is one viewport wide at 1×; zooming makes the rails `scale` viewports wide inside a
// horizontally scrolling viewport, the way a video editor stretches its timeline. Everything on
// the rails is placed in percent of the run, so nothing is re-laid — only the width and the
// scroll position change. These helpers own that arithmetic; the viewer owns the DOM.

// Zoom stops at the point where one second of the run is this many pixels wide — deep enough that
// two tool calls a tenth of a second apart are 50px apart and both get their names. A short run still gets at
// least a few stops, so the control never appears and does nothing.
export const REPLAY_STRIP_PX_PER_SECOND = 500;
export const REPLAY_STRIP_MIN_ZOOM_MAX = 4;
// The widest the zoomed rails may get. Browsers stop laying out an element somewhere past 17M px
// (Firefox) or 33M px (Chrome), so a trail whose devices ran days apart stops zooming here rather
// than at REPLAY_STRIP_PX_PER_SECOND — its tool calls stay on the strip, just not all named.
export const REPLAY_STRIP_MAX_RAILS_PX = 10_000_000;

export function replayStripZoomMax(totalMs: number, viewportPx: number): number {
  if (!(viewportPx > 0) || !(totalMs > 0)) return REPLAY_STRIP_MIN_ZOOM_MAX;
  const wanted = ((totalMs / 1000) * REPLAY_STRIP_PX_PER_SECOND) / viewportPx;
  return Math.max(REPLAY_STRIP_MIN_ZOOM_MAX, Math.min(wanted, REPLAY_STRIP_MAX_RAILS_PX / viewportPx));
}

// A name on the strip shows only once its room holds a readable stub; a tool's room is the gap to
// the next tool, less a little air so neighbouring names don't touch.
export const REPLAY_STRIP_MIN_LABEL_PX = 36;
export const REPLAY_STRIP_LABEL_GAP_PX = 6;

/** The pixels a tool's name gets on rails `railsPx` wide: its gap to the next tool, less the air. */
export function replayToolLabelRoom(gapMs: number, totalMs: number, railsPx: number): number {
  return (gapMs / Math.max(1, totalMs)) * railsPx - REPLAY_STRIP_LABEL_GAP_PX;
}

export type ReplayStripZoom = {
  /** Rails width as a multiple of the viewport; 1 is the whole run in view. */
  s: number;
  /** Scroll position as a fraction of the rails' full width, so it survives a resize. */
  at: number;
};

/**
 * Zoom by `factor` keeping the instant at `anchorFrac` (0..1 of the run) under the same viewport
 * pixel `anchorPx` — the pointer under a pinch, the playhead under a button press. The result is
 * clamped so the scale stays in [1, max] and the viewport never shows void beyond either end.
 */
export function zoomReplayStrip(zoom: ReplayStripZoom, factor: number, anchorFrac: number, anchorPx: number, viewportPx: number, max: number): ReplayStripZoom {
  const s = Math.max(1, Math.min(max, zoom.s * factor));
  if (!(viewportPx > 0)) return { s, at: 0 };
  const width = s * viewportPx;
  const left = Math.max(0, Math.min(width - viewportPx, anchorFrac * width - anchorPx));
  return { s, at: width > 0 ? left / width : 0 };
}

/**
 * Where to scroll so the playhead at `headPx` (on the zoomed rails) is in view, or null when it
 * already is. Pages rather than tracks: the head lands `lead` of the way in from the left and the
 * view then holds still while playback crosses it, so the strip is readable during playback.
 */
export function followReplayHead(scrollLeft: number, viewportPx: number, headPx: number, lead = 0.15): number | null {
  if (headPx >= scrollLeft && headPx <= scrollLeft + viewportPx) return null;
  return Math.max(0, headPx - viewportPx * lead);
}

/**
 * Where to scroll the strip's rows so the row at [rowTop, rowTop + rowHeight) (in the scroller's
 * content coordinates) is fully visible below the sticky ruler, which covers the top `stickyPx` of
 * a `viewportPx`-tall view; null when it already is. Moves the least distance: a row above lands
 * just under the ruler, a row below lands on the bottom edge, and a row taller than the view shows
 * its top.
 */
export function revealReplayRow(scrollTop: number, viewportPx: number, stickyPx: number, rowTop: number, rowHeight: number): number | null {
  const shownFrom = scrollTop + stickyPx;
  const shownTo = scrollTop + viewportPx;
  if (rowTop >= shownFrom && rowTop + rowHeight <= shownTo) return null;
  if (rowTop < shownFrom || rowHeight > viewportPx - stickyPx) return Math.max(0, rowTop - stickyPx);
  return rowTop + rowHeight - viewportPx;
}

/**
 * Zoom so that exactly the run fraction [startFrac, endFrac] fills the viewport — a range dragged
 * out on the overview, or a step double-clicked on the strip. Clamped to [1, max]; a range too
 * narrow for `max` is centred rather than pinned to its left edge.
 */
export function rangeReplayStrip(startFrac: number, endFrac: number, max: number): ReplayStripZoom {
  const a = Math.max(0, Math.min(startFrac, endFrac));
  const b = Math.min(1, Math.max(startFrac, endFrac));
  const span = Math.max(1e-9, b - a);
  const s = Math.max(1, Math.min(max, 1 / span));
  const shown = 1 / s;
  const left = Math.max(0, Math.min(1 - shown, (a + b) / 2 - shown / 2));
  return { s, at: left };
}

// The ruler's label spacing when zoomed: finer than replayTickSeconds, down to a tenth of a second,
// with unlabelled minor ticks between labels the way every timeline ruler draws them.
// Past a minute the steps run on through hours to a day, so a trail whose devices ran days apart
// still gets a ruler of readable labels rather than thousands of overlapping ones.
const MINUTE_MS = 60_000;
const HOUR_MS = 60 * MINUTE_MS;
const DAY_MS = 24 * HOUR_MS;
const RULER_LADDER: [number, number][] = [
  [100, 2], [200, 4], [500, 5], [1000, 5], [2000, 4], [5000, 5], [10000, 5], [15000, 3], [30000, 3],
  [MINUTE_MS, 4], [2 * MINUTE_MS, 4], [5 * MINUTE_MS, 5], [10 * MINUTE_MS, 5], [20 * MINUTE_MS, 4], [30 * MINUTE_MS, 3],
  [HOUR_MS, 4], [2 * HOUR_MS, 4], [3 * HOUR_MS, 3], [6 * HOUR_MS, 6], [12 * HOUR_MS, 4], [DAY_MS, 4],
];

/** The ruler's label spacing (majorMs) and tick spacing (minorMs) for rails `widthPx` wide. */
export function replayRulerStep(totalMs: number, widthPx: number, minGapPx = 64): { majorMs: number; minorMs: number } {
  const perMs = (widthPx || 1) / Math.max(1, totalMs);
  const rung = RULER_LADDER.find(([ms]) => ms * perMs >= minGapPx);
  if (rung) return { majorMs: rung[0], minorMs: rung[0] / rung[1] };
  // Beyond a day a label per day is still too close: step in 1, 2, 5, 10… days, halved by ticks.
  const days = minGapPx / (perMs * DAY_MS);
  const magnitude = Math.pow(10, Math.floor(Math.log10(days)));
  const nice = [1, 2, 5, 10].map((m) => m * magnitude).find((d) => d >= days) || 10 * magnitude;
  return { majorMs: nice * DAY_MS, minorMs: (nice * DAY_MS) / 2 };
}

/**
 * A ruler label: m:ss, h:mm:ss past an hour, and a tenth of a second once the labels are closer
 * than a second apart. Labels an hour or more apart read as hours and days: "6h", "1d 6h".
 */
export function fmtRulerClock(ms: number, majorMs: number): string {
  const pad = (n: number) => String(n).padStart(2, '0');
  if (majorMs >= HOUR_MS) {
    const hours = Math.max(0, Math.round(ms / HOUR_MS));
    const d = Math.floor(hours / 24);
    const h = hours % 24;
    return d ? (h ? `${d}d ${h}h` : `${d}d`) : `${h}h`;
  }
  const tenthsOn = majorMs < 1000;
  const tenths = Math.max(0, Math.round(ms / 100));
  const sec = tenthsOn ? Math.floor(tenths / 10) : Math.max(0, Math.round(ms / 1000));
  const h = Math.floor(sec / 3600);
  const clock = h ? `${h}:${pad(Math.floor(sec / 60) % 60)}:${pad(sec % 60)}` : `${Math.floor(sec / 60)}:${pad(sec % 60)}`;
  return tenthsOn ? `${clock}.${tenths % 10}` : clock;
}

/** How much of the run the strip is showing, for the zoom control: "850 ms", "4.2 s", "50 s", "10 min", "5 h". */
export function fmtReplaySpan(ms: number): string {
  if (ms < 1000) return `${Math.max(1, Math.round(ms))} ms`;
  if (ms < 10000) return `${(Math.round(ms / 100) / 10).toFixed(1)} s`;
  if (ms < 120000) return `${Math.round(ms / 1000)} s`;
  if (ms < 2 * 3_600_000) return `${Math.round(ms / 60000)} min`;
  // A trail whose devices ran days apart spans that long on a clock-aligned strip.
  if (ms < 2 * 86_400_000) return `${Math.round(ms / 3_600_000)} h`;
  return `${Math.round(ms / 86_400_000)} days`;
}
