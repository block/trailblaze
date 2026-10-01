// Behavioral contracts for the Timeline's free seek and event tracks. A pixel on the scrubber, a
// mark on a track and the frame on screen must all agree on one instant; these are that agreement.
import { describe, expect, test } from "bun:test";
import { adjacentMark, entryAtOrBefore, fractionToOffset, snapFraction, timelineTracks, trackMarks } from "./run-report-timeline-tracks";

const label = (event: { d?: string }) => `raw ${event.d}`;

describe("trackMarks", () => {
  test("a formatted stream contributes its rows, with a span when the row says where it ends", () => {
    const marks = trackMarks({
      name: "net",
      rows: [
        { t: 2000, endT: 2300, label: "GET /b", tone: "error" },
        { t: 1000, endT: 1142, label: "POST /a", tone: "ok" },
        // An endT at or before t is no interval: the mark stays an instant.
        { t: 3000, endT: 3000, label: "GET /c" },
      ],
    }, label);

    // Drawn in time order, keyed by the row's own position so a click finds the right row.
    expect(marks).toEqual([
      { key: "net-1", t: 1000, endT: 1142, label: "POST /a", tone: "ok" },
      { key: "net-0", t: 2000, endT: 2300, label: "GET /b", tone: "error" },
      { key: "net-2", t: 3000, endT: null, label: "GET /c", tone: null },
    ]);
  });

  test("a raw stream contributes its events, labelled by the caller, and drops the untimed ones", () => {
    const marks = trackMarks({ name: "log", events: [{ t: 5, d: "a" }, { t: null, d: "b" }, { t: 7, d: "c" }] }, label);
    expect(marks.map((mark) => [mark.key, mark.label])).toEqual([["log-0", "raw a"], ["log-2", "raw c"]]);
  });
});

describe("timelineTracks", () => {
  test("lists each stream with a timed event, counting the marks it would draw", () => {
    const streams = [
      { name: "log", events: [{ t: 5, d: "a" }, { t: null, d: "b" }, { t: 7, d: "c" }] },
      { name: "empty", events: [{ t: null, d: "x" }] },
      { name: "net", rows: [{ t: 1, label: "GET /" }] },
    ];
    expect(timelineTracks(streams)).toEqual([{ name: "log", streamIndex: 0, count: 2 }, { name: "net", streamIndex: 2, count: 1 }]);
    // The count is the marks the open track draws, so the name never promises more than the rail.
    expect(timelineTracks(streams)[0].count).toBe(trackMarks(streams[0], label).length);
  });
});

describe("fractionToOffset", () => {
  // Three entries at 0s, 10s and 70s; the axis compresses the 60s gap to the same width as the 10s one.
  const real = [0, 10_000, 70_000];
  const stepFrac = [0, 0.5, 1];

  test("is linear in time between two entries, however compressed the gap", () => {
    expect(fractionToOffset(real, stepFrac, 0.25)).toBe(5_000);
    expect(fractionToOffset(real, stepFrac, 0.75)).toBe(40_000);
  });

  test("lands exactly on an entry at its own position, and clamps past either end", () => {
    expect(fractionToOffset(real, stepFrac, 0.5)).toBe(10_000);
    expect(fractionToOffset(real, stepFrac, -1)).toBe(0);
    expect(fractionToOffset(real, stepFrac, 2)).toBe(70_000);
  });

  test("entries sharing a position resolve to the first of their times", () => {
    expect(fractionToOffset([0, 4_000, 9_000], [0, 1, 1], 1)).toBe(4_000);
  });

  test("has no answer for an empty axis", () => {
    expect(fractionToOffset([], [], 0.5)).toBeNull();
  });
});

describe("snapFraction", () => {
  test("pulls a position onto the nearest keyframe within tolerance", () => {
    expect(snapFraction(0.52, [0.1, 0.5, 0.55], 0.04)).toBe(0.5);
    expect(snapFraction(0.54, [0.1, 0.5, 0.55], 0.04)).toBe(0.55);
  });

  test("leaves a position alone when no keyframe is close", () => {
    expect(snapFraction(0.3, [0.1, 0.5], 0.04)).toBe(0.3);
  });
});

describe("entryAtOrBefore", () => {
  const stepFrac = [0, 0.2, 0.4, 0.6, 0.8];
  const selectable = (i: number) => i !== 0 && i !== 3;

  test("is the last selectable entry at or before the position — the screen still up", () => {
    expect(entryAtOrBefore(stepFrac, selectable, 0.5)).toBe(2);
    expect(entryAtOrBefore(stepFrac, selectable, 0.7)).toBe(2);
    expect(entryAtOrBefore(stepFrac, selectable, 0.8)).toBe(4);
  });

  test("before the first selectable entry, is that first one", () => {
    expect(entryAtOrBefore(stepFrac, selectable, 0.1)).toBe(1);
  });

  test("with nothing selectable, is no entry", () => {
    expect(entryAtOrBefore(stepFrac, () => false, 0.5)).toBe(-1);
  });
});

describe("adjacentMark", () => {
  const marks = ["a", "b", "c"].map((key, t) => ({ key, t, endT: null, label: key, tone: null }));

  test("steps to the neighbour, and stops at either end", () => {
    expect(adjacentMark(marks, "b", 1)?.key).toBe("c");
    expect(adjacentMark(marks, "b", -1)?.key).toBe("a");
    expect(adjacentMark(marks, "c", 1)).toBeNull();
  });

  test("from nothing selected, enters at the end it is heading from", () => {
    expect(adjacentMark(marks, null, 1)?.key).toBe("a");
    expect(adjacentMark(marks, null, -1)?.key).toBe("c");
  });
});
