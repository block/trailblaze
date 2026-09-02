// The Trail map's camera and wire geometry, as pure functions of measured boxes.
//
// The map lays its nodes out in normal flow — cards size to their screenshots — so both the camera
// and the wires are computed from what the browser measured rather than from a precomputed layout.
// Keeping that arithmetic here (and out of the DOM wiring) is what makes it testable: the caller
// measures, these functions decide, the caller applies.

/** A map camera: world-space translate + scale, applied as one CSS transform. */
export interface TrailCamera {
  x: number;
  y: number;
  s: number;
}

/**
 * Legibility floor. Below this a 20-step trail renders as an unreadable ribbon of thumbnails with
 * no visible step text — an overview that can't be read isn't an overview, so zooming out (and
 * fitting) stops here and the reader pans instead.
 */
export const TRAIL_MIN_SCALE = 0.15;
export const TRAIL_MAX_SCALE = 3;
/** Gap kept between the world and the canvas edge, per axis. */
const PAD_X = 16;
const PAD_Y = 12;

export const clampTrailScale = (s: number): number =>
  Math.max(TRAIL_MIN_SCALE, Math.min(TRAIL_MAX_SCALE, s));

export interface TrailFitInput {
  /** Canvas viewport, px. */
  viewW: number;
  viewH: number;
  /** Untransformed world size, px. */
  worldW: number;
  worldH: number;
  /** True when the flow runs left→right (`dir=h`). */
  horizontal: boolean;
  /** True for the Fit button: take in the whole trail. False for the default framing. */
  whole: boolean;
}

/**
 * Where to park the camera.
 *
 * The default framing (`whole: false`) fits the flow's CROSS axis — the full width of one rank of
 * devices when the flow runs down the page, the full height of it when it runs across — so the
 * reader lands reading a step at a legible size.
 *
 * Fit (`whole: true`) takes in both axes, but never past TRAIL_MIN_SCALE: a long trail is simply
 * taller than any legible whole-trail scale, and clamping produces a readable overview to pan
 * rather than a sliver of nothing.
 *
 * Placement follows one rule on each axis: if the scaled world fits the viewport, center it;
 * otherwise anchor it at the start of the chain. That is why an overflowing Fit opens at the
 * trailhead instead of somewhere in the middle of the trail, and why zooming out never strands
 * content against one edge.
 */
export function fitCamera(input: TrailFitInput): TrailCamera {
  const viewW = input.viewW || 1;
  const viewH = input.viewH || 1;
  const worldW = input.worldW || 1;
  const worldH = input.worldH || 1;
  const fitW = (viewW - PAD_X * 2) / worldW;
  const fitH = (viewH - PAD_Y * 2) / worldH;
  const s = clampTrailScale(input.whole
    ? Math.min(fitW, fitH)
    : input.horizontal ? fitH : fitW);
  const place = (view: number, world: number, pad: number) =>
    world * s <= view - pad * 2 ? (view - world * s) / 2 : pad;
  return { x: place(viewW, worldW, PAD_X), y: place(viewH, worldH, PAD_Y), s };
}

/**
 * Zoom about a viewport point: the world point under (px, py) stays under it. Returns the camera
 * unchanged in scale when the factor would push past a clamp — the reader can keep spinning the
 * wheel at the limit without the view drifting.
 */
export function zoomedCamera(cam: TrailCamera, px: number, py: number, factor: number): TrailCamera {
  const s = clampTrailScale(cam.s * factor);
  const ratio = s / cam.s;
  return { x: px - (px - cam.x) * ratio, y: py - (py - cam.y) * ratio, s };
}

/**
 * How much to counter-scale a step hub so its text keeps a readable size as the camera zooms out.
 * Capped, because the hub floats in the gap between two ranks of screenshots and an uncapped
 * inverse would overlap them.
 */
export const HUB_MAX_COUNTER_SCALE = 4;
export const hubCounterScale = (s: number): number =>
  Math.max(1, Math.min(HUB_MAX_COUNTER_SCALE, 1 / (s || 1)));

/** A measured node box, in untransformed world coordinates (offsetLeft/Top/Width/Height). */
export interface WireBox {
  left: number;
  top: number;
  width: number;
  height: number;
}

/** The smallest box holding every given box — how "one step" becomes one rectangle to frame. */
export function unionBox(boxes: WireBox[]): WireBox | null {
  if (!boxes.length) return null;
  const left = Math.min(...boxes.map((b) => b.left));
  const top = Math.min(...boxes.map((b) => b.top));
  const right = Math.max(...boxes.map((b) => b.left + b.width));
  const bottom = Math.max(...boxes.map((b) => b.top + b.height));
  return { left, top, width: right - left, height: bottom - top };
}

/**
 * Frame one step: fit its box (the hub plus that step's device frames) in the viewport, centered.
 *
 * Scale is capped at 1 as well as floored at the legibility minimum — focusing a step is for
 * READING it, and a one-device step blown up past the screenshots' own pixels is just blur. When
 * even the floor can't take the whole rank in, it stays centered: the step's own hub text is in
 * the middle, and it is the anchor the reader stepped to.
 */
export function focusCamera(input: { viewW: number; viewH: number; box: WireBox }): TrailCamera {
  const viewW = input.viewW || 1;
  const viewH = input.viewH || 1;
  const { box } = input;
  const s = Math.max(TRAIL_MIN_SCALE, Math.min(1,
    (viewW - PAD_X * 2) / (box.width || 1),
    (viewH - PAD_Y * 2) / (box.height || 1)));
  return {
    x: (viewW - box.width * s) / 2 - box.left * s,
    y: (viewH - box.height * s) / 2 - box.top * s,
    s,
  };
}

/**
 * A camera partway between two framings, for animating a step-to-step jump. Position interpolates
 * linearly; scale interpolates GEOMETRICALLY, because zoom is perceived as a ratio — a linear scale
 * ramp spends most of its time zoomed in and then lurches out at the end. `t` arrives already
 * eased; this only blends.
 */
export function tweenCamera(from: TrailCamera, to: TrailCamera, t: number): TrailCamera {
  if (t <= 0) return from;
  if (t >= 1) return to;
  return {
    x: from.x + (to.x - from.x) * t,
    y: from.y + (to.y - from.y) * t,
    s: from.s * Math.pow(to.s / (from.s || 1), t),
  };
}

/** One step's device frames. A step a device never reached contributes no box — and so no wire. */
export interface WireHub {
  hub: WireBox;
  frames: Array<{ box: WireBox; failed?: boolean }>;
}

export interface WireGraph {
  /** The trail's start card, or null when there isn't one. */
  start: WireBox | null;
  hubs: WireHub[];
}

export interface WirePath {
  /** SVG path `d`. */
  d: string;
  /** True when the wire touches a failed frame, so the drawing can say so. */
  failed: boolean;
}

// Wires leave the trailing edge of the flow (bottom when vertical, right when horizontal) and
// enter the next node's leading edge, a few px short so the arrowhead sits off the border.
const ARROW_GAP = 5;

const curve = (from: WireBox, to: WireBox, horizontal: boolean): string => {
  const centerX = (b: WireBox) => b.left + b.width / 2;
  const centerY = (b: WireBox) => b.top + b.height / 2;
  const x1 = horizontal ? from.left + from.width : centerX(from);
  const y1 = horizontal ? centerY(from) : from.top + from.height;
  const x2 = horizontal ? to.left - ARROW_GAP : centerX(to);
  const y2 = horizontal ? centerY(to) : to.top - ARROW_GAP;
  const sag = Math.max(18, (horizontal ? x2 - x1 : y2 - y1) * 0.5);
  return horizontal
    ? `M ${x1} ${y1} C ${x1 + sag} ${y1}, ${x2 - sag} ${y2}, ${x2} ${y2}`
    : `M ${x1} ${y1} C ${x1} ${y1 + sag}, ${x2} ${y2 - sag}, ${x2} ${y2}`;
};

/**
 * Every wire of the fan-out / fan-in flow: start → first hub, each hub → its device frames, and
 * each frame → the next hub. A device that never reached a step has no frame there, so its chain
 * visibly stops feeding the flow rather than skipping the gap.
 */
export function wirePlan(graph: WireGraph, horizontal: boolean): WirePath[] {
  const paths: WirePath[] = [];
  if (graph.start && graph.hubs.length) {
    paths.push({ d: curve(graph.start, graph.hubs[0].hub, horizontal), failed: false });
  }
  graph.hubs.forEach((entry, i) => {
    const next = graph.hubs[i + 1];
    entry.frames.forEach((frame) => {
      paths.push({ d: curve(entry.hub, frame.box, horizontal), failed: Boolean(frame.failed) });
      if (next) paths.push({ d: curve(frame.box, next.hub, horizontal), failed: Boolean(frame.failed) });
    });
  });
  return paths;
}
