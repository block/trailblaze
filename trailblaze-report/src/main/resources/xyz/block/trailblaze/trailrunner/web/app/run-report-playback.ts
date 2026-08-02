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

// The frame-timing subset of VideoInfo the schedule math needs (the sprite layout fields stay on
// VideoInfo — only spriteFrameCss consumes those).
type PlaybackVideoTiming = { startMs?: number | null; fps: number; startFrame: number; endFrame: number };

// Logical video frame at a run-clock instant, clamped to the playable range.
function videoFrameAt(v: PlaybackVideoTiming, clockMs: number): number {
  return Math.max(v.startFrame, Math.min(v.endFrame, Math.floor(((clockMs - v.startMs) * v.fps) / 1000)));
}

// Run-clock ms at which the video's last playable frame ends.
function videoEndMs(v: PlaybackVideoTiming): number {
  return v.startMs + ((v.endFrame + 1) * 1000) / v.fps;
}

// CSS background geometry for one logical sprite frame (shared by the timeline preview and the
// Video tab player), shown via background-position. `columns`/`rows` describe one FULL sheet, so
// physical frame N lives on sheet `N / (columns*rows)`, filled row-major within the sheet
// (ffmpeg's `tile` filter) — mirroring VideoSpriteExtractor's spriteGridPosition. Transposing
// row/column silently serves a different frame of the same session for almost every index once
// columns > 1. The position math uses the target sheet's OWN row count: the final sheet may be
// shorter, and sizing it with the full-grid `rows` would stretch and mis-place every frame on it.
function spriteFrameCss(v: VideoInfo, logical: number): { sheet: number; size: string; position: string } {
  const physical = (v.frameMap[logical] != null) ? v.frameMap[logical] : logical;
  const framesPerSheet = v.columns * v.rows;
  const sheet = Math.floor(physical / framesPerSheet);
  const local = physical % framesPerSheet;
  const row = Math.floor(local / v.columns);
  const col = local % v.columns;
  const sheetRows = (v.sprites[sheet] && v.sprites[sheet].rows) || v.rows;
  return {
    sheet,
    size: `${v.columns * 100}% ${sheetRows * 100}%`,
    position: `${v.columns > 1 ? (col / (v.columns - 1)) * 100 : 0}% ${sheetRows > 1 ? (row / (sheetRows - 1)) * 100 : 0}%`,
  };
}

// Everything one playback run needs from the trace (and video, when one drives it): per-row
// playback-clock offsets, the stop instant, the video frame timing (video mode only), and the
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
};

// Build the playback schedule: for every trace row, the playback-clock ms at which it becomes the
// current row, plus when playback is over. Two modes:
// - video (a run-clock-mappable video + at least one timed row): the playback clock IS the run
//   clock — the video is real time. Offsets are real `ts` deltas from the first timed row (kept
//   monotonic against clock skew); untimed rows ride along with the nearest earlier timed row.
//   totalMs covers BOTH the trace and the video, so a video shorter than the trace can't wedge the
//   stop, and a trace that ends early keeps playing to the video's last frame.
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
    const timing = { startMs: video.startMs, fps: video.fps, startFrame: video.startFrame, endFrame: video.endFrame };
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
  return { mode: 'steps', clock0: null, offsets, totalMs: cum + (last ? playbackGapMs(Math.max(last.ms ?? 0, 350)) : 0), video: null, haveTs, lo, hi };
}

// Map a playback-clock instant to a position: the current row index, the run-clock ms (video mode
// only), the video frame to show (video mode only), and whether playback has finished. The frame
// timing rides on the schedule, so this needs no separate video argument — frame/clockMs are null
// exactly when the schedule has no video. Runs every animation frame, so the current-row lookup is
// a binary search (offsets are monotonic by construction) rather than a linear scan.
function playbackPositionAt(
  schedule: PlaybackSchedule,
  playMs: number,
): { stepIndex: number; clockMs: number | null; frame: number | null; done: boolean } {
  // Largest index whose offset has passed (floor 0 when even the first offset hasn't).
  let stepIndex = 0;
  let hi = schedule.offsets.length - 1;
  while (stepIndex < hi) {
    const mid = (stepIndex + hi + 1) >> 1;
    if (schedule.offsets[mid] <= playMs) stepIndex = mid; else hi = mid - 1;
  }
  const clockMs = schedule.clock0 != null ? schedule.clock0 + playMs : null;
  return {
    stepIndex,
    clockMs,
    frame: schedule.video && clockMs != null ? videoFrameAt(schedule.video, clockMs) : null,
    done: playMs >= schedule.totalMs,
  };
}

// Video-tab frame at an elapsed playback ms (speed already folded into the elapsed clock by the
// engine), wrapping past the last frame so the player loops — parity with the legacy player.
function videoLoopFrame(baseFrame: number, totalFrames: number, fps: number, elapsedMs: number): number {
  return totalFrames > 0 ? (baseFrame + Math.floor((elapsedMs * fps) / 1000)) % totalFrames : 0;
}

export { playbackGapMs, videoFrameAt, videoEndMs, spriteFrameCss, buildPlaybackSchedule, playbackPositionAt, videoLoopFrame };
export type { PlaybackSchedule, PlaybackVideoTiming };
