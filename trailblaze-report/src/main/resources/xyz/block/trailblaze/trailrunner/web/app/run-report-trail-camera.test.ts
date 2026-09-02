// Behavioral contracts for the Trail map's camera and wire geometry. The map measures its nodes
// after layout and hands the numbers to these functions, so everything the reader experiences as
// framing — what Fit shows, where zoom lands, which nodes are joined — is decided here.
import { describe, expect, test } from "bun:test";
import {
  clampTrailScale,
  fitCamera,
  focusCamera,
  hubCounterScale,
  TRAIL_MAX_SCALE,
  TRAIL_MIN_SCALE,
  tweenCamera,
  unionBox,
  wirePlan,
  zoomedCamera,
  type WireBox,
} from "./run-report-trail-camera";

const box = (left: number, top: number, width = 100, height = 60): WireBox => ({ left, top, width, height });
// A canvas the size of a laptop viewport, and a trail far taller than it — the shape every real
// multi-step trail has.
const VIEW = { viewW: 1440, viewH: 800 };

describe("the map camera's framing", () => {
  test("the default framing fills the viewport with one rank of devices, anchored at the trailhead", () => {
    // Vertical flow: the cross axis is width, so the whole rank of device cards spans the canvas
    // and the tall column starts at the top rather than centered on its middle.
    const cam = fitCamera({ ...VIEW, worldW: 2800, worldH: 16000, horizontal: false, whole: false });
    expect(cam.s).toBeCloseTo((1440 - 32) / 2800, 5);
    expect(cam.y).toBe(12);
    // Horizontal flow pivots the same rule onto the other axis.
    const across = fitCamera({ ...VIEW, worldW: 16000, worldH: 2800, horizontal: true, whole: false });
    expect(across.s).toBeCloseTo((800 - 24) / 2800, 5);
    expect(across.x).toBe(16);
  });

  test("Fit never shrinks a long trail past the point where its steps can be read", () => {
    // A 20-step trail is ~16000px tall: fitting it honestly would be ~5%, an unreadable ribbon.
    const cam = fitCamera({ ...VIEW, worldW: 2800, worldH: 16000, horizontal: false, whole: true });
    expect(cam.s).toBe(TRAIL_MIN_SCALE);
    // And what it can't fit, it opens at the start of — not stranded in the middle of the trail.
    expect(cam.y).toBe(12);
    expect(16000 * cam.s).toBeGreaterThan(800);
  });

  test("Fit centers a trail that genuinely fits, on both axes", () => {
    const cam = fitCamera({ ...VIEW, worldW: 1000, worldH: 400, horizontal: false, whole: true });
    // Both axes are taken in, so the tighter of the two decides the scale — here, width.
    expect(cam.s).toBeCloseTo((1440 - 32) / 1000, 5);
    expect(cam.x).toBeCloseTo((1440 - 1000 * cam.s) / 2, 5);
    expect(cam.y).toBeCloseTo((800 - 400 * cam.s) / 2, 5);
  });

  test("scale is clamped to a legible band, and a zero-sized world never yields NaN", () => {
    expect(clampTrailScale(0.001)).toBe(TRAIL_MIN_SCALE);
    expect(clampTrailScale(99)).toBe(TRAIL_MAX_SCALE);
    const cam = fitCamera({ viewW: 0, viewH: 0, worldW: 0, worldH: 0, horizontal: false, whole: true });
    expect(Number.isFinite(cam.s)).toBe(true);
    expect(Number.isFinite(cam.x)).toBe(true);
    expect(Number.isFinite(cam.y)).toBe(true);
  });
});

describe("zooming the map", () => {
  test("the world point under the cursor stays under the cursor", () => {
    const cam = { x: 120, y: -400, s: 0.5 };
    const zoomed = zoomedCamera(cam, 700, 300, 1.6);
    // Where that viewport point sits in world coordinates, before and after.
    const worldBefore = { x: (700 - cam.x) / cam.s, y: (300 - cam.y) / cam.s };
    const worldAfter = { x: (700 - zoomed.x) / zoomed.s, y: (300 - zoomed.y) / zoomed.s };
    expect(worldAfter.x).toBeCloseTo(worldBefore.x, 5);
    expect(worldAfter.y).toBeCloseTo(worldBefore.y, 5);
    expect(zoomed.s).toBeCloseTo(0.8, 5);
  });

  test("at the zoom limit the view holds still instead of drifting", () => {
    const atFloor = { x: 40, y: 40, s: TRAIL_MIN_SCALE };
    expect(zoomedCamera(atFloor, 700, 300, 0.5)).toEqual(atFloor);
    const atCeiling = { x: 40, y: 40, s: TRAIL_MAX_SCALE };
    expect(zoomedCamera(atCeiling, 700, 300, 2)).toEqual(atCeiling);
  });

  test("step hubs are counter-scaled as the camera pulls back, up to a cap", () => {
    // At or above 1:1 the hub is left alone; pulling back grows it so its text holds its size.
    expect(hubCounterScale(1)).toBe(1);
    expect(hubCounterScale(2)).toBe(1);
    expect(hubCounterScale(0.5)).toBeCloseTo(2, 5);
    // Capped: an uncapped inverse would swell the hub over the screenshots it sits between.
    expect(hubCounterScale(TRAIL_MIN_SCALE)).toBe(4);
  });
});

describe("stepping the map with the arrow keys", () => {
  test("one step becomes one rectangle: the union of its hub and every device's frame", () => {
    const union = unionBox([box(400, 100, 200, 40), box(0, 200, 300, 500), box(700, 200, 300, 480)]);
    expect(union).toEqual({ left: 0, top: 100, width: 1000, height: 600 });
    // Nothing measured (a step no device reached renders no frames) → nothing to frame.
    expect(unionBox([])).toBeNull();
  });

  test("a focused step is centered and fitted, but never blown past the screenshots' own pixels", () => {
    const cam = focusCamera({ ...VIEW, box: box(0, 3000, 2800, 700) });
    // Width is the tight axis here: (1440 - 32) / 2800.
    expect(cam.s).toBeCloseTo(0.502857, 5);
    // Centered on the step on both axes — the step is the anchor the reader jumped to.
    expect(cam.x).toBeCloseTo((1440 - 2800 * cam.s) / 2 - 0 * cam.s, 5);
    expect(cam.y).toBeCloseTo((800 - 700 * cam.s) / 2 - 3000 * cam.s, 5);
    // A one-device step would fit at 4x; focusing is for READING, so it stops at 1:1.
    expect(focusCamera({ ...VIEW, box: box(0, 0, 300, 150) }).s).toBe(1);
    // And a rank too wide even for the legibility floor still respects that floor.
    expect(focusCamera({ ...VIEW, box: box(0, 0, 20000, 700) }).s).toBe(TRAIL_MIN_SCALE);
  });

  test("the flight between two framings blends position linearly and zoom geometrically", () => {
    const from = { x: 0, y: 0, s: 0.25 };
    const to = { x: 100, y: -300, s: 1 };
    expect(tweenCamera(from, to, 0)).toEqual(from);
    expect(tweenCamera(from, to, 1)).toEqual(to);
    const mid = tweenCamera(from, to, 0.5);
    expect(mid.x).toBeCloseTo(50, 5);
    expect(mid.y).toBeCloseTo(-150, 5);
    // Halfway through a 4x zoom is 2x the start — the geometric midpoint. The linear midpoint
    // (0.625) would spend most of the flight zoomed in and lurch at the end.
    expect(mid.s).toBeCloseTo(0.5, 5);
  });
});

describe("the wires of the fan-out / fan-in flow", () => {
  const graph = {
    start: box(0, 0),
    hubs: [
      { hub: box(0, 100), frames: [{ box: box(0, 200) }, { box: box(200, 200), failed: true }] },
      { hub: box(0, 300), frames: [{ box: box(0, 400) }] },
    ],
  };

  test("joins the start to the first step, each step to its devices, and each device onward", () => {
    const paths = wirePlan(graph, false);
    // start→hub1, hub1→2 frames, both frames→hub2, hub2→its frame. The last rank feeds nothing.
    expect(paths).toHaveLength(6);
    expect(paths.every((p) => /^M [\d.-]+ [\d.-]+ C /.test(p.d))).toBe(true);
  });

  test("a device that never reached a step contributes no wire there", () => {
    const missing = { start: null, hubs: [{ hub: box(0, 100), frames: [] }, { hub: box(0, 300), frames: [{ box: box(0, 400) }] }] };
    // Only the second step's single frame is joined: no start wire, and nothing bridges the gap
    // the unreached step leaves.
    expect(wirePlan(missing, false)).toHaveLength(1);
  });

  test("a wire touching a failed device is marked so the drawing can say so", () => {
    const failed = wirePlan(graph, false).filter((p) => p.failed);
    // Both the fan-out to the failed frame and its merge into the next step.
    expect(failed).toHaveLength(2);
  });

  test("wires leave the trailing edge of the flow, which the orientation decides", () => {
    const down = wirePlan({ start: box(0, 0, 100, 60), hubs: [{ hub: box(0, 200, 100, 60), frames: [] }] }, false);
    // Vertical: out of the bottom edge (y=60) at the node's horizontal center (x=50).
    expect(down[0].d.startsWith("M 50 60 ")).toBe(true);
    const across = wirePlan({ start: box(0, 0, 100, 60), hubs: [{ hub: box(200, 0, 100, 60), frames: [] }] }, true);
    // Horizontal: out of the right edge (x=100) at the node's vertical center (y=30).
    expect(across[0].d.startsWith("M 100 30 ")).toBe(true);
  });
});
