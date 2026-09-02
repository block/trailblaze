// Behavioral contracts for the Replay projection's clock. The view paints whatever these functions
// say, so "the five columns are showing the same instant" is a claim about this file.
import { describe, expect, test } from "bun:test";
import {
  aspectHeld,
  buildReplayTimeline,
  clampTime,
  fmtReplayClock,
  laneStateAt,
  laneStops,
  nextStop,
  laneEventsAt,
  laneMarksAt,
  markWindowMs,
  MAX_MEDIA_RATE,
  MIN_MEDIA_RATE,
  replayable,
  replayTickSeconds,
  videoClipRate,
  videoClipTimeAt,
} from "./run-report-trail-replay";
import type { TrailMatrix } from "./run-report-trail-model";

const frame = (atMs: number | null, file: string, rowId = 1) => ({ rowId, kid: null, file, label: `tap ${file}`, atMs });
const action = (atMs: number, kind = "tap", extra: Record<string, unknown> = {}) => ({
  atMs,
  mark: { kind, x: 10, y: 20, dw: 100, dh: 200, ...extra },
  label: `${kind} at ${atMs}`,
});
const cell = (startMs: number | null, durationMs: number | null, frames: ReturnType<typeof frame>[], extra: Record<string, unknown> = {}) => ({
  headerId: 10,
  ok: true,
  selfHeal: false,
  label: "",
  labelDiffers: false,
  startMs,
  durationMs,
  toolCount: frames.length,
  frames,
  actions: [],
  lastFrame: frames.length ? frames[frames.length - 1] : null,
  failureAtMs: null,
  ...extra,
});

// Two devices running the same two steps: the second device starts step 2 later and finishes later.
const matrix = {
  rows: [
    { num: 1, label: "Sign in", cells: [
      cell(0, 1000, [frame(200, "a1"), frame(900, "a2")]),
      cell(0, 2000, [frame(500, "b1")]),
    ] },
    { num: 2, label: "Open menu", cells: [
      cell(1000, 500, [frame(1200, "a3")]),
      cell(2000, 3000, [frame(2500, "b2")], { ok: false }),
    ] },
  ],
  maxEndMs: 5000,
} as unknown as TrailMatrix;

describe("building the shared clock", () => {
  test("turns the step-major matrix into one timeline per device, in clock order", () => {
    const timeline = buildReplayTimeline(matrix);
    expect(timeline.lanes).toHaveLength(2);
    expect(timeline.totalMs).toBe(5000);
    expect(timeline.lanes[0].captures.map((c) => c.atMs)).toEqual([200, 900, 1200]);
    expect(timeline.lanes[1].captures.map((c) => c.atMs)).toEqual([500, 2500]);
    // Each lane ends when IT stops, which is the whole point of the view — device 0 is done at 1.5s
    // while device 1 grinds on to 5s.
    expect(timeline.lanes[0].endMs).toBe(1500);
    expect(timeline.lanes[1].endMs).toBe(5000);
    // A capture carries the step it happened during, so the stage can name it.
    expect(timeline.lanes[0].captures[2].stepLabel).toBe("Open menu");
    expect(timeline.lanes[1].steps[1].outcome).toBe("failed");
  });

  test("a row that names no shared step leaves each lane named by its own wording", () => {
    // Runs that are not one trail get blank row labels — nothing is shared to name a row after —
    // and keep each lane's wording on its cell. Reading only the row leaves the transport's step
    // chip, the rail tooltip and the zoomed capture with no step name at all.
    const spanning = {
      rows: [
        { num: 1, label: "", cells: [
          cell(0, 1000, [frame(200, "a1")], { label: "Sign in" }),
          cell(0, 800, [frame(300, "b1")], { label: "Settle up" }),
        ] },
      ],
      maxEndMs: 1000,
    } as unknown as TrailMatrix;
    const timeline = buildReplayTimeline(spanning);
    expect(timeline.lanes.map((lane) => lane.steps[0].label)).toEqual(["Sign in", "Settle up"]);
    expect(timeline.lanes.map((lane) => lane.captures[0].stepLabel)).toEqual(["Sign in", "Settle up"]);

    // Under a step join the authored row label IS the shared spine, so it still wins over a lane's
    // own wording for that step.
    const joined = {
      rows: [{ num: 1, label: "Sign in", cells: [cell(0, 1000, [frame(200, "a1")], { label: "Log in" })] }],
      maxEndMs: 1000,
    } as unknown as TrailMatrix;
    expect(buildReplayTimeline(joined).lanes[0].steps[0].label).toBe("Sign in");
    expect(buildReplayTimeline(joined).lanes[0].captures[0].stepLabel).toBe("Sign in");
  });

  test("boundaries are every instant any device starts a step — the trail's own moves", () => {
    // Device 0 starts steps at 0 and 1000; device 1 at 0 and 2000. Plus the axis end.
    expect(buildReplayTimeline(matrix).boundaries).toEqual([0, 1000, 2000, 5000]);
  });

  test("the opening instant is the first capture anywhere, not zero", () => {
    // Nothing is on any screen until 200ms, so opening at 0 would show only empty panes.
    expect(buildReplayTimeline(matrix).firstCaptureMs).toBe(200);
    // With no captures at all there is nowhere better to open than the start.
    const bare = { rows: [{ num: 1, label: "One", cells: [cell(0, 100, [])] }], maxEndMs: 100 } as unknown as TrailMatrix;
    expect(buildReplayTimeline(bare).firstCaptureMs).toBe(0);
  });

  test("an untimed step or capture is dropped, never placed at a guessed instant", () => {
    const untimed = {
      rows: [{ num: 1, label: "Sign in", cells: [cell(null, null, [frame(null, "x1")]), cell(0, 100, [frame(null, "y1"), frame(50, "y2")])] }],
      maxEndMs: 100,
    } as unknown as TrailMatrix;
    const timeline = buildReplayTimeline(untimed);
    // Lane 0 has nothing to place at all; lane 1 keeps only the capture that carried a timestamp.
    expect(timeline.lanes[0].steps).toHaveLength(0);
    expect(timeline.lanes[0].captures).toHaveLength(0);
    expect(timeline.lanes[1].captures.map((c) => c.file)).toEqual(["y2"]);
    expect(replayable(timeline)).toBe(true);
  });

  test("a lane's failure instant rides into the timeline and is a stop everyone can reach", () => {
    const failed = {
      rows: [
        { num: 1, label: "Sign in", cells: [cell(0, 1000, [frame(200, "a1")]), cell(0, 1000, [frame(300, "b1")])] },
        { num: 2, label: "Open menu", cells: [
          cell(1000, 500, [frame(1200, "a2")]),
          cell(1000, 2000, [frame(1500, "b2")], { ok: false, label: "Open the menu", failureAtMs: 2400 }),
        ] },
      ],
      maxEndMs: 3000,
    } as unknown as TrailMatrix;
    const timeline = buildReplayTimeline(failed);
    // The failing lane carries where and in which step it died; the passing lane carries nothing.
    expect(timeline.lanes[1].failure).toEqual({ atMs: 2400, stepNum: 2, label: "Open the menu" });
    expect(timeline.lanes[0].failure).toBeNull();
    // Reachable from the arrow keys BOTH ways: as a shared boundary with no lane selected, and as
    // one of the failing lane's own stops — but not smeared onto the healthy lane's stops.
    expect(timeline.boundaries).toContain(2400);
    expect(laneStops(timeline.lanes[1])).toContain(2400);
    expect(laneStops(timeline.lanes[0])).not.toContain(2400);
  });

  test("a trail with no timestamps anywhere is reported as un-replayable, not played at zero", () => {
    const none = { rows: [{ num: 1, label: "Sign in", cells: [cell(null, null, [])] }], maxEndMs: 0 } as unknown as TrailMatrix;
    const timeline = buildReplayTimeline(none);
    expect(replayable(timeline)).toBe(false);
    // Still safe to divide by.
    expect(timeline.totalMs).toBeGreaterThan(0);
  });
});

describe("what a device shows at an instant", () => {
  const timeline = buildReplayTimeline(matrix);

  test("before its first capture a device has nothing on screen, then holds the latest one", () => {
    expect(laneStateAt(timeline.lanes[0], 0).capture).toBeNull();
    expect(laneStateAt(timeline.lanes[0], 199).capture).toBeNull();
    expect(laneStateAt(timeline.lanes[0], 200).capture?.file).toBe("a1");
    // Held between captures — the screen doesn't go blank because nothing new was captured.
    expect(laneStateAt(timeline.lanes[0], 899).capture?.file).toBe("a1");
    expect(laneStateAt(timeline.lanes[0], 900).capture?.file).toBe("a2");
  });

  test("a device that has finished reads as done, and keeps its last frame", () => {
    const early = laneStateAt(timeline.lanes[0], 1400);
    expect(early.phase).toBe("running");
    // Device 0 is done at 1500 while the axis runs to 5000: the rest of the replay shows it finished.
    const late = laneStateAt(timeline.lanes[0], 3000);
    expect(late.phase).toBe("done");
    expect(late.capture?.file).toBe("a3");
    expect(late.step?.num).toBe(2);
    // Its slower partner is still running at that same instant.
    expect(laneStateAt(timeline.lanes[1], 3000).phase).toBe("running");
  });

  test("a device that has not started yet is pending, with no step to name", () => {
    const later = buildReplayTimeline({
      rows: [{ num: 1, label: "Sign in", cells: [cell(4000, 500, [frame(4200, "z1")])] }],
      maxEndMs: 4500,
    } as unknown as TrailMatrix);
    const state = laneStateAt(later.lanes[0], 1000);
    expect(state.phase).toBe("pending");
    expect(state.step).toBeNull();
    expect(state.capture).toBeNull();
  });

  test("the step is held through the gap after it ends, so a lane is never nowhere", () => {
    // Device 1's step 1 ends at 2000 and step 2 starts at 2000 — but device 0's step 1 ends at
    // 1000 with step 2 starting at exactly 1000 too. Probe a lane with a real gap instead.
    const gapped = buildReplayTimeline({
      rows: [
        { num: 1, label: "One", cells: [cell(0, 100, [frame(50, "g1")])] },
        { num: 2, label: "Two", cells: [cell(900, 100, [frame(950, "g2")])] },
      ],
      maxEndMs: 1000,
    } as unknown as TrailMatrix);
    const inGap = laneStateAt(gapped.lanes[0], 500);
    expect(inGap.phase).toBe("running");
    expect(inGap.step?.num).toBe(1);
    expect(inGap.capture?.file).toBe("g1");
  });
});

describe("stepping the transport", () => {
  const timeline = buildReplayTimeline(matrix);

  test("a selected device steps through its own captures and step starts", () => {
    // Lane 0: captures at 200/900/1200, steps starting 0/1000.
    expect(laneStops(timeline.lanes[0])).toEqual([0, 200, 900, 1000, 1200]);
    expect(nextStop(laneStops(timeline.lanes[0]), 0, 1)).toBe(200);
    expect(nextStop(laneStops(timeline.lanes[0]), 900, 1)).toBe(1000);
    expect(nextStop(laneStops(timeline.lanes[0]), 950, -1)).toBe(900);
  });

  test("at either end the playhead holds still instead of wrapping", () => {
    const stops = [0, 200, 900];
    expect(nextStop(stops, 900, 1)).toBe(900);
    expect(nextStop(stops, 0, -1)).toBe(0);
    // And with nothing to step to at all.
    expect(nextStop([], 400, 1)).toBe(400);
  });

  test("landing exactly on a stop still advances — repeated presses never stall", () => {
    const stops = laneStops(timeline.lanes[0]);
    let t = 0;
    const walked: number[] = [];
    for (let i = 0; i < 4; i++) { t = nextStop(stops, t, 1); walked.push(t); }
    expect(walked).toEqual([200, 900, 1000, 1200]);
  });

  test("scrub instants are clamped to the axis, and a non-finite one reads as the start", () => {
    expect(clampTime(-500, 5000)).toBe(0);
    expect(clampTime(9999, 5000)).toBe(5000);
    expect(clampTime(NaN, 5000)).toBe(0);
  });
});

describe("reading the axis", () => {
  test("the clock is m:ss, with minutes past ten kept whole", () => {
    expect(fmtReplayClock(0)).toBe("0:00");
    expect(fmtReplayClock(9400)).toBe("0:09");
    expect(fmtReplayClock(83000)).toBe("1:23");
    expect(fmtReplayClock(600000)).toBe("10:00");
    // Rounds seconds rather than truncating a hair short of the next one.
    expect(fmtReplayClock(59600)).toBe("1:00");
  });

  test("tick spacing widens as the axis gets denser, so labels never collide", () => {
    // A 3-minute trail across 900px: 15s ticks sit 75px apart, 10s ticks would be 50px.
    expect(replayTickSeconds(180000, 900)).toBe(15);
    // The same trail in a narrow pane has to coarsen.
    expect(replayTickSeconds(180000, 300)).toBe(60);
    // A zero width can't yield 0 or NaN.
    expect(replayTickSeconds(180000, 0)).toBeGreaterThan(0);
  });
});

describe("interactions on the shared clock", () => {
  // The same two devices, now with the taps and assertions they performed. Device 0's second step
  // acts twice without capturing anything in between.
  const acted = {
    rows: [
      { num: 1, label: "Sign in", cells: [
        cell(0, 1000, [frame(200, "a1")], { actions: [action(150), action(800, "assert", { ok: true })] }),
        cell(0, 2000, [frame(500, "b1")], { actions: [action(400, "swipe")] }),
      ] },
      { num: 2, label: "Open menu", cells: [
        cell(1000, 500, [], { actions: [action(1300), action(1100)] }),
        cell(2000, 3000, [frame(2500, "b2")], { actions: [] }),
      ] },
    ],
    maxEndMs: 5000,
  } as unknown as TrailMatrix;

  test("collects every device's interactions onto its own lane in clock order", () => {
    const timeline = buildReplayTimeline(acted);
    // Sorted by clock even though step 2 recorded them out of order.
    expect(timeline.lanes[0].events.map((e) => e.atMs)).toEqual([150, 800, 1100, 1300]);
    expect(timeline.lanes[0].events.map((e) => e.mark.kind)).toEqual(["tap", "assert", "tap", "tap"]);
    expect(timeline.lanes[1].events.map((e) => e.atMs)).toEqual([400]);
    // An interaction knows which step it belongs to, so the overlay can be attributed.
    expect(timeline.lanes[0].events[3].stepNum).toBe(2);
  });

  test("a mark is live for the window after it fires and not before or after", () => {
    const lane = buildReplayTimeline(acted).lanes[0];
    expect(laneEventsAt(lane, 149, 500).map((e) => e.atMs)).toEqual([]);
    expect(laneEventsAt(lane, 150, 500).map((e) => e.atMs)).toEqual([150]);
    expect(laneEventsAt(lane, 649, 500).map((e) => e.atMs)).toEqual([150]);
    // Exactly at the window's end it is gone — a mark that outlived its window would smear into
    // the next interaction's.
    expect(laneEventsAt(lane, 650, 500).map((e) => e.atMs)).toEqual([]);
  });

  test("a longer window (a faster playback speed) holds several marks at once", () => {
    const lane = buildReplayTimeline(acted).lanes[0];
    // 1100 and 1300 are 200ms apart: at a 500ms window both are on screen together, which is what
    // watching at speed looks like.
    expect(laneEventsAt(lane, 1300, 500).map((e) => e.atMs)).toEqual([1100, 1300]);
    expect(laneEventsAt(lane, 1300, 100).map((e) => e.atMs)).toEqual([1300]);
  });

  test("the linger window stretches with playback speed, but only while something is moving", () => {
    // Playing at 10x, 700ms of run time is 70ms of real time — gone before the eye registers it, so
    // the window is scaled to what the WATCHER can perceive.
    expect(markWindowMs(700, 10, true)).toBe(7000);
    expect(markWindowMs(700, 1, true)).toBe(700);
    // Paused, the reader is reading a still frame, and a 7-second window over it lit seven marks at
    // once on one screen. Speed is a property of motion; with none, it does not apply.
    expect(markWindowMs(700, 10, false)).toBe(700);
    expect(markWindowMs(700, 0.5, true)).toBe(700);
  });

  test("a pane draws at most the last few marks, newest last", () => {
    const lane = buildReplayTimeline(acted).lanes[0];
    // The whole run inside one window: uncapped this is every tap the lane ever made, at once.
    expect(laneEventsAt(lane, 1300, 5000).map((e) => e.atMs)).toEqual([150, 800, 1100, 1300]);
    // Capped, it is the most recent ones — and in clock order, because the view leaves the LAST one
    // solid and dims the ones it followed.
    expect(laneMarksAt(lane, 1300, 5000, 3).map((e) => e.atMs)).toEqual([800, 1100, 1300]);
    expect(laneMarksAt(lane, 1300, 5000, 1).map((e) => e.atMs)).toEqual([1300]);
    // A cap can never blank the overlay: one mark is the floor.
    expect(laneMarksAt(lane, 1300, 5000, 0).map((e) => e.atMs)).toEqual([1300]);
    // Under the cap it is just the window.
    expect(laneMarksAt(lane, 1300, 500, 3).map((e) => e.atMs)).toEqual([1100, 1300]);
  });

  test("a pane holds its shape through capture wobble, and adopts real shape changes", () => {
    // Consecutive captures of one device differ by a system bar's worth of pixels; re-deriving the
    // frame from each one nudged the picture sideways all through playback.
    expect(aspectHeld(1080 / 2400, 1080 / 2412)).toBe(true);
    expect(aspectHeld(1080 / 2400, 1080 / 2400)).toBe(true);
    // A rotation is a different rectangle entirely, as is video handing over to a portrait capture.
    expect(aspectHeld(1080 / 2400, 2400 / 1080)).toBe(false);
    expect(aspectHeld(2360 / 1640, 706 / 1535)).toBe(false);
    // A frame with no shape yet always adopts the first real source.
    expect(aspectHeld(0, 0.46)).toBe(false);
    expect(aspectHeld(NaN, 0.46)).toBe(false);
  });

  test("interactions are stops too, so a step that captured nothing is still walkable", () => {
    const lane = buildReplayTimeline(acted).lanes[0];
    // Step 2 captured no frames at all; without its interactions the arrow keys could only reach
    // its start, and the two taps inside it would be unvisitable.
    expect(laneStops(lane)).toEqual([0, 150, 200, 800, 1000, 1100, 1300]);
  });
});

describe("putting a recording on the shared clock", () => {
  // The iPad lane of C5804013, rounded: the recorder declares a 182.1s window for a 178.0s file.
  const clip = { startMs: 1_000_000, endMs: 1_000_000 + 182_102 };
  const duration = 177.967;
  const laneT0 = 1_000_000;

  test("scales by duration over the recorder's window instead of offsetting from its start", () => {
    // Measured on the real archive: at 141.3s into the run the linear answer showed a screen two
    // navigations later than the screenshot captured at that instant. The scaled answer matched.
    expect(videoClipTimeAt(clip, laneT0, 141_336, duration)).toBeCloseTo(138.127, 2);
    // Start and end are pinned, so the drift is zero at both bookends rather than only the first.
    expect(videoClipTimeAt(clip, laneT0, 0, duration)).toBe(0);
    expect(videoClipTimeAt(clip, laneT0, 182_102, duration)).toBeCloseTo(duration, 4);
  });

  test("declines outside the recording so the lane falls back to its captures", () => {
    // The recorder started after this lane's first log — nothing to show yet.
    expect(videoClipTimeAt({ startMs: laneT0 + 5_000, endMs: laneT0 + 100_000 }, laneT0, 1_000, 90)).toBeNull();
    // Past the end of the file: a held-forever last frame would be staler than the screenshots.
    expect(videoClipTimeAt(clip, laneT0, 182_103, duration)).toBeNull();
  });

  test("declines until the media duration is known, and without a lane clock", () => {
    // Metadata hasn't loaded: guessing a position here would paint the wrong instant, so the pane
    // stays on screenshots for the moment instead.
    expect(videoClipTimeAt(clip, laneT0, 10_000, null)).toBeNull();
    expect(videoClipTimeAt(clip, laneT0, 10_000, 0)).toBeNull();
    // An untimed run has no epoch origin to measure the recording against.
    expect(videoClipTimeAt(clip, null, 10_000, duration)).toBeNull();
  });

  test("a recorder window of zero degrades to real time rather than dividing by it", () => {
    expect(videoClipTimeAt({ startMs: laneT0, endMs: laneT0 }, laneT0, 3_000, 60)).toBe(3);
  });

  test("the element's own rate carries the same scale, so playing doesn't walk off the clock", () => {
    // The position mapping and the playback rate have to agree: an element playing at the bare UI
    // speed advances 10s of media per 10s of clock where the clip only holds 9.77s of it, so it
    // drifts past the sync's 250ms tolerance about once a second and re-seeks — which discards the
    // decode pipeline and stutters exactly at the speed that most needs smooth playback.
    expect(videoClipRate(clip, duration, 1)).toBeCloseTo(0.9773, 4);
    expect(videoClipRate(clip, duration, 10)).toBeCloseTo(9.773, 3);
    // Derivative check against the position mapping itself: over a second of clock at 10×, the rate
    // must move the element by exactly as much media as videoClipTimeAt expects it to have moved.
    const at = (t: number) => videoClipTimeAt(clip, laneT0, t, duration) as number;
    expect(videoClipRate(clip, duration, 10)).toBeCloseTo((at(20_000) - at(10_000)) / 10_000 * 10 * 1000, 6);
  });

  test("the rate stays inside what a media element accepts", () => {
    // A clip whose file is much longer than its declared window would otherwise ask for a rate the
    // browser throws on, taking the whole lane down instead of playing slightly out of step.
    expect(videoClipRate({ startMs: laneT0, endMs: laneT0 + 10_000 }, 600, 10)).toBe(MAX_MEDIA_RATE);
    // And the floor: browsers accept positive rates only from MIN_MEDIA_RATE up, so a recording the
    // recorder truncated — 5s of file for a 3-minute window — clamps instead of throwing. The
    // assignment happens inside the playback loop, so a throw there would stop every lane.
    expect(videoClipRate({ startMs: laneT0, endMs: laneT0 + 180_000 }, 5, 1)).toBe(MIN_MEDIA_RATE);
    // Zero is legal and means "don't advance", so it passes through rather than being floored into
    // a lane that creeps forward under a paused replay.
    expect(videoClipRate(clip, duration, 0)).toBe(0);
    // No scale to apply yet (duration unknown, or a zero window): the UI speed passes through.
    expect(videoClipRate(clip, null, 5)).toBe(5);
    expect(videoClipRate({ startMs: laneT0, endMs: laneT0 }, 60, 2)).toBe(2);
  });
});
