// ---- Playback timing (pure) --------------------------------------------------------------------
// The timing math behind every playback surface (timeline video mode, timeline steps mode, Video
// tab). Pure functions over plain numbers so the contract is unit-testable without a DOM; the
// viewer's rAF engine only maps their output onto elements.

// Clamp one inter-row gap for playback/axis compression. The 350ms floor keeps a fast tool burst
// visible; the 4000ms cap keeps a long idle stretch (an LLM turn, a wait-for poll) from stalling
// playback. The timeline axis rail uses the same clamp, so the scrub head, tick marks, and the
// steps-mode playback clock all move through one consistent compressed time base.
function playbackGapMs(gapMs: number): number {
  return Math.max(350, Math.min(4000, gapMs));
}

// Gap clamp for the EXPORT timeline — the `?autoplay=1` capture the CLI's --video/--gif/--webp
// exporters screen-record. Reproduces what the legacy report's export path produced: it played the
// session at 4x with any gap between recorded events capped at 4000ms of session time, so nothing
// ever sat on screen for more than a second and an exported animation's length tracked the number
// of steps rather than the session's real wall clock (a handful of actions spread over an hour must
// not become an hour of animation). Written here in playback ms, so `gap / 4` capped at 1000 is the
// same mapping.
//
// The floor is the one deliberate difference: it keeps a step on screen long enough for the
// exporter to catch it. The legacy path had no floor, so a burst of sub-100ms tool calls could be
// fast-forwarded past the shutter and never appear in the artifact at all.
//
// It is set relative to that shutter's NOMINAL cadence — 200ms, PlaywrightReportCapture's
// FRAME_INTERVAL_MS (~5fps). A dwell at least one shutter period long always contains a capture
// instant, so 250ms clears it with 25% to spare. `PlaywrightReportCaptureTest` asserts the two
// constants stay ordered that way, because the cadence lives in Kotlin and this floor lives here.
//
// What that does NOT cover: the 200ms cadence is requested, not guaranteed. `page.screenshot()`
// costs ~50-150ms, and on a loaded runner it can overshoot until the EFFECTIVE interval exceeds
// this floor — `computeFps` treats a measured 2fps (500ms/frame) as a normal outcome, and at that
// rate a 250ms dwell can land entirely between two samples and appear in no frame. The old 350ms
// floor had the same hole at a slightly higher load threshold; nothing about a time-sampled capture
// loop closes it. Capturing on each step transition instead of on a timer is the actual fix, and it
// belongs with the capture loop rather than with this constant.
//
// This is also the term that sets export length on a DENSE session — a step-heavy trail floors
// nearly every gap, so the artifact's length is essentially (row count x this value) and the 4x
// speedup above barely enters into it. It is the only lever that shortens such an export, and it is
// now one quarter of a shutter period from the point where steps start dropping even on an idle
// machine, so the next shortening needs the transition-driven capture above, not a smaller number.
const EXPORT_SPEEDUP = 4;
const EXPORT_GAP_MIN_MS = 250;
const EXPORT_GAP_MAX_MS = 1000;
function exportGapMs(gapMs: number): number {
  return Math.max(EXPORT_GAP_MIN_MS, Math.min(EXPORT_GAP_MAX_MS, gapMs / EXPORT_SPEEDUP));
}

// The recording's window on the run clock — the subset of VideoInfo the schedule math needs.
type PlaybackVideoTiming = { startMs: number; endMs: number };

// Run-clock ms at which the recording ends.
function videoEndMs(v: PlaybackVideoTiming): number {
  return v.endMs;
}

// Everything one playback run needs from the trace (and video, when one drives it): per-row
// playback-clock offsets, the stop instant, the recording's window (video mode only), and the
// rows' timestamp coverage (haveTs/lo/hi — consumed by the timeline axis so the rail and the
// playback clock derive from one computation).
type PlaybackSchedule = {
  mode: 'video' | 'steps';
  clock0: number | null;
  offsets: number[];
  totalMs: number;
  video: PlaybackVideoTiming | null;
  haveTs: boolean;
  lo: number;
  hi: number;
  // Export schedules only (buildExportSchedule): the run-clock instant each entry of `offsets`
  // sits on. Present exactly when the playback clock is NOT the run clock — the compressed
  // export timeline — so playbackPositionAt maps compressed playback time back onto real
  // timestamps piecewise instead of adding a fixed clock0. Null everywhere else.
  clockAnchors?: number[] | null;
};

// Build the playback schedule: for every trace row, the playback-clock ms at which it becomes the
// current row, plus when playback is over. Two modes:
// - video (a run-clock-mappable video + at least one timed row): the playback clock IS the run
//   clock — the video is real time. Offsets are real `ts` deltas from the first timed row (kept
//   monotonic against clock skew); untimed rows ride along with the nearest earlier timed row.
//   totalMs covers BOTH the trace and the video, so a video shorter than the trace can't wedge the
//   stop, and a trace that ends early keeps playing to the recording's end.
// - steps (no mappable video): the compressed offsets mirror the axis rail exactly — entry i adds
//   the clamped (playbackGapMs) gap from row i-1: the real timestamp delta when row i is timed,
//   else row i's recorded duration (350ms floor). So a row dwells until the FOLLOWING row's entry,
//   which keeps pacing real without stalling on idle and keeps the scrub head aligned with the
//   tick marks during playback. totalMs adds one final clamped dwell (the last row's own recorded
//   duration) so the last row stays visible before playback ends.
function buildPlaybackSchedule(
  rows: Array<{ ts?: number | null; ms?: number | null }>,
  video: VideoInfo | null,
): PlaybackSchedule {
  const tsVals = rows.map((r) => r.ts).filter((x) => x != null);
  const lo = tsVals.length ? tsVals.reduce((a, b) => b < a ? b : a, tsVals[0]) : 0;
  const hi = tsVals.length ? tsVals.reduce((a, b) => b > a ? b : a, tsVals[0]) : 0;
  const haveTs = tsVals.length >= 2 && hi > lo;
  let firstTs = null;
  for (const r of rows) { if (r.ts != null) { firstTs = r.ts; break; } }
  if (video && video.startMs != null && firstTs != null) {
    const timing = { startMs: video.startMs, endMs: video.endMs };
    const offsets = []; let cur = 0;
    for (const r of rows) { if (r.ts != null) cur = Math.max(cur, r.ts - firstTs); offsets.push(cur); }
    const traceEndMs = offsets.length ? offsets[offsets.length - 1] : 0;
    return { mode: 'video', clock0: firstTs, offsets, totalMs: Math.max(1, traceEndMs, videoEndMs(timing) - firstTs), video: timing, haveTs, lo, hi };
  }
  const offsets = []; let cum = 0; let prevTs = null;
  rows.forEach((r, i) => {
    if (i > 0) {
      const gap = haveTs && r.ts != null && prevTs != null ? r.ts - prevTs : Math.max(r.ms ?? 0, 350);
      cum += playbackGapMs(gap);
    }
    offsets.push(cum);
    if (r.ts != null) prevTs = r.ts;
  });
  const last = rows.length ? rows[rows.length - 1] : null;
  return { mode: 'steps', clock0: null, offsets, totalMs: cum + (last ? playbackGapMs(Math.max(last.ms ?? 0, 350)) : 0), video: null, haveTs, lo, hi, clockAnchors: null };
}

// Build the EXPORT playback schedule — what `?autoplay=1` plays and the CLI exporters record.
// Same shape as buildPlaybackSchedule, two deliberate differences:
// - Gaps are clamped to the tighter export window (exportGapMs) in BOTH modes. buildPlaybackSchedule
//   only compresses steps mode; its video mode IS the run clock, so an interactively-recorded
//   session with an hour of dead air would export an hour of a static screen.
// - A video rides on `clockAnchors` rather than a single clock0 offset: playback time is compressed,
//   so the run clock it maps to is piecewise-linear, and the recording's position follows that
//   mapping — fast-forwarding through a collapsed gap and playing 1:1 through real activity.
// totalMs stops one final dwell after the LAST ROW, never at the video's end: a trailing
// event-less video tail is exactly the dead air compression exists to drop.
function buildExportSchedule(
  rows: Array<{ ts?: number | null; ms?: number | null }>,
  video: VideoInfo | null,
): PlaybackSchedule {
  const tsVals = rows.map((r) => r.ts).filter((x) => x != null);
  const lo = tsVals.length ? tsVals.reduce((a, b) => b < a ? b : a, tsVals[0]) : 0;
  const hi = tsVals.length ? tsVals.reduce((a, b) => b > a ? b : a, tsVals[0]) : 0;
  const haveTs = tsVals.length >= 2 && hi > lo;
  let firstTs = null;
  for (const r of rows) { if (r.ts != null) { firstTs = r.ts; break; } }
  const offsets = []; const clockAnchors = [];
  let cum = 0; let prevTs = null; let clock = firstTs != null ? firstTs : 0;
  rows.forEach((r, i) => {
    if (i > 0) {
      const gap = haveTs && r.ts != null && prevTs != null ? r.ts - prevTs : (r.ms ?? 0);
      cum += exportGapMs(gap);
    }
    // Untimed rows ride along with the nearest earlier timed row; Math.max keeps the anchors
    // monotonic so clock skew in the recorded timestamps can't rewind the video.
    if (r.ts != null) { prevTs = r.ts; clock = Math.max(clock, r.ts); }
    offsets.push(cum);
    clockAnchors.push(clock);
  });
  const last = rows.length ? rows[rows.length - 1] : null;
  // One final dwell on the last row's own recorded duration, so it doesn't flash past at the end.
  const totalMs = cum + (last ? exportGapMs(last.ms ?? 0) : 0);
  if (video && video.startMs != null && firstTs != null) {
    const timing = { startMs: video.startMs, endMs: video.endMs };
    return { mode: 'video', clock0: firstTs, offsets, totalMs, video: timing, haveTs, lo, hi, clockAnchors };
  }
  return { mode: 'steps', clock0: null, offsets, totalMs, video: null, haveTs, lo, hi, clockAnchors: null };
}

// Run-clock instant at a compressed-playback instant, interpolating within the segment `i` starts.
// Past the last anchor (the final dwell) it clamps, so the last step holds its own frame.
function exportClockAt(schedule: PlaybackSchedule, playMs: number, i: number): number {
  const anchors = schedule.clockAnchors;
  const end = anchors.length - 1;
  if (i >= end) return anchors[end];
  const span = schedule.offsets[i + 1] - schedule.offsets[i];
  if (span <= 0) return anchors[i];
  const frac = Math.min(1, Math.max(0, (playMs - schedule.offsets[i]) / span));
  return anchors[i] + (anchors[i + 1] - anchors[i]) * frac;
}

// Map a playback-clock instant to a position: the current row index, the run-clock ms (video mode
// only — the instant the recording is seeked/played to), and whether playback has finished.
// clockMs is null exactly when the schedule has no run clock. Runs every animation frame, so the
// current-row lookup is a binary search (offsets are monotonic by construction) rather than a
// linear scan.
function playbackPositionAt(
  schedule: PlaybackSchedule,
  playMs: number,
): { stepIndex: number; clockMs: number | null; done: boolean } {
  // Largest index whose offset has passed (floor 0 when even the first offset hasn't).
  let stepIndex = 0;
  let hi = schedule.offsets.length - 1;
  while (stepIndex < hi) {
    const mid = (stepIndex + hi + 1) >> 1;
    if (schedule.offsets[mid] <= playMs) stepIndex = mid; else hi = mid - 1;
  }
  // An export schedule's playback clock is compressed, so its run clock is the piecewise map
  // through clockAnchors; every other schedule's playback clock IS the run clock (clock0 + playMs).
  const clockMs = schedule.clockAnchors ? exportClockAt(schedule, playMs, stepIndex)
    : schedule.clock0 != null ? schedule.clock0 + playMs : null;
  return {
    stepIndex,
    clockMs,
    done: playMs >= schedule.totalMs,
  };
}

export { playbackGapMs, exportGapMs, videoEndMs, buildPlaybackSchedule, buildExportSchedule, playbackPositionAt };
export type { PlaybackSchedule, PlaybackVideoTiming };
