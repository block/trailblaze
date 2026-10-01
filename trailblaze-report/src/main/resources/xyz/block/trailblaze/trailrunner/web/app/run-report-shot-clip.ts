// A step thumbnail that plays its step: which stretch of a session's recording a still stands for,
// how fast to play it, and how the player's controls read. The viewer owns the elements that do
// the playing (see createShotClipPlayer in run-report-viewer.ts); this is the arithmetic, kept
// pure to test.
import { videoClipRate, videoClipTimeAt } from './run-report-trail-replay';

/**
 * How fast a step plays unless the reader picks another speed, as a multiple of the run's own
 * pace. The whole step plays, however long it is, so the reader sees everything between the
 * step's start and its frame.
 */
export const SHOT_CLIP_SPEED = 1;

/** The speeds the player's speed control steps through, in order. */
export const SHOT_CLIP_SPEEDS = [1, 2, 4] as const;

/** The speed after `speed` in SHOT_CLIP_SPEEDS, wrapping back to the first. */
export function nextShotClipSpeed(speed: number): number {
  const at = SHOT_CLIP_SPEEDS.indexOf(speed as (typeof SHOT_CLIP_SPEEDS)[number]);
  return SHOT_CLIP_SPEEDS[(at + 1) % SHOT_CLIP_SPEEDS.length];
}

/**
 * Shortest stretch played, in seconds of media. A step whose frame was captured the instant it
 * started has nothing of its own to show; the moment leading into that frame is still the motion
 * the still froze.
 */
export const SHOT_CLIP_MIN_SEC = 1;

export interface ShotClipSegment {
  /** Where playback starts, in media seconds. */
  fromSec: number;
  /** Where it stops and rests: the captured frame's instant, in media seconds. */
  toSec: number;
  /** The element's playbackRate: `speed` on the run clock. */
  rate: number;
  /**
   * Media seconds per run second. A recorder's file rarely lasts exactly as long as the window it
   * declares, so the readout converts through this to show the step's own time.
   */
  mediaPerRunSec: number;
}

/**
 * The stretch of `clip` that covers a step: from the step's start (`fromMs`, epoch ms) to the
 * instant its frame was captured (`toMs`) — the run-clock → media mapping Replay uses
 * (videoClipTimeAt), so playback ends on the frame Replay shows at that instant.
 *
 * Null when the recording doesn't reach the captured instant or its duration isn't known yet: the
 * cell keeps its still. A step that started before the recording did plays from its first frame;
 * a step with no start of its own plays the SHOT_CLIP_MIN_SEC before its frame.
 */
export function shotClipSegment(
  clip: { startMs: number; endMs: number },
  fromMs: number | null,
  toMs: number,
  durationSec: number | null,
  speed: number = SHOT_CLIP_SPEED,
): ShotClipSegment | null {
  const toSec = videoClipTimeAt(clip, toMs, 0, durationSec);
  if (toSec == null || durationSec == null) return null;
  const startSec = fromMs == null ? toSec : fromMs < clip.startMs ? 0 : videoClipTimeAt(clip, Math.min(fromMs, toMs), 0, durationSec) ?? 0;
  const fromSec = Math.max(0, Math.min(startSec, toSec - SHOT_CLIP_MIN_SEC));
  return { fromSec, toSec, rate: videoClipRate(clip, durationSec, speed), mediaPerRunSec: videoClipRate(clip, durationSec, 1) };
}

/** How far into the segment media time `sec` is, in run seconds, clamped to the segment. */
export function shotClipRunSecAt(segment: ShotClipSegment, sec: number): number {
  const clamped = Math.max(segment.fromSec, Math.min(segment.toSec, sec));
  return (clamped - segment.fromSec) / segment.mediaPerRunSec;
}

/** The media time `runSec` run seconds into the segment, clamped to the segment. */
export function shotClipMediaSecAt(segment: ShotClipSegment, runSec: number): number {
  return Math.max(segment.fromSec, Math.min(segment.toSec, segment.fromSec + runSec * segment.mediaPerRunSec));
}

/** A readout time: `0:04`, `1:05`, `12:00` — to the nearest second, like the caption's length. */
export function formatShotClipTime(sec: number): string {
  const whole = Math.max(0, Math.round(sec));
  return `${Math.floor(whole / 60)}:${String(whole % 60).padStart(2, '0')}`;
}

/** A step's length as a caption hint: `17s` under a minute, `1:05` from there — at least `1s`. */
export function formatShotClipLength(sec: number): string {
  const whole = Math.max(1, Math.round(sec));
  return whole < 60 ? `${whole}s` : formatShotClipTime(whole);
}

/**
 * Whether a recording window can show the frame captured at `toMs` — the render-time gate for
 * marking a thumbnail playable, before any media is loaded (the recording's duration, and so the
 * exact mapping, is only known once the browser reads it).
 */
export function shotClipCovers(recording: { startMs: number; endMs: number }, toMs: number | null): boolean {
  return toMs != null && Number.isFinite(toMs) && toMs >= recording.startMs && toMs <= recording.endMs;
}
