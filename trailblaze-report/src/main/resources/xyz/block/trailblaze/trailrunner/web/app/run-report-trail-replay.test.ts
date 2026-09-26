// Behavioral contracts for the Replay projection's clock. The view paints whatever these functions
// say, so "the five columns are showing the same instant" is a claim about this file.
import { describe, expect, test } from "bun:test";
import memoryFormatter, { formatDeltaKb, formatKb } from "../../../report/event-formatters/memory.formatter";
import type { ReplayMemorySeries } from "./run-report-trail-replay";
import {
  replayChipFor,
  alignReplayByStep,
  aspectHeld,
  buildReplayMemorySeries,
  MEMORY_STREAM_NAME,
  buildReplayTimeline,
  clampMemorySeries,
  clampTime,
  describeMemorySample,
  fmtMemoryKb,
  fmtReplayClock,
  laneStateAt,
  laneStops,
  nextStop,
  laneEventsAt,
  laneMarksAt,
  markWindowMs,
  MAX_MEDIA_RATE,
  memoryNearLimit,
  memoryEntriesFromStream,
  memoryPoints,
  memoryPointsAttr,
  memorySampleAt,
  memoryStreamName,
  MIN_MEDIA_RATE,
  remapMemorySeries,
  replayable,
  replayMemoryScaleKb,
  replayTickSeconds,
  segmentAt,
  videoClipRate,
  heldClipTimeAt,
  heldClipsTimeAt,
  videoClipTimeAt,
  followReplayHead,
  revealReplayRow,
  fmtReplaySpan,
  fmtRulerClock,
  rangeReplayStrip,
  replayRulerStep,
  replayStripZoomMax,
  REPLAY_STRIP_MAX_RAILS_PX,
  REPLAY_STRIP_MIN_LABEL_PX,
  REPLAY_STRIP_MIN_ZOOM_MAX,
  REPLAY_STRIP_PX_PER_SECOND,
  replayToolLabelRoom,
  zoomReplayStrip,
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

  test("scrubbing back before a device's first step blanks its step chip, and scrubbing on restores it", () => {
    // A companion device that joins late: its pane is not faded before it starts, so a chip still
    // naming the step it was last scrubbed to would read as the device being on that step.
    const late = buildReplayTimeline({
      rows: [{ num: 1, label: "Sign in", cells: [cell(4000, 500, [frame(4200, "z1")], { headerId: 7 })] }],
      maxEndMs: 4500,
    } as unknown as TrailMatrix);
    const chipAt = (t: number) => replayChipFor(laneStateAt(late.lanes[0], t).step);
    const onStep = { num: "STEP 1", label: "Sign in", outcome: "passed", openHeaderId: 7 };
    const blank = { num: "—", label: "", outcome: null, openHeaderId: null };
    expect(chipAt(4200)).toEqual(onStep);
    expect(chipAt(1000)).toEqual(blank);
    expect(chipAt(4200)).toEqual(onStep);
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
  // A real iPad lane, rounded: the recorder declares a 182.1s window for a 178.0s file.
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

  test("a device bound twice plays whichever of its recordings covers the lane's instant", () => {
    // Two binds, 10s each, with a 10s unbound gap between them; each file is 10s long.
    const binds = [
      { clip: { startMs: laneT0, endMs: laneT0 + 10_000 }, duration: 10 },
      { clip: { startMs: laneT0 + 20_000, endMs: laneT0 + 30_000 }, duration: 10 },
    ];
    expect(heldClipsTimeAt(binds, laneT0, 4_000, false)).toEqual({ index: 0, at: 4 });
    // After the rebind, the SECOND recording, measured from its own start.
    expect(heldClipsTimeAt(binds, laneT0, 25_000, false)).toEqual({ index: 1, at: 5 });
    // Unbound in between: no recording reaches it, so the lane shows its captures.
    expect(heldClipsTimeAt(binds, laneT0, 15_000, false)).toBeNull();
    // A lane held at its start holds the first recording's opening frame.
    expect(heldClipsTimeAt([binds[1]], laneT0, 0, true)).toEqual({ index: 0, at: 0 });
    expect(heldClipsTimeAt([], laneT0, 0, true)).toBeNull();
  });

  test("a lane held at its start shows its first frame instead of an empty pane", () => {
    // A recorder that started 5s into the run, and a lane the axis holds at instant 0: there is no
    // frame at that instant, and a lane with no timed step has no captures to fall back to either.
    const late = { startMs: laneT0 + 5_000, endMs: laneT0 + 100_000 };
    expect(heldClipTimeAt(late, laneT0, 0, 90, true)).toBe(0);
    // Only for a held lane. Every other lane's pre-clip stretch still declines, because there the
    // captures are the honest answer and the clip's first frame would be from a later moment.
    expect(heldClipTimeAt(late, laneT0, 0, 90, false)).toBeNull();
    // And only where there is a frame to hold: the gates videoClipTimeAt applies still apply.
    expect(heldClipTimeAt(late, laneT0, 0, null, true)).toBeNull();
    expect(heldClipTimeAt(late, null, 0, 90, true)).toBeNull();
    // Inside the recording a held lane is not a special case — it reads the same position.
    expect(heldClipTimeAt(clip, laneT0, 141_336, duration, true)).toBeCloseTo(videoClipTimeAt(clip, laneT0, 141_336, duration) as number, 6);
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

describe("app memory on the shared clock", () => {
  // A lane whose first trace row landed at this epoch instant; memory rows are stamped on the same
  // host clock, so the subtraction that places captures places these.
  const laneT0 = 1_700_000_000_000;
  const at = (offsetMs: number) => laneT0 + offsetMs;
  const heap = (offsetMs: number, usedKb: number, extra: Record<string, unknown> = {}) => ({
    t: at(offsetMs),
    data: { reason: "memory_changed", appId: "com.example.app", pid: 100, heapUsedKb: usedKb, heapLimitKb: 196_608, gcForced: false, ...extra },
  });

  test("readings land on the lane's run clock, and the line follows them in order", () => {
    const series = buildReplayMemorySeries([
      heap(2_000, 40_000),
      heap(-500, 30_000, { reason: "first" }),
      heap(5_000, 45_000),
    ], laneT0);
    expect(series).not.toBeNull();
    // Sorted by clock, with the pre-run baseline kept at its negative instant — the view clamps it
    // to the axis start rather than this dropping the figure the first step is read against.
    expect(series!.samples.map((s) => s.atMs)).toEqual([-500, 2_000, 5_000]);
    expect(series!.kind).toBe("heap");
    expect(series!.limitKb).toBe(196_608);
    expect(series!.segments.length).toBe(1);
    expect(series!.peak.atMs).toBe(5_000);
  });

  test("the kind and the limit come from the clock order, not the file order", () => {
    // The sort exists because a stream's file order is not its clock order. Reading the limit off
    // the last row in the FILE lets an Android heap limit that grew mid-run be judged against an
    // earlier, smaller one, so a series reads as near its limit when it is not.
    const series = buildReplayMemorySeries([
      heap(5_000, 45_000, { heapLimitKb: 196_608 }),
      heap(9_000, 46_000, { heapLimitKb: 393_216 }),
      heap(1_000, 40_000, { heapLimitKb: 98_304 }),
    ], laneT0);
    expect(series!.limitKb).toBe(393_216);
    expect(memoryNearLimit(series)).toBe(false);

    // Same for the kind: the earliest reading says what this run reports, so a footprint row
    // listed first in the file must not make a heap run read as an iOS footprint one.
    const mixed = buildReplayMemorySeries([
      { t: at(4_000), data: { reason: "memory_changed", footprintKb: 64_000 } },
      { t: at(1_000), data: { reason: "first", heapUsedKb: 40_000, heapLimitKb: 196_608 } },
    ], laneT0);
    expect(mixed!.kind).toBe("heap");
  });

  test("a footprint series reports no limit to be near, on the samples as well as the series", () => {
    // iOS has no heap limit. A stray limit on one row must not put a ceiling on a footprint line,
    // where "90% of the limit" would be a warning about a number that does not exist.
    const series = buildReplayMemorySeries([
      { t: at(0), data: { reason: "first", footprintKb: 60_000 } },
      { t: at(1_000), data: { reason: "memory_changed", footprintKb: 64_000, heapLimitKb: 70_000 } },
    ], laneT0);
    expect(series!.kind).toBe("footprint");
    expect(series!.limitKb).toBeNull();
    expect(series!.samples.map((s) => s.limitKb)).toEqual([null, null]);
    expect(memoryNearLimit(series)).toBe(false);
  });

  test("the rails read the stream under the name the producer actually writes", () => {
    // Every mismatch in the lookup answers null, and a null draws as "this device reported no
    // memory" — so a rename on either side removes the rail silently. Held against the formatter
    // that ships in the report, which declares the very names it owns: if these two ever disagree
    // the rail is reading a stream nothing writes.
    expect(MEMORY_STREAM_NAME).toBe("memory");
    expect(memoryFormatter.streams).toContain(MEMORY_STREAM_NAME);
    expect(memoryFormatter.streams).toContain(`${MEMORY_STREAM_NAME}.*`);
    // The session's own device reads the plain stream; a companion device reads its own.
    expect(memoryStreamName()).toBe("memory");
    expect(memoryStreamName("emulator-5554")).toBe("memory.emulator-5554");
    // A companion with no device name identifies no stream at all — it must not quietly resolve to
    // the launch device's, which would draw one device's memory under another device's rail.
    expect(memoryStreamName("")).toBeNull();
  });

  test("a figure reads the same on the rail as it does in the timeline row", () => {
    // Two copies exist because a formatter is staged as a standalone module and cannot import the
    // report modules above it. They had already drifted on sub-megabyte figures, so hold them to
    // the same output — one reading printed two ways in one report reads as a bad measurement.
    for (const kb of [0, 1, 512, 1023, 1024, 40_960, 1024 * 1024, 2 * 1024 * 1024, -2048]) {
      expect(fmtMemoryKb(kb)).toBe(formatKb(kb));
    }
    // And the movement the rail's readout renders inline matches the formatter's signed form.
    expect(formatDeltaKb(-10_000)).toBe("−9.8 MB");
  });

  test("an embedded stream is read in either shape it arrives in", () => {
    // The formatter's rows carry the decoded payload in `raw[0]`; the generic fallback carries it
    // serialized in `d`. Both must decode, and a payload that will not parse must not throw —
    // this runs inside the render, where a throw blanks the page rather than just the rail.
    expect(memoryEntriesFromStream({ rows: [{ t: 7, raw: [{ heapUsedKb: 1 }] }] }))
      .toEqual([{ t: 7, data: { heapUsedKb: 1 } }]);
    expect(memoryEntriesFromStream({ rows: [{ t: 7, raw: [] }] })).toEqual([{ t: 7, data: null }]);
    expect(memoryEntriesFromStream({ events: [{ t: 8, d: '{"heapUsedKb":2}' }] }))
      .toEqual([{ t: 8, data: { heapUsedKb: 2 } }]);
    expect(memoryEntriesFromStream({ events: [{ t: 9, d: "not json" }] })).toEqual([{ t: 9, data: null }]);
    expect(memoryEntriesFromStream(null)).toEqual([]);
  });

  test("without a lane clock or a single figure there is no series, so the rail is not drawn", () => {
    expect(buildReplayMemorySeries([heap(0, 1000)], null)).toBeNull();
    // An app that never ran: every row is a not-running row.
    expect(buildReplayMemorySeries([
      { t: at(0), data: { reason: "first", appId: "com.example.app" } },
      { t: at(1000), data: { reason: "after_tool", tool: "launchApp", appId: "com.example.app" } },
    ], laneT0)).toBeNull();
    // Rows without an instant, or that failed to decode, are skipped rather than placed.
    expect(buildReplayMemorySeries([{ t: null, data: { heapUsedKb: 5 } }, { t: at(1), data: null }], laneT0)).toBeNull();
  });

  test("the line breaks where the process died, and the readout says so instead of holding the last figure", () => {
    const series = buildReplayMemorySeries([
      heap(0, 40_000),
      heap(1_000, 42_000),
      { t: at(1_500), data: { reason: "process_died", appId: "com.example.app" } },
      heap(3_000, 20_000, { reason: "process_restarted" }),
      heap(4_000, 25_000),
    ], laneT0)!;
    expect(series.segments.map((seg) => seg.map((s) => s.atMs))).toEqual([[0, 1_000], [3_000, 4_000]]);
    expect(series.stops).toEqual([1_500]);
    // The peak is the highest reading, not the newest: the restarted app never got back to the
    // level the old process reached, and the rail's name column reports the run's high-water mark.
    expect(series.peak.atMs).toBe(1_000);
    // Before the stop the latest reading holds; after it, nothing does until the app is back.
    expect(memorySampleAt(series, 1_200)?.usedKb).toBe(42_000);
    expect(memorySampleAt(series, 2_000)).toBeNull();
    expect(memorySampleAt(series, 3_500)?.usedKb).toBe(20_000);
    // And before the first reading there is no figure either.
    expect(memorySampleAt(series, -1)).toBeNull();
  });

  test("a death recorded at a live reading's own instant wins over that reading", () => {
    // Capture holds a queued row's stamp at or after the last row it emitted, so a death observed
    // at a tool boundary can carry the instant of the reading before it. Deciding by clock alone
    // ("is the stop LATER than the reading?") then reports a heap figure for a process that was
    // just seen gone — the one instant where holding the last figure is plainly wrong.
    const series = buildReplayMemorySeries([
      heap(0, 40_000),
      heap(1_000, 42_000),
      { t: at(1_000), data: { reason: "after_tool", tool: "stopApp", appId: "com.example.app" } },
    ], laneT0)!;
    expect(series.stops).toEqual([1_000]);
    expect(memorySampleAt(series, 1_000)).toBeNull();
    expect(memorySampleAt(series, 1_500)).toBeNull();
    // The reading before the tie is untouched.
    expect(memorySampleAt(series, 999)?.usedKb).toBe(40_000);
  });

  test("a restart's stop shares an instant with the new process's first reading, which is still live", () => {
    // The mirror image, and why an equal-instant stop cannot simply be made to win: a restart marks
    // its stop at the first reading of the NEW process, so that stop and that reading ALWAYS tie.
    // Blanking the readout on a tie would report "not running" at every restart, for a process that
    // is running and just gave a figure.
    const series = buildReplayMemorySeries([
      heap(0, 40_000, { pid: 9 }),
      heap(3_000, 20_000, { pid: 11 }),
    ], laneT0)!;
    expect(series.stops).toEqual([3_000]);
    expect(memorySampleAt(series, 3_000)?.usedKb).toBe(20_000);
  });

  test("clamping the final reading and the stop onto the axis end does not resurrect the app", () => {
    // Capture forces a final reading as it stops, and when both it and the stop land past the last
    // step, clamping moves the reading AND the stop onto the same instant — the tie above, reached
    // with no held stamps at all, every time a run ends with the app already gone.
    const series = buildReplayMemorySeries([
      heap(0, 40_000),
      heap(6_000, 44_000),
      { t: at(7_000), data: { reason: "final", appId: "com.example.app" } },
    ], laneT0)!;
    const total = 5_000;
    expect(memorySampleAt(clampMemorySeries(series, total), total)).toBeNull();
  });

  test("a reading that found the app gone is a stop whatever prompted it", () => {
    // The producer names a sample after what prompted it, so a reading taken at a tool boundary
    // says `after_tool` even when what it found was the app gone. Reading deaths off that name
    // misses exactly the deaths that matter — the ones a tool caused — and the readout goes on
    // reporting the dead process's last heap figure.
    const series = buildReplayMemorySeries([
      heap(0, 40_000),
      { t: at(1_000), data: { reason: "after_tool", tool: "stopApp", appId: "com.example.app" } },
      heap(3_000, 12_000),
    ], laneT0)!;
    expect(series.stops).toEqual([1_000]);
    expect(series.segments.map((seg) => seg.map((s) => s.atMs))).toEqual([[0], [3_000]]);
    expect(memorySampleAt(series, 2_000)).toBeNull();
  });

  test("an app the trail had not launched yet is absent, not stopped", () => {
    // Capture starts with the session, so a trail that launches its own app is read several times
    // before the app exists. Those readings carry no figure and no pid — the same shape as a
    // reading that finds the app gone — and marking them would put a red bar on the rail of every
    // run that launches the app itself.
    const series = buildReplayMemorySeries([
      { t: at(0), data: { reason: "first", appId: "com.example.app" } },
      { t: at(2_000), data: { reason: "before_tool", tool: "launchApp", appId: "com.example.app" } },
      heap(4_000, 30_000),
      heap(6_000, 34_000),
      { t: at(9_000), data: { reason: "final", appId: "com.example.app" } },
    ], laneT0)!;
    // One mark only: the teardown, where an app that WAS running is gone.
    expect(series.stops).toEqual([9_000]);
    expect(series.segments.map((seg) => seg.map((s) => s.atMs))).toEqual([[4_000, 6_000]]);
    expect(memorySampleAt(series, 1_000)).toBeNull();
    expect(memorySampleAt(series, 7_000)?.usedKb).toBe(34_000);
    expect(memorySampleAt(series, 9_500)).toBeNull();
  });

  test("a reading that could not be taken is not a stop, because the process is still there", () => {
    // A figure-less row that still names a pid is a failed reading of a live app. Calling that a
    // stop would blank the readout for the rest of the run over one unanswered `dumpsys`.
    const series = buildReplayMemorySeries([
      heap(0, 40_000),
      { t: at(1_000), data: { reason: "unreadable", appId: "com.example.app", pid: 100 } },
      heap(2_000, 44_000, { deltaKb: 4_000 }),
    ], laneT0)!;
    expect(series.stops).toEqual([]);
    expect(memorySampleAt(series, 1_500)?.usedKb).toBe(40_000);
    // Same process throughout, so the movement across the unread gap is still movement.
    expect(series.samples[1].deltaKb).toBe(4_000);
  });

  test("a restarted app starts a new line instead of continuing the old process's", () => {
    // Two readings under different pids are two processes. Stroked as one line they claim the app
    // held that memory across the restart, and the producer's delta — taken against the OLD
    // process's heap — draws as a spike or a plunge that never happened.
    const series = buildReplayMemorySeries([
      heap(0, 120_000),
      heap(1_000, 130_000, { deltaKb: 10_000 }),
      heap(2_000, 20_000, { pid: 200, reason: "process_restarted", deltaKb: -110_000 }),
      heap(3_000, 26_000, { pid: 200, deltaKb: 6_000 }),
    ], laneT0)!;
    expect(series.segments.map((seg) => seg.map((s) => s.atMs))).toEqual([[0, 1_000], [2_000, 3_000]]);
    expect(series.stops).toEqual([2_000]);
    // The cross-process delta is dropped, so it is not reported as a spike; the new process's own
    // movement is kept.
    expect(series.samples.map((s) => s.deltaKb)).toEqual([null, 10_000, null, 6_000]);
    expect(series.spikes).toEqual([]);
    // The new process's first reading is available at its own instant — the break is not a gap
    // that swallows it.
    expect(memorySampleAt(series, 2_000)?.usedKb).toBe(20_000);
  });

  test("a restart the producer declared breaks the line even when the pid cannot prove it", () => {
    // The pid comparison alone cannot see a restart whose reading carries no pid, and cannot tell a
    // reused pid from the same process. The producer already knows which it is — that is what set
    // `reason` — so the split follows its word as well as the inference. Drawn as one line, the
    // reader is told the app held that memory straight through a restart it did not survive.
    const noPid = buildReplayMemorySeries([
      heap(0, 120_000, { pid: 100 }),
      heap(1_000, 20_000, { reason: "process_restarted", deltaKb: -100_000 }),
      heap(2_000, 26_000, { deltaKb: 6_000 }),
    ], laneT0)!;
    expect(noPid.segments.map((seg) => seg.map((s) => s.atMs))).toEqual([[0], [1_000, 2_000]]);
    expect(noPid.stops).toEqual([1_000]);
    expect(noPid.samples.map((s) => s.deltaKb)).toEqual([null, null, 6_000]);

    // Same, for a replacement process the kernel happened to hand the old pid back.
    const reusedPid = buildReplayMemorySeries([
      heap(0, 120_000, { pid: 100 }),
      heap(1_000, 20_000, { pid: 100, reason: "process_restarted", deltaKb: -100_000 }),
    ], laneT0)!;
    expect(reusedPid.segments.map((seg) => seg.map((s) => s.atMs))).toEqual([[0], [1_000]]);
    expect(reusedPid.stops).toEqual([1_000]);
  });

  test("a reading taken after the last step is found at the end of the axis, where it is drawn", () => {
    // Capture forces a final reading as it stops, which lands past the axis end. The rail draws it
    // at the endpoint because drawing clamps, so scrubbing to the end showed one reading and
    // reported an older one.
    const series = buildReplayMemorySeries([
      heap(0, 40_000, { pid: 9 }),
      heap(1_000, 44_000, { pid: 9 }),
      // The stop reading, 400ms after the trail's last step ended.
      heap(5_400, 52_000, { pid: 9, reason: "stop" }),
    ], laneT0)!;
    const total = 5_000;
    // Unclamped, the end of the axis reports the 1s reading and the 5.4s one is unreachable.
    expect(memorySampleAt(series, total)?.usedKb).toBe(44_000);

    const onAxis = clampMemorySeries(series, total);
    expect(memorySampleAt(onAxis, total)?.usedKb).toBe(52_000);
    // The peak follows it onto the axis, so the marker is not drawn off the end of the rail.
    expect(onAxis.peak.atMs).toBe(total);
    // A series already inside the axis is handed back untouched rather than rebuilt.
    expect(clampMemorySeries(onAxis, total)).toBe(onAxis);
  });

  test("an iOS footprint is a series with no limit, and never reads as near one", () => {
    const series = buildReplayMemorySeries([
      { t: at(0), data: { reason: "first", footprintKb: 60_000, pid: 5 } },
      { t: at(1_000), data: { reason: "after_tool", tool: "tap", footprintKb: 70_000, deltaKb: 10_000 } },
    ], laneT0)!;
    expect(series.kind).toBe("footprint");
    expect(series.limitKb).toBeNull();
    expect(memoryNearLimit(series)).toBe(false);
    expect(describeMemorySample(series, series.samples[1])).toBe("footprint 68.4 MB (+9.8 MB) · after tap");
  });

  test("a reading within a tenth of the heap limit marks the whole rail as near it", () => {
    const calm = buildReplayMemorySeries([heap(0, 100_000)], laneT0)!;
    const pressed = buildReplayMemorySeries([heap(0, 100_000), heap(1_000, 180_000)], laneT0)!;
    expect(memoryNearLimit(calm)).toBe(false);
    expect(memoryNearLimit(pressed)).toBe(true);
  });

  test("each reading is judged against the limit in force when it was taken", () => {
    // The limit can GROW mid-run. A reading at 95% of the then-smaller limit was near its limit,
    // and judging the run's peak against the run's FINAL limit would report it as calm.
    const grew = buildReplayMemorySeries([
      heap(0, 95_000, { heapLimitKb: 100_000 }),
      heap(1_000, 96_000, { heapLimitKb: 400_000 }),
    ], laneT0)!;
    expect(memoryNearLimit(grew)).toBe(true);
    // The named ceiling is still the latest one reported — that is what the line is drawn under.
    expect(grew.limitKb).toBe(400_000);
    // Not every reading names the limit, so a reading that omits it keeps the last one reported
    // rather than becoming a reading with no ceiling at all.
    const omitted = buildReplayMemorySeries([
      heap(0, 40_000, { heapLimitKb: 196_608 }),
      heap(1_000, 180_000, { heapLimitKb: undefined }),
    ], laneT0)!;
    expect(omitted.samples.map((sample) => sample.limitKb)).toEqual([196_608, 196_608]);
    expect(memoryNearLimit(omitted)).toBe(true);
    // A limit of zero is not a limit. Taken at face value every reading would be past it.
    const zero = buildReplayMemorySeries([heap(0, 1_000, { heapLimitKb: 0 })], laneT0)!;
    expect(zero.limitKb).toBeNull();
    expect(memoryNearLimit(zero)).toBe(false);
  });

  test("the readout says only what the reading carries", () => {
    const series = buildReplayMemorySeries([
      heap(0, 40_000, { reason: "before_tool", tool: "tapOnElement", deltaKb: -2_048 }),
      // A reading that names a tool but was not taken at its boundary: the tool is not what this
      // reading is about, so the readout must not claim the reading brackets it.
      heap(1_000, 41_000, { reason: "memory_changed", tool: "tapOnElement", deltaKb: 0 }),
    ], laneT0)!;
    expect(describeMemorySample(series, series.samples[0]))
      .toBe("heap 39.1 MB of 192.0 MB (−2.0 MB) · before tapOnElement");
    // No movement and no boundary: the figure and its ceiling, and nothing invented.
    expect(describeMemorySample(series, series.samples[1])).toBe("heap 40.0 MB of 192.0 MB");
  });

  test("a jump of fifty megabytes in one reading is a spike; smaller movement is not", () => {
    const series = buildReplayMemorySeries([
      heap(0, 40_000, { deltaKb: 0 }),
      heap(1_000, 60_000, { deltaKb: 20_000 }),
      heap(2_000, 120_000, { deltaKb: 60_000, reason: "after_tool", tool: "openGallery" }),
    ], laneT0)!;
    expect(series.spikes.map((s) => s.atMs)).toEqual([2_000]);
    expect(describeMemorySample(series, series.spikes[0])).toBe("heap 117.2 MB of 192.0 MB (+58.6 MB) · after openGallery");
  });

  test("the y-scale is shared across lanes, from the highest peak anywhere", () => {
    const phone = buildReplayMemorySeries([heap(0, 90 * 1024)], laneT0)!;
    const tablet = buildReplayMemorySeries([heap(0, 300 * 1024)], laneT0)!;
    // The tablet's 300 MB sets the scale for both, so the phone's line sits visibly lower — the
    // same height on both rails would read as the same pressure.
    expect(replayMemoryScaleKb([phone, tablet, null])).toBeCloseTo(300 * 1024 * 1.08);
    // No lane with memory at all still yields a safe divisor.
    expect(replayMemoryScaleKb([null, null])).toBe(1);
  });

  test("the polyline puts more memory HIGHER and clamps off-axis instants onto the rail", () => {
    const series = buildReplayMemorySeries([
      heap(-500, 25_000),
      heap(5_000, 50_000),
      heap(12_000, 100_000),
    ], laneT0)!;
    // A 10s axis, a 100_000 kB scale, drawn into a 1000×20 box.
    const points = memoryPoints(series.segments[0], 10_000, 100_000, 1000, 20);
    // -500ms clamps to x=0; 12s clamps to x=1000; 25% of the scale is 5px from the bottom.
    expect(memoryPointsAttr(points)).toBe("0,15 500,10 1000,0");
  });

  test("a segment of one reading is drawn as a mark, not as nothing", () => {
    // A one-point polyline paints no pixels, and a lone reading is the common shape, not an exotic
    // one: an app the trail launches itself, or one that was restarted, starts a segment of one.
    const lone = buildReplayMemorySeries([
      { t: at(1_000), data: { reason: "first", appId: "com.example.app" } },
      heap(5_000, 50_000),
    ], laneT0)!;
    expect(lone.segments.map((seg) => seg.length)).toEqual([1]);
    const points = memoryPoints(lone.segments[0], 10_000, 100_000, 1000, 20);
    expect(points.length).toBe(2);
    expect(points[0].x).toBeLessThan(points[1].x);
    expect(points[0].y).toBe(points[1].y);
  });

  test("memory figures read in the unit the reader thinks in", () => {
    expect(fmtMemoryKb(512)).toBe("512 kB");
    expect(fmtMemoryKb(40_960)).toBe("40.0 MB");
    expect(fmtMemoryKb(2 * 1024 * 1024)).toBe("2.00 GB");
  });
});

describe("aligning the clock by step", () => {
  // A phone that takes 10s on step 1 and 4s on step 2; a tablet that takes 15s and 5s on the
  // same steps. On the wall clock the phone is on step 2 while the tablet is still on step 1.
  const paced = {
    rows: [
      { num: 1, label: "Sign in", cells: [
        cell(0, 10_000, [frame(2_000, "a1")], { actions: [action(9_000)] }),
        cell(0, 15_000, [frame(3_000, "b1")]),
      ] },
      { num: 2, label: "Open menu", cells: [
        cell(10_000, 4_000, [frame(12_000, "a2")]),
        cell(15_000, 5_000, [frame(17_000, "b2")], { ok: false, failureAtMs: 19_000 }),
      ] },
    ],
    maxEndMs: 20_000,
  } as unknown as TrailMatrix;

  test("each step's segment is as wide as the slowest device's take, and both start it together", () => {
    const aligned = alignReplayByStep(buildReplayTimeline(paced));
    expect(aligned.segments.map((s) => [s.num, s.startMs, s.endMs])).toEqual([[1, 0, 15_000], [2, 15_000, 20_000]]);
    expect(aligned.timeline.totalMs).toBe(20_000);
    const [phone, tablet] = aligned.timeline.lanes;
    // The phone's step 2 no longer starts at 10s: it starts where the tablet's does.
    expect(phone.steps.map((s) => [s.startMs, s.endMs])).toEqual([[0, 10_000], [15_000, 19_000]]);
    expect(tablet.steps.map((s) => [s.startMs, s.endMs])).toEqual([[0, 15_000], [15_000, 20_000]]);
    // Everything the phone did inside step 2 moved with it; what it did inside step 1 did not.
    expect(phone.captures.map((c) => c.atMs)).toEqual([2_000, 17_000]);
    expect(phone.events.map((e) => e.atMs)).toEqual([9_000]);
    expect(phone.endMs).toBe(19_000);
    // The tablet is the slowest on both, so it is untouched — including where it died.
    expect(tablet.failure?.atMs).toBe(19_000);
    // The stops with no device followed are the segment starts, plus the death.
    expect(aligned.timeline.boundaries).toEqual([0, 15_000, 19_000, 20_000]);
  });

  test("a fast device waits at the end of a segment, and its own clock holds there", () => {
    const aligned = alignReplayByStep(buildReplayTimeline(paced));
    // Inside its own span the phone's clock is the axis.
    expect(aligned.toLane(0, 5_000)).toBe(5_000);
    expect(aligned.frozenAt(0, 5_000)).toBe(false);
    // Past 10s of segment 1 the phone is done with step 1 and waiting for the tablet: its clock
    // holds at 10s, so a recording would pause on the frame it ended the step on.
    expect(aligned.toLane(0, 12_000)).toBe(10_000);
    expect(aligned.frozenAt(0, 12_000)).toBe(true);
    // At the boundary it picks up step 2 at its own 10s mark.
    expect(aligned.toLane(0, 15_000)).toBe(10_000);
    expect(aligned.toLane(0, 16_000)).toBe(11_000);
    expect(aligned.frozenAt(0, 16_000)).toBe(false);
    // Past its own end it is done, at its own end.
    expect(aligned.toLane(0, 20_000)).toBe(14_000);
    // The two maps invert each other wherever the lane's clock is moving.
    expect(aligned.toAligned(0, aligned.toLane(0, 16_000))).toBe(16_000);
    expect(aligned.toAligned(0, 12_000)).toBe(17_000);
  });

  test("a device that skipped a step waits through its segment", () => {
    const skipped = {
      rows: [
        { num: 1, label: "A", cells: [cell(0, 5_000, []), cell(0, 5_000, [])] },
        // Only the second device ran step 2 (a platform-specific step).
        { num: 2, label: "B", cells: [null, cell(5_000, 6_000, [])] },
        { num: 3, label: "C", cells: [cell(5_000, 2_000, []), cell(11_000, 2_000, [])] },
      ],
      maxEndMs: 13_000,
    } as unknown as TrailMatrix;
    const aligned = alignReplayByStep(buildReplayTimeline(skipped));
    expect(aligned.segments.map((s) => [s.num, s.startMs, s.endMs])).toEqual([[1, 0, 5_000], [2, 5_000, 11_000], [3, 11_000, 13_000]]);
    const first = aligned.timeline.lanes[0];
    // Its step 3 lands in segment 3, not right after its step 1.
    expect(first.steps.map((s) => [s.num, s.startMs])).toEqual([[1, 0], [3, 11_000]]);
    // Through segment 2 it holds at the start of its own step 3 (which is also the end of its step 1 span).
    expect(aligned.toLane(0, 8_000)).toBe(5_000);
    expect(aligned.frozenAt(0, 8_000)).toBe(true);
    expect(aligned.toLane(0, 12_000)).toBe(6_000);
  });

  test("a device with no timed step at all is held at its start for the whole axis", () => {
    // One lane's timed step is enough to make the stage alignable, so a device whose cells carry no
    // timing still gets laid on the step axis — with nothing on it. Run on its own clock its
    // recording would play straight through while the other lane is held to the steps, putting
    // screens from an unrelated moment under a heading that names the step the stage is showing.
    const untimed = {
      rows: [{ num: 1, label: "A", cells: [cell(0, 5_000, [frame(1_000, "a1")]), cell(null, null, [])] }],
      maxEndMs: 5_000,
    } as unknown as TrailMatrix;
    const timeline = buildReplayTimeline(untimed);
    expect(replayable(timeline)).toBe(true);
    expect(timeline.lanes[1].steps).toEqual([]);
    const aligned = alignReplayByStep(timeline);
    // The timed lane is unaffected: the axis is its own clock.
    expect(aligned.toLane(0, 3_000)).toBe(3_000);
    expect(aligned.frozenAt(0, 3_000)).toBe(false);
    // The untimed one holds at its own start, and says it is frozen, so its recording pauses there.
    expect([0, 3_000, 5_000].map((a) => aligned.toLane(1, a))).toEqual([0, 0, 0]);
    expect([0, 3_000, 5_000].map((a) => aligned.frozenAt(1, a))).toEqual([true, true, true]);
  });

  test("a device that launched late gets a lead-in segment, so first steps start together", () => {
    const late = {
      rows: [{ num: 1, label: "A", cells: [cell(0, 4_000, [frame(-1_000, "warm")]), cell(3_000, 4_000, [])] }],
      maxEndMs: 7_000,
    } as unknown as TrailMatrix;
    const aligned = alignReplayByStep(buildReplayTimeline(late));
    expect(aligned.segments.map((s) => [s.num, s.startMs, s.endMs])).toEqual([[null, 0, 3_000], [1, 3_000, 7_000]]);
    expect(aligned.timeline.lanes.map((lane) => lane.steps[0].startMs)).toEqual([3_000, 3_000]);
    // The early device's pre-step capture keeps its own (negative) instant; the view clamps it.
    expect(aligned.timeline.lanes[0].captures[0].atMs).toBe(-1_000);
    // Through the lead-in the early device waits at its own zero.
    expect(aligned.toLane(0, 2_000)).toBe(0);
    expect(aligned.toLane(1, 2_000)).toBe(2_000);
  });

  test("the map never runs backwards for a device that joined at a later step", () => {
    // The axis follows the step NUMBERS; a device's own clock does not have to. The device below
    // joins at step 2, whose segment opens at 2s — before its own step 2 starts at 5s. Mapping its
    // pre-step time as-is sends 4s to 4s and then 5s back to 2s, and every reader downstream
    // assumes ascending order: laneStateAt breaks out of its scan on the first instant past t, so
    // the frame after the fold becomes unreachable and the pane reads "no frame".
    const handover = {
      rows: [
        { num: 1, label: "A", cells: [cell(0, 2_000, []), null] },
        { num: 2, label: "B", cells: [cell(2_000, 3_000, [frame(3_000, "a1")]), cell(5_000, 3_000, [frame(4_000, "b0"), frame(6_000, "b1")])] },
      ],
      maxEndMs: 8_000,
    } as unknown as TrailMatrix;
    const aligned = alignReplayByStep(buildReplayTimeline(handover));
    // The joining lane's own clock still only moves forward on the aligned axis.
    const walk = [0, 1_000, 2_000, 3_000, 4_000, 4_999, 5_000, 6_000, 8_000].map((t) => aligned.toAligned(1, t));
    expect(walk).toEqual([...walk].sort((x, y) => x - y));
    // Specifically: everything before its first step is held at that step's segment start, not
    // carried past it.
    expect(aligned.toAligned(1, 4_000)).toBe(2_000);
    expect(aligned.toAligned(1, 5_000)).toBe(2_000);
    // So its captures come out ascending and both remain reachable.
    expect(aligned.timeline.lanes[1].captures.map((c) => c.atMs)).toEqual([2_000, 3_000]);
  });

  // A device that ran step 2 FIRST (0–4s) and step 1 after it (4–6s), as a retry group does: its
  // step numbers disagree with its clock, so the aligned axis — which follows the numbers — puts
  // its later time earlier.
  const retried = {
    rows: [
      { num: 1, label: "Sign in", cells: [cell(4_000, 2_000, [frame(5_000, "late-1")])] },
      { num: 2, label: "Open menu", cells: [cell(0, 4_000, [frame(1_000, "early-2")], { actions: [action(2_000)] })] },
    ],
    maxEndMs: 6_000,
  } as unknown as TrailMatrix;

  test("a device that ran its steps out of authored order still comes out in clock order", () => {
    // buildReplayTimeline sorts by the clock for exactly this, and the aligned axis then reorders
    // those steps back into NUMBER order, so the mapped instants can come out unsorted unless the
    // projection re-sorts. memorySampleAt binary-searches this, and a binary search over unsorted
    // input does not fail, it returns a plausible wrong reading.
    const aligned = alignReplayByStep(buildReplayTimeline(retried));
    const lane = aligned.timeline.lanes[0];
    const ascending = (values: number[]) => expect(values).toEqual([...values].sort((x, y) => x - y));
    ascending(lane.steps.map((step) => step.startMs));
    ascending(lane.captures.map((capture) => capture.atMs));
    ascending(lane.events.map((event) => event.atMs));
    // And the memory series laid on the same axis, which is the one that is binary-searched.
    const series = buildReplayMemorySeries(
      [1_000, 2_000, 5_000].map((t) => ({ t, data: { heapUsedKb: 1_000 + t, pid: 7 } })),
      0,
    ) as ReplayMemorySeries;
    ascending(remapMemorySeries(series, (t) => aligned.toAligned(0, t)).samples.map((sample) => sample.atMs));
  });

  test("a death the axis moves ahead of the reading it followed stops counting against it", () => {
    // Same out-of-order device. A reading during step 2 and the death that followed it during step
    // 1 are 4s apart on the device's clock, and the axis puts the death FIRST because step 1's
    // segment comes first. Held against the reading there, the readout calls the app dead through
    // the whole of step 2 — a step this device spent running.
    const aligned = alignReplayByStep(buildReplayTimeline(retried));
    const series = buildReplayMemorySeries([
      { t: 1_000, data: { heapUsedKb: 4_000, pid: 7 } },
      // The app found gone, during the step that RAN later and is DRAWN earlier.
      { t: 5_000, data: { reason: "after_tool" } },
    ], 0) as ReplayMemorySeries;
    expect(series.samples[0].stopAfterMs).toBe(5_000);
    const moved = remapMemorySeries(series, (t) => aligned.toAligned(0, t));
    expect(moved.samples.map((sample) => sample.atMs)).toEqual([3_000]);
    // The death is not lost: it is drawn where it happened, in step 1's segment.
    expect(moved.stops).toEqual([1_000]);
    // And the reading reads as live at its own place on the axis, and on to the end of it.
    expect(memorySampleAt(moved, 3_000)?.usedKb).toBe(4_000);
    expect(memorySampleAt(moved, 6_000)?.usedKb).toBe(4_000);
  });

  test("a death stamped at its reading's own instant still counts after the axis moves both", () => {
    // A death found at a tool boundary can carry the instant of the reading before it, and that
    // reading is dead from there on. Both instants map to the same place, so only a stop dropped
    // for landing STRICTLY earlier keeps this one — dropping a tie reports the app alive at an
    // instant it was already observed gone.
    const aligned = alignReplayByStep(buildReplayTimeline(retried));
    const series = buildReplayMemorySeries([
      { t: 1_000, data: { heapUsedKb: 4_000, pid: 7 } },
      { t: 1_000, data: { reason: "after_tool" } },
    ], 0) as ReplayMemorySeries;
    expect(series.samples[0].stopAfterMs).toBe(1_000);
    const moved = remapMemorySeries(series, (t) => aligned.toAligned(0, t));
    expect(moved.samples[0]).toMatchObject({ atMs: 3_000, stopAfterMs: 3_000 });
    expect(memorySampleAt(moved, 3_000)).toBeNull();
  });

  test("a step that overran its segment has its bar clamped to the segment's edge", () => {
    // A step's own duration can exceed its span when it overlapped the step that follows it. The
    // bar is drawn from the step's aligned start, so an unclamped duration draws it straight
    // through the next segment — over a step this device had already moved on from.
    const overrun = {
      rows: [
        // 3s of duration, but the next step starts 1s in: the span is 1s.
        { num: 1, label: "A", cells: [cell(0, 3_000, [])] },
        { num: 2, label: "B", cells: [cell(1_000, 1_000, [])] },
      ],
      maxEndMs: 3_000,
    } as unknown as TrailMatrix;
    const aligned = alignReplayByStep(buildReplayTimeline(overrun));
    // Step 1's span is 1s (its successor starts 1s in) even though it claims 3s of duration.
    expect(aligned.segments[0]).toMatchObject({ num: 1, startMs: 0, endMs: 1_000 });
    // So its bar stops at the segment edge rather than running on into step 2's.
    expect(aligned.timeline.lanes[0].steps[0]).toMatchObject({ startMs: 0, endMs: 1_000 });
  });

  test("a step every device finished instantly is still a segment an instant can land in", () => {
    // segmentAt matches on `a < endMs`, so a zero-width segment is one no instant can ever fall in:
    // the axis chip draws at zero width and the reader who lands on it is told they are in its
    // neighbour instead.
    const instant = {
      rows: [
        { num: 1, label: "A", cells: [cell(0, 0, [])] },
        { num: 2, label: "B", cells: [cell(0, 2_000, [])] },
      ],
      maxEndMs: 2_000,
    } as unknown as TrailMatrix;
    const aligned = alignReplayByStep(buildReplayTimeline(instant));
    const first = aligned.segments[0];
    expect(first.endMs).toBeGreaterThan(first.startMs);
    expect(segmentAt(aligned.segments, first.startMs)?.num).toBe(1);
  });

  test("a step its successor started in the same millisecond keeps its own screens", () => {
    // The instant map resolves by clock alone, and a step whose next step starts in the same
    // millisecond has a zero-wide span — so the walk goes straight past it and everything captured
    // during it was filed under the step after it, while its own segment stayed unreachable. Each
    // of these items knows its own step; nothing has to be inferred.
    const instant = {
      rows: [
        { num: 1, label: "A", cells: [cell(0, 0, [frame(0, "a1")], { actions: [action(0)] })] },
        { num: 2, label: "B", cells: [cell(0, 2_000, [frame(1_000, "b1")])] },
      ],
      maxEndMs: 2_000,
    } as unknown as TrailMatrix;
    const aligned = alignReplayByStep(buildReplayTimeline(instant));
    const lane = aligned.timeline.lanes[0];
    const stepOne = aligned.segments.find((segment) => segment.num === 1) as { startMs: number };

    const shot = lane.captures.find((capture) => capture.file === "a1");
    expect(shot?.atMs).toBe(stepOne.startMs);
    expect(segmentAt(aligned.segments, shot?.atMs as number)?.num).toBe(1);
    expect(lane.events[0].atMs).toBe(stepOne.startMs);
    expect(segmentAt(aligned.segments, lane.events[0].atMs)?.num).toBe(1);
    // Step 2's own capture is untouched by the identity placement.
    expect(segmentAt(aligned.segments, lane.captures[1].atMs)?.num).toBe(2);
  });

  test("a failure stamped after a device's last step still fits on the axis", () => {
    // totalMs summed the segment widths, so an instant mapped past the last segment fell off the
    // end: the boundaries filter dropped the jump-to-failure stop and the marker pinned to the
    // right edge, claiming the device died exactly when the trail ended.
    const lateFailure = {
      rows: [
        { num: 1, label: "A", cells: [cell(0, 2_000, [], { ok: false, failureAtMs: 5_000 })] },
      ],
      maxEndMs: 5_000,
    } as unknown as TrailMatrix;
    const aligned = alignReplayByStep(buildReplayTimeline(lateFailure));
    const failureAt = aligned.timeline.lanes[0].failure?.atMs as number;
    expect(failureAt).toBe(5_000);
    expect(aligned.timeline.totalMs).toBeGreaterThanOrEqual(failureAt);
    expect(aligned.timeline.boundaries).toContain(failureAt);
  });

  test("a device that launched late is not frozen while it is still launching", () => {
    // Waiting and launching look the same on the axis and are not: during its own lead-in the
    // device is recording, and freezing the pane there would hold one frame over the whole launch.
    const late = {
      rows: [{ num: 1, label: "A", cells: [cell(0, 4_000, []), cell(3_000, 4_000, [])] }],
      maxEndMs: 7_000,
    } as unknown as TrailMatrix;
    const aligned = alignReplayByStep(buildReplayTimeline(late));
    // Lane 1 launched at 3s, so the lead-in segment is [0, 3_000) and lane 0 has none.
    expect(aligned.frozenAt(1, 1_000)).toBe(false);
    expect(aligned.frozenAt(0, 1_000)).toBe(true);
  });

  test("a device that never ran the first step contributes no lead-in", () => {
    // The lead-in segment exists for launch delay: time a device spent before the trail's first
    // step. A device whose earliest step is a LATER one was not waiting to launch — it was idling
    // through a step it never ran. Counting that as lead-in lays a segment of dead axis and then
    // charges the skipped step its own width after it, shifting every segment.
    const handover = {
      rows: [
        // Only the first device runs step 1; the second joins at step 2, five seconds in.
        { num: 1, label: "A", cells: [cell(0, 5_000, []), null] },
        { num: 2, label: "B", cells: [cell(5_000, 3_000, []), cell(5_000, 3_000, [])] },
      ],
      maxEndMs: 8_000,
    } as unknown as TrailMatrix;
    const aligned = alignReplayByStep(buildReplayTimeline(handover));
    expect(aligned.segments.map((s) => [s.num, s.startMs, s.endMs])).toEqual([[1, 0, 5_000], [2, 5_000, 8_000]]);
    expect(aligned.timeline.totalMs).toBe(8_000);
  });

  test("an aligned instant names the segment it is in, and the ends are clamped onto one", () => {
    const { segments } = alignReplayByStep(buildReplayTimeline(paced));
    expect(segmentAt(segments, 0)?.num).toBe(1);
    expect(segmentAt(segments, 14_999)?.num).toBe(1);
    expect(segmentAt(segments, 15_000)?.num).toBe(2);
    expect(segmentAt(segments, 20_000)?.num).toBe(2);
    expect(segmentAt(segments, -5)?.num).toBe(1);
    expect(segmentAt([], 0)).toBeNull();
  });

  test("a memory series moves onto the aligned axis with every instant it carries", () => {
    const laneT0 = 1_700_000_000_000;
    const series = buildReplayMemorySeries([
      { t: laneT0 + 1_000, data: { reason: "first", heapUsedKb: 100 } },
      { t: laneT0 + 12_000, data: { reason: "after_tool", tool: "tap", heapUsedKb: 60_000, deltaKb: 59_900 } },
      { t: laneT0 + 13_000, data: { reason: "process_died" } },
    ], laneT0)!;
    const aligned = alignReplayByStep(buildReplayTimeline(paced));
    const moved = remapMemorySeries(series, (t) => aligned.toAligned(0, t));
    // 12s into the phone's run is 2s into its step 2, which the aligned axis puts at 17s.
    expect(moved.samples.map((s) => s.atMs)).toEqual([1_000, 17_000]);
    expect(moved.peak.atMs).toBe(17_000);
    expect(moved.spikes.map((s) => s.atMs)).toEqual([17_000]);
    expect(moved.stops).toEqual([18_000]);
    expect(moved.segments.map((seg) => seg.map((s) => s.atMs))).toEqual([[1_000, 17_000]]);
  });

  test("an app that outlived a wait still reads as running through it", () => {
    // The stop a reading carries is on the reading's own clock, so it has to move with it. The
    // phone's clock runs behind the axis for the 5s it waits on the tablet, so a stop left at its
    // lane instant sits earlier than the stretch of axis it belongs to — and every instant in
    // between then reports the app already dead, blanking a rail the device was still filling.
    const laneT0 = 1_700_000_000_000;
    const series = buildReplayMemorySeries([
      { t: laneT0 + 1_000, data: { reason: "first", heapUsedKb: 100 } },
      { t: laneT0 + 12_000, data: { reason: "after_tool", tool: "tap", heapUsedKb: 200 } },
      { t: laneT0 + 13_000, data: { reason: "process_died" } },
    ], laneT0)!;
    const aligned = alignReplayByStep(buildReplayTimeline(paced));
    const moved = remapMemorySeries(series, (t) => aligned.toAligned(0, t));
    // Mid-wait: the phone finished step 1 on its own 10s mark and holds there until 15s. Its latest
    // reading is still the one from 1s, and the app is running.
    expect(aligned.frozenAt(0, 14_000)).toBe(true);
    expect(memorySampleAt(moved, 14_000)?.usedKb).toBe(100);
    // Running at its second reading too, and gone once the stop's own aligned instant lands.
    expect(memorySampleAt(moved, 17_000)?.usedKb).toBe(200);
    expect(moved.stops).toEqual([18_000]);
    expect(memorySampleAt(moved, 18_000)).toBeNull();
  });

  test("a lane's waits are the stretches of axis its own clock never enters", () => {
    const aligned = alignReplayByStep(buildReplayTimeline(paced));
    // The phone is done with step 1 on its own 10s mark and picks step 2 up when the segment opens.
    expect(aligned.waits(0)).toEqual([{ startMs: 10_000, endMs: 15_000 }]);
    // The tablet set both segment widths, so it never waits.
    expect(aligned.waits(1)).toEqual([]);
    expect(aligned.waits(7)).toEqual([]);
    // Past its last step the phone is finished, and `frozenAt` says so — but the map runs on 1:1
    // there, so that stretch is not a wait and a reading taken in it is drawn where it happened.
    expect(aligned.frozenAt(0, 19_500)).toBe(true);
    expect(aligned.toAligned(0, 15_000)).toBe(20_000);
  });

  test("the memory line is held level across a wait instead of climbing through it", () => {
    // 1 MB at the phone's 1s and 12 MB at its 12s, with 5s of waiting in between. Drawn straight
    // between the two mapped points, the rail climbs 9 MB during a wait the device spent standing
    // still — while every other thing on the lane is frozen.
    const laneT0 = 1_700_000_000_000;
    const series = buildReplayMemorySeries([
      { t: laneT0 + 1_000, data: { heapUsedKb: 1_000, pid: 7 } },
      { t: laneT0 + 12_000, data: { heapUsedKb: 12_000, pid: 7 } },
    ], laneT0)!;
    const aligned = alignReplayByStep(buildReplayTimeline(paced));
    const moved = remapMemorySeries(series, (t) => aligned.toAligned(0, t));
    // x is atMs/20 over a 20s axis 1000 wide; y is 12 - MB over a 12 MB scale 12 tall.
    const points = (waits: Array<{ startMs: number; endMs: number }>) =>
      memoryPoints(moved.segments[0], 20_000, 12_000, 1_000, 12, waits);
    // 9 of the pair's 11 seconds of movement had run when the wait began, so the line arrives at
    // 10 MB, holds there from 10s to 15s, and finishes its climb over the 2s it really had left.
    expect(points(aligned.waits(0))).toEqual([
      { x: 50, y: 11 },
      { x: 500, y: 2 },
      { x: 750, y: 2 },
      { x: 850, y: 0 },
    ]);
    // The wall clock has no waits, and is what it always was.
    expect(points([])).toEqual([{ x: 50, y: 11 }, { x: 850, y: 0 }]);
  });

  test("a wait that follows a device's whole climb holds at the level it climbed to", () => {
    // Taking the EARLIER reading's level for the hold would be right here only by accident. This
    // device did all of its growing during its lead-in and then waited: the hold has to sit at the
    // level it had reached, or the rail redraws the climb after the wait it already finished before.
    const late = {
      rows: [{ num: 1, label: "A", cells: [cell(0, 4_000, []), cell(3_000, 4_000, [])] }],
      maxEndMs: 7_000,
    } as unknown as TrailMatrix;
    const aligned = alignReplayByStep(buildReplayTimeline(late));
    // Lane 0 reached its first step 3s before the segment opens, so it waits out that 3s.
    expect(aligned.waits(0)).toEqual([{ startMs: 0, endMs: 3_000 }]);
    const laneT0 = 1_700_000_000_000;
    const series = buildReplayMemorySeries([
      { t: laneT0 - 1_000, data: { heapUsedKb: 1_000, pid: 7 } },
      { t: laneT0, data: { heapUsedKb: 5_000, pid: 7 } },
    ], laneT0)!;
    const moved = remapMemorySeries(series, (t) => aligned.toAligned(0, t));
    // Its second reading is at its own 0, which the axis puts at 3s — so both mapped instants sit
    // on the wait's edges and the whole climb belongs to the 1s of lead-in before it.
    expect(moved.segments[0].map((sample) => sample.atMs)).toEqual([-1_000, 3_000]);
    // So the hold runs at 5 MB from 0s to 3s. The pre-axis reading is drawn at the axis start, as
    // every instant off the axis is, which makes that climb the line's vertical opening stroke.
    expect(memoryPoints(moved.segments[0], 7_000, 5_000, 700, 5, aligned.waits(0)))
      .toEqual([{ x: 0, y: 4 }, { x: 0, y: 0 }, { x: 300, y: 0 }]);
  });

  test("a reading pair a wait swallows whole holds flat and steps at the later reading", () => {
    // Both readings on the edges of one wait: nothing moved between them on the device's own clock,
    // so there is no interval to slope over and the level steps where the second reading is.
    const held = memoryPoints(
      [
        { atMs: 1_000, usedKb: 2_000, limitKb: null, deltaKb: null, reason: "", tool: null, stopAfterMs: null },
        { atMs: 5_000, usedKb: 4_000, limitKb: null, deltaKb: null, reason: "", tool: null, stopAfterMs: null },
      ],
      10_000,
      4_000,
      1_000,
      4,
      [{ startMs: 1_000, endMs: 5_000 }],
    );
    expect(held).toEqual([{ x: 100, y: 2 }, { x: 500, y: 2 }, { x: 500, y: 0 }]);
  });
});

describe("zooming the strip", () => {
  test("a run can be zoomed until one second is REPLAY_STRIP_PX_PER_SECOND wide, and never less than the floor", () => {
    // 10 minutes in an 800px strip: 600 s × REPLAY_STRIP_PX_PER_SECOND px of rails, in viewports.
    expect(replayStripZoomMax(600_000, 800)).toBeCloseTo((600 * REPLAY_STRIP_PX_PER_SECOND) / 800);
    // A 5 s run would fit at 0.75× — the floor keeps the control meaningful.
    expect(replayStripZoomMax(5_000, 800)).toBe(REPLAY_STRIP_MIN_ZOOM_MAX);
    expect(replayStripZoomMax(0, 800)).toBe(REPLAY_STRIP_MIN_ZOOM_MAX);
    expect(replayStripZoomMax(60_000, 0)).toBe(REPLAY_STRIP_MIN_ZOOM_MAX);
    expect(REPLAY_STRIP_PX_PER_SECOND).toBeGreaterThan(0);
  });

  test("at the deepest zoom, two tool calls a tenth of a second apart both get their names", () => {
    const total = 600_000;
    const viewport = 800;
    const rails = replayStripZoomMax(total, viewport) * viewport;
    expect(replayToolLabelRoom(100, total, rails)).toBeGreaterThanOrEqual(REPLAY_STRIP_MIN_LABEL_PX);
    // At the full view the same pair is far too close to name.
    expect(replayToolLabelRoom(100, total, viewport)).toBeLessThan(REPLAY_STRIP_MIN_LABEL_PX);
  });

  test("a days-long trail stops zooming at a width the browser can still lay out", () => {
    // Devices that ran 10,956 minutes apart would want 329M px of rails at full depth.
    const total = 10_956 * 60_000;
    const viewport = 1300;
    const max = replayStripZoomMax(total, viewport);
    expect(max * viewport).toBeLessThanOrEqual(REPLAY_STRIP_MAX_RAILS_PX);
    expect(max).toBeGreaterThan(REPLAY_STRIP_MIN_ZOOM_MAX);
  });

  test("zooming keeps the anchored instant under the same viewport pixel", () => {
    // 1× → 2× about the instant 25% into the run, which sits 200px into an 800px viewport.
    const zoomed = zoomReplayStrip({ s: 1, at: 0 }, 2, 0.25, 200, 800, 8);
    expect(zoomed.s).toBe(2);
    // Rails are now 1600px; the instant is at 400px on them; to stay at viewport x=200 the
    // scroll is 200px, i.e. 200/1600 of the rails.
    expect(zoomed.at * 1600).toBeCloseTo(200);
    // Zooming back out about the same viewport pixel returns to the start.
    const back = zoomReplayStrip(zoomed, 0.5, 0.25, 200, 800, 8);
    expect(back).toEqual({ s: 1, at: 0 });
  });

  test("the scale is clamped to [1, max] and the scroll never shows void past either end", () => {
    expect(zoomReplayStrip({ s: 1, at: 0 }, 0.5, 0.5, 400, 800, 8)).toEqual({ s: 1, at: 0 });
    expect(zoomReplayStrip({ s: 6, at: 0 }, 10, 0.5, 400, 800, 8).s).toBe(8);
    // Anchoring the run's END at the LEFT edge would scroll past the rails' end: clamped to the last page.
    const atEnd = zoomReplayStrip({ s: 1, at: 0 }, 4, 1, 0, 800, 8);
    expect(atEnd.at * 3200).toBeCloseTo(3200 - 800);
    // Anchoring the run's START at the RIGHT edge would scroll before 0: clamped to the first page.
    expect(zoomReplayStrip({ s: 1, at: 0 }, 4, 0, 800, 800, 8).at).toBe(0);
  });

  test("a viewport with no width yields a scroll of 0 rather than NaN", () => {
    expect(zoomReplayStrip({ s: 1, at: 0 }, 2, 0.5, 0, 0, 8)).toEqual({ s: 2, at: 0 });
  });

  test("the view pages to the playhead only when it has left the view", () => {
    // Head inside [scrollLeft, scrollLeft + viewport]: hold still.
    expect(followReplayHead(1000, 800, 1000)).toBeNull();
    expect(followReplayHead(1000, 800, 1800)).toBeNull();
    expect(followReplayHead(1000, 800, 1400)).toBeNull();
    // Head past the right edge: a new page with the head 15% in from the left.
    expect(followReplayHead(1000, 800, 1801)).toBeCloseTo(1801 - 120);
    // Head before the left edge (a seek backwards): same rule, never negative.
    expect(followReplayHead(1000, 800, 50)).toBe(0);
    expect(followReplayHead(1000, 800, 999)).toBeCloseTo(999 - 120);
  });
});

describe("revealing a device's row in a strip whose rows scroll", () => {
  // A 200px view whose top 20px is the sticky ruler; rows are 34px tall.
  test("a row already fully in view below the ruler does not scroll", () => {
    expect(revealReplayRow(0, 200, 20, 20, 34)).toBeNull();
    expect(revealReplayRow(0, 200, 20, 166, 34)).toBeNull();
    expect(revealReplayRow(100, 200, 20, 120, 34)).toBeNull();
  });

  test("a row hidden under the ruler, or above the view, lands just under the ruler", () => {
    expect(revealReplayRow(100, 200, 20, 110, 34)).toBe(90);
    expect(revealReplayRow(300, 200, 20, 20, 34)).toBe(0);
  });

  test("a row below the view, even partly, lands on its bottom edge", () => {
    expect(revealReplayRow(0, 200, 20, 167, 34)).toBe(1);
    expect(revealReplayRow(0, 200, 20, 600, 34)).toBe(434);
  });

  test("a row taller than the space under the ruler shows its top", () => {
    expect(revealReplayRow(0, 60, 20, 300, 50)).toBe(280);
  });
});

describe("zooming the strip to a range", () => {
  test("a dragged range fills the view exactly", () => {
    const z = rangeReplayStrip(0.2, 0.45, 100);
    expect(z.s).toBeCloseTo(4);
    expect(z.at).toBeCloseTo(0.2);
    // Dragged right-to-left is the same range.
    expect(rangeReplayStrip(0.45, 0.2, 100)).toEqual(z);
  });

  test("a range narrower than the deepest zoom is centred in the view, not pinned left", () => {
    const z = rangeReplayStrip(0.5, 0.5001, 10);
    expect(z.s).toBe(10);
    expect(z.at + 0.05).toBeCloseTo(0.50005);
  });

  test("a range past either end of the run stays inside it", () => {
    expect(rangeReplayStrip(-0.2, 0.5, 100)).toEqual({ s: 2, at: 0 });
    const end = rangeReplayStrip(0.9, 1.3, 100);
    expect(end.s).toBeCloseTo(10);
    expect(end.at).toBeCloseTo(0.9);
    expect(rangeReplayStrip(0, 1, 100)).toEqual({ s: 1, at: 0 });
  });
});

describe("the zoomed ruler", () => {
  test("labels thin to keep 64px apart, down to a tenth of a second, with minor ticks between", () => {
    // 10 s across 800px = 80 px/s: a label a second, a minor tick every 200 ms.
    expect(replayRulerStep(10_000, 800)).toEqual({ majorMs: 1000, minorMs: 200 });
    // 10 s across 8000px = 800 px/s: a label every 100 ms.
    expect(replayRulerStep(10_000, 8000)).toEqual({ majorMs: 100, minorMs: 50 });
    // A 15 s step divides into 5 s minors, not 3 s ones.
    expect(replayRulerStep(180_000, 900)).toEqual({ majorMs: 15000, minorMs: 5000 });
    expect(replayRulerStep(10_000, 0).majorMs).toBeGreaterThan(0);
  });

  test("a days-long trail's ruler steps in hours and days, so its labels still stay 64px apart", () => {
    const total = 10_956 * 60_000;
    // The first zoom step on a 1300px strip, and the full view: a handful of labels, not thousands.
    for (const width of [1.5 * 1300, 1300]) {
      const { majorMs, minorMs } = replayRulerStep(total, width);
      expect((majorMs / total) * width).toBeGreaterThanOrEqual(64);
      expect(minorMs).toBeLessThan(majorMs);
      expect(replayTickSeconds(total, width) * 1000 * (width / total)).toBeGreaterThanOrEqual(64);
    }
    const hour = 3_600_000;
    // 64px of a 1950px strip is 6h of this trail; of a 1300px one, 9h, so the next rung up.
    expect(replayRulerStep(total, 1.5 * 1300)).toEqual({ majorMs: 6 * hour, minorMs: hour });
    expect(replayRulerStep(total, 1300)).toEqual({ majorMs: 12 * hour, minorMs: 3 * hour });
    // Squeezed into 100px it needs 4.9 days a label: past the ladder, rounded up to 5 days.
    expect(replayRulerStep(total, 100)).toEqual({ majorMs: 5 * 24 * hour, minorMs: 2.5 * 24 * hour });
    // A 6h run on 1300px needs 17.7 min a label: 20 min, ticked every 5.
    expect(replayRulerStep(6 * hour, 1300)).toEqual({ majorMs: 20 * 60_000, minorMs: 5 * 60_000 });
  });

  test("labels gain a tenth of a second only once they are closer than a second apart", () => {
    expect(fmtRulerClock(61_500, 1000)).toBe("1:02");
    expect(fmtRulerClock(61_500, 500)).toBe("1:01.5");
    expect(fmtRulerClock(200, 100)).toBe("0:00.2");
  });

  test("labels past an hour read as a clock with hours, and hour-apart labels as hours and days", () => {
    expect(fmtRulerClock(3_723_000, 60_000)).toBe("1:02:03");
    expect(fmtRulerClock(6 * 3_600_000, 3_600_000)).toBe("6h");
    expect(fmtRulerClock(30 * 3_600_000, 6 * 3_600_000)).toBe("1d 6h");
    expect(fmtRulerClock(2 * 86_400_000, 86_400_000)).toBe("2d");
  });

  test("the shown span reads in the unit a person would say", () => {
    expect(fmtReplaySpan(850)).toBe("850 ms");
    expect(fmtReplaySpan(4_240)).toBe("4.2 s");
    expect(fmtReplaySpan(50_000)).toBe("50 s");
    expect(fmtReplaySpan(600_000)).toBe("10 min");
    expect(fmtReplaySpan(5 * 3_600_000)).toBe("5 h");
    expect(fmtReplaySpan(10_956 * 60_000)).toBe("8 days");
  });
});
