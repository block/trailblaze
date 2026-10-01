import { describe, expect, test } from "bun:test";
import {
  SHOT_CLIP_MIN_SEC, SHOT_CLIP_SPEED, SHOT_CLIP_SPEEDS, formatShotClipLength, formatShotClipTime, nextShotClipSpeed, shotClipCovers,
  shotClipMediaSecAt, shotClipRunSecAt, shotClipSegment,
} from "./run-report-shot-clip";

// A 20s recording whose file is exactly as long as its window, so media seconds are run seconds.
const CLIP = { startMs: 10_000, endMs: 30_000 };
const DURATION = 20;

describe("shotClipSegment", () => {
  test("plays from the step's start to the captured frame, at the run's own pace", () => {
    expect(SHOT_CLIP_SPEED).toBe(1);
    expect(shotClipSegment(CLIP, 12_000, 14_000, DURATION)).toEqual({ fromSec: 2, toSec: 4, rate: 1, mediaPerRunSec: 1 });
  });

  test("ends on the instant Replay maps the capture to when the file is shorter than its window", () => {
    // 19s of media for a 20s window: the capture 10s in sits at 9.5s of the file, not 10s.
    const segment = shotClipSegment(CLIP, 18_000, 20_000, 19);
    expect(segment?.toSec).toBeCloseTo(9.5, 6);
    expect(segment?.fromSec).toBeCloseTo(7.6, 6);
    // At the run's own pace, the file's seconds run at 0.95 per wall second.
    expect(segment?.rate).toBeCloseTo(0.95, 6);
  });

  test("plays at whichever speed the reader picked", () => {
    expect(shotClipSegment(CLIP, 12_000, 14_000, DURATION, 2)?.rate).toBe(2);
    expect(shotClipSegment(CLIP, 12_000, 14_000, DURATION, 4)?.rate).toBe(4);
    expect(shotClipSegment(CLIP, 18_000, 20_000, 19, 4)?.rate).toBeCloseTo(3.8, 6);
  });

  test("plays the whole of a long step, at the same pace as a short one", () => {
    const long = { startMs: 0, endMs: 600_000 };
    expect(shotClipSegment(long, 10_000, 100_000, 600)).toEqual({ fromSec: 10, toSec: 100, rate: 1, mediaPerRunSec: 1 });
  });

  test("a step that started before the recording plays from its first frame", () => {
    expect(shotClipSegment(CLIP, 5_000, 13_000, DURATION)).toEqual({ fromSec: 0, toSec: 3, rate: 1, mediaPerRunSec: 1 });
  });

  test("a frame captured the instant its step started still plays the moment leading into it", () => {
    expect(shotClipSegment(CLIP, 15_000, 15_000, DURATION)).toEqual({ fromSec: 5 - SHOT_CLIP_MIN_SEC, toSec: 5, rate: 1, mediaPerRunSec: 1 });
    expect(shotClipSegment(CLIP, null, 15_000, DURATION)?.fromSec).toBe(5 - SHOT_CLIP_MIN_SEC);
    // …but never before the recording's first frame.
    expect(shotClipSegment(CLIP, 10_200, 10_400, DURATION)?.fromSec).toBe(0);
  });

  test("declines when the recording doesn't reach the capture or its duration isn't known", () => {
    expect(shotClipSegment(CLIP, 5_000, 9_000, DURATION)).toBeNull();
    expect(shotClipSegment(CLIP, 29_000, 31_000, DURATION)).toBeNull();
    expect(shotClipSegment(CLIP, 12_000, 14_000, null)).toBeNull();
    expect(shotClipSegment(CLIP, 12_000, 14_000, 0)).toBeNull();
  });
});

describe("shotClipCovers", () => {
  test("marks a frame playable only when the recording window holds its capture", () => {
    expect(shotClipCovers(CLIP, 10_000)).toBe(true);
    expect(shotClipCovers(CLIP, 30_000)).toBe(true);
    expect(shotClipCovers(CLIP, 9_999)).toBe(false);
    expect(shotClipCovers(CLIP, 30_001)).toBe(false);
    expect(shotClipCovers(CLIP, null)).toBe(false);
  });
});

describe("the player's readout", () => {
  // The 19s file for a 20s window: the step's 2 run seconds span 1.9s of media.
  const segment = shotClipSegment(CLIP, 18_000, 20_000, 19)!;

  test("reads the step's own time, not the file's", () => {
    expect(shotClipRunSecAt(segment, segment.toSec)).toBeCloseTo(2, 6);
    expect(shotClipRunSecAt(segment, segment.fromSec + 0.95)).toBeCloseTo(1, 6);
    expect(shotClipMediaSecAt(segment, 1)).toBeCloseTo(segment.fromSec + 0.95, 6);
  });

  test("never reads or seeks outside the step", () => {
    expect(shotClipRunSecAt(segment, 0)).toBe(0);
    expect(shotClipRunSecAt(segment, 19)).toBeCloseTo(2, 6);
    expect(shotClipMediaSecAt(segment, -5)).toBe(segment.fromSec);
    expect(shotClipMediaSecAt(segment, 60)).toBe(segment.toSec);
  });

  test("formats positions as m:ss and lengths as a short hint", () => {
    expect(formatShotClipTime(0)).toBe("0:00");
    expect(formatShotClipTime(4.4)).toBe("0:04");
    expect(formatShotClipTime(89.5)).toBe("1:30");
    expect(formatShotClipTime(65)).toBe("1:05");
    expect(formatShotClipLength(0.3)).toBe("1s");
    expect(formatShotClipLength(16.9)).toBe("17s");
    expect(formatShotClipLength(65)).toBe("1:05");
  });

  test("the speed control cycles 1×, 2×, 4× and starts on the default", () => {
    expect(SHOT_CLIP_SPEEDS).toContain(SHOT_CLIP_SPEED);
    expect(nextShotClipSpeed(1)).toBe(2);
    expect(nextShotClipSpeed(2)).toBe(4);
    expect(nextShotClipSpeed(4)).toBe(1);
  });
});
