// Tests for the Compare view's model (run-report-compare-model.ts). The scenarios mirror the
// Kotlin engines' suites (SessionToolDiffTest, SessionEventDiffTest) so the two surfaces are held
// to the same behavior: the same pair of runs must read the same from `trailblaze report diff`
// and from the viewer.
//
// Run: `bun test app/run-report-compare-model.test.ts` from the web/ directory.
import { describe, expect, test } from "bun:test";
import {
  alignedScenes,
  comparableArgLines,
  compareEventStreams,
  compareToolTimelines,
  defaultComparePair,
  diffPixels,
  flatToolRows,
  highlightSpans,
  diffEventContent,
  eventDisplayLines,
  eventLines,
  eventObjectsOf,
  leafStrings,
  lineDiff,
  MAX_LINE_DIFF_CELLS,
  pickGroupPath,
  toolTimelineOf,
  volatileScalarPaths,
} from "./run-report-compare-model";

// A trace row in the slimTraceForShare shape the viewer payload carries. `args` is the
// deterministic trail-YAML string run-report-payload's jsonToYaml emits for the call.
function toolRow(i: number, label: string, argsYaml: string | null = null, ok = true, extra: Record<string, unknown> = {}) {
  return { i, label, tool: "", note: null, ms: 0, ts: null, ok, err: null, screenshotFile: null, objective: false, terminal: false, ...(argsYaml ? { args: argsYaml } : {}), ...extra } as any;
}

const argsOf = (toolName: string, body: string[]) => `- ${toolName}:\n${body.map((l) => `    ${l}`).join("\n")}`;

describe("tool timeline selection", () => {
  test("keeps executed calls, drops objective / terminal / LLM rows", () => {
    const trace = [
      toolRow(0, "Add an item to the cart", null, true, { objective: true }),
      toolRow(1, "Tap the first item", null, true, { llm: 0, tool: "llm · claude" }),
      toolRow(2, "tapOnElementBySelector", argsOf("tapOnElementBySelector", ["selector:", "  id: item_1"])),
      toolRow(3, "Session ended", null, true, { terminal: true }),
    ];
    expect(toolTimelineOf(trace).map((t: any) => t.label)).toEqual(["tapOnElementBySelector"]);
  });

  test("comparable arg lines drop the tool-name head, dedent, and remove run-scoped fields", () => {
    const lines = comparableArgLines(argsOf("tap", ['ref: e149', 'reasoning: "step one"', "reason: tapping the item", "x: 1", "selector:", "  ref: d21", "  id: search"]));
    expect(lines).toEqual(["x: 1", "selector:", "  id: search"]);
  });
});

describe("tool-call lane (mirrors SessionToolDiffTest)", () => {
  test("identical timelines are all same", () => {
    const run = () => [
      toolRow(0, "launchApp", argsOf("launchApp", ["appId: a"])),
      toolRow(1, "tap", argsOf("tap", ["x: 1"])),
    ];
    const result = compareToolTimelines(run(), run());
    expect(result.sameCount).toBe(2);
    expect(result.rows.every((r) => r.status === "same")).toBe(true);
    expect(result.summary).toBe("Tool calls: 2 identical");
  });

  test("a changed argument is reported line by line", () => {
    const result = compareToolTimelines(
      [toolRow(0, "inputText", argsOf("inputText", ["text: Coffee", "selector:", "  id: search"]))],
      [toolRow(0, "inputText", argsOf("inputText", ["text: Bagel", "selector:", "  id: search"]))],
    );
    expect(result.rows[0].status).toBe("args_changed");
    // The pair carries the differing span — 'Coffee'/'Bagel' after the shared 'text: ' prefix —
    // and the call's unchanged arguments print around it as context, anchoring the change.
    expect(result.rows[0].changes).toEqual([
      { sign: "-", text: "text: Coffee", hi: [6, 12] },
      { sign: "+", text: "text: Bagel", hi: [6, 11] },
      { sign: " ", text: "selector:" },
      { sign: " ", text: "  id: search" },
    ]);
  });

  // The mark's offsets are sliced into separate text and <mark> nodes, so a boundary that falls
  // between the two code units of an astral character hands each node half of it and both render
  // as a replacement glyph — the reader sees mojibake where the changed character should be.
  test("a changed emoji is marked whole, not split down the middle", () => {
    const result = compareToolTimelines(
      [toolRow(0, "inputText", argsOf("inputText", ['text: "😀"']))],
      [toolRow(0, "inputText", argsOf("inputText", ['text: "😁"']))],
    );
    const marked = (sign: string) => {
      const line = result.rows[0].changes.find((c: any) => c.sign === sign) as any;
      return line.text.slice(line.hi[0], line.hi[1]);
    };
    expect(marked("-")).toBe("😀");
    expect(marked("+")).toBe("😁");
  });

  // Ordered arguments — a `commands:` list, a swipe's points — execute in the order written, so a
  // reorder changes behavior even though every line survives.
  test("reordered argument lines are a change, not an identical multiset", () => {
    const result = compareToolTimelines(
      [toolRow(0, "runFlow", argsOf("runFlow", ["commands:", "  - tapOn: A", "  - tapOn: B"]))],
      [toolRow(0, "runFlow", argsOf("runFlow", ["commands:", "  - tapOn: B", "  - tapOn: A"]))],
    );
    expect(result.rows[0].status).toBe("args_changed");
    expect(result.rows[0].changes[0]).toEqual({ sign: " ", text: "argument order changed" });
    // The report names the moved entries, not the surrounding mapping lines.
    expect(result.rows[0].changes.slice(1)).toEqual([
      { sign: "-", text: "  - tapOn: A" },
      { sign: "-", text: "  - tapOn: B" },
      { sign: "+", text: "  - tapOn: B" },
      { sign: "+", text: "  - tapOn: A" },
    ]);
  });

  // Mapping keys have no order: jsonToYaml emits them in insertion order, so the same argument
  // object can serialize either way run to run. Calling that a change would flag every such pair.
  test("mapping keys serialized in a different order are not a change", () => {
    const result = compareToolTimelines(
      [toolRow(0, "inputText", argsOf("inputText", ["text: Coffee", "timeout: 5"]))],
      [toolRow(0, "inputText", argsOf("inputText", ["timeout: 5", "text: Coffee"]))],
    );
    expect(result.rows[0].status).toBe("same");
    expect(result.rows[0].changes).toEqual([]);
  });

  // A turn row stands for its first tool and folds the rest of the turn in as children; comparing
  // parents alone calls two runs identical whenever they open a turn the same way and then diverge.
  test("a folded turn's children are compared, not just the tool that opened it", () => {
    const turn = (second: string) => [toolRow(0, "launchApp", argsOf("launchApp", ["appId: a"]), true, {
      children: [
        { label: "tapOnElementBySelector", tool: "", args: argsOf("tapOnElementBySelector", ["id: search"]) },
        { label: second, tool: "", args: argsOf(second, ["text: Coffee"]) },
      ],
    })];
    expect(flatToolRows(turn("inputText") as any).map((r) => r.label))
      .toEqual(["launchApp", "tapOnElementBySelector", "inputText"]);
    const result = compareToolTimelines(turn("inputText"), turn("eraseText"));
    expect(result.sameCount).toBe(2); // launchApp and the shared tap
    expect(result.baselineOnlyCount).toBe(1);
    expect(result.currentOnlyCount).toBe(1);
    // A child deep-links to the parent row that expands to it.
    expect(result.rows[result.rows.length - 1].currentStep).toBe(0);
  });

  test("a child's fold count is compared, so running a tap twice reads as a change", () => {
    const turn = (count: number) => [toolRow(0, "launchApp", argsOf("launchApp", ["appId: a"]), true, {
      children: [{ label: "tapOnElementBySelector", tool: "", args: argsOf("tapOnElementBySelector", ["id: search"]), count }],
    })];
    const result = compareToolTimelines(turn(1), turn(3));
    expect(result.argsChangedCount).toBe(1);
    expect(result.rows[1].changes).toEqual([
      { sign: " ", text: "id: search" },
      { sign: "+", text: "× 3" },
    ]);
  });

  // The parent row folds too — a polled assertion that retried three times is one row with
  // count: 3 — and omitting it made those two runs produce byte-identical rows.
  test("a parent's own fold count is compared, so a polled assertion that retried reads as a change", () => {
    const polled = (count: number) => [toolRow(0, "assertVisible", argsOf("assertVisible", ["text: Done"]), true, { count })];
    const result = compareToolTimelines(polled(1), polled(3));
    expect(result.argsChangedCount).toBe(1);
    expect(result.rows[0].changes).toEqual([
      { sign: " ", text: "text: Done" },
      { sign: "+", text: "× 3" },
    ]);
  });

  test("run-scoped fields — reasoning and element refs — are not a change", () => {
    const result = compareToolTimelines(
      [toolRow(0, "tap", argsOf("tap", ["ref: e149", "reason: I will tap the first item", 'reasoning: "step one"']))],
      [toolRow(0, "tap", argsOf("tap", ["ref: d221", "reason: Tapping the item at the top", 'reasoning: "first step"']))],
    );
    expect(result.rows[0].status).toBe("same");
  });

  test("an inserted call does not shift later comparisons off by one", () => {
    const result = compareToolTimelines(
      [
        toolRow(0, "launchApp", argsOf("launchApp", ["appId: a"])),
        toolRow(1, "assertVisible", argsOf("assertVisible", ["text: Done"])),
      ],
      [
        toolRow(0, "launchApp", argsOf("launchApp", ["appId: a"])),
        toolRow(1, "tap", argsOf("tap", ["x: 1"])), // inserted — index pairing would diff tap vs assertVisible
        toolRow(2, "assertVisible", argsOf("assertVisible", ["text: Done"])),
      ],
    );
    expect(result.sameCount).toBe(2);
    const inserted = result.rows.filter((r) => r.status === "current_only");
    expect(inserted.map((r) => r.toolName)).toEqual(["tap"]);
    // A call only the current run made is wholly added, so every line of it carries a `+`.
    expect(inserted[0].changes).toEqual([{ sign: "+", text: "x: 1" }]);
    expect(inserted[0].currentStep).toBe(1);
    expect(inserted[0].baselineStep).toBeNull();
  });

  test("a removed call is reported as baseline-only", () => {
    const result = compareToolTimelines(
      [toolRow(0, "launchApp", argsOf("launchApp", ["appId: a"])), toolRow(1, "wait", argsOf("wait", ["ms: 500"]))],
      [toolRow(0, "launchApp", argsOf("launchApp", ["appId: a"]))],
    );
    const removed = result.rows.filter((r) => r.status === "baseline_only");
    expect(removed.map((r) => r.toolName)).toEqual(["wait"]);
  });

  test("an outcome flip is reported even when arguments match", () => {
    const result = compareToolTimelines(
      [toolRow(0, "assertVisible", argsOf("assertVisible", ["text: Done"]), true)],
      [toolRow(0, "assertVisible", argsOf("assertVisible", ["text: Done"]), false)],
    );
    expect(result.rows[0].status).toBe("outcome_changed");
    expect(result.rows[0].changes).toEqual([
      { sign: "-", text: "outcome: succeeded", hi: [9, 18] },
      { sign: "+", text: "outcome: FAILED", hi: [9, 15] },
      { sign: " ", text: "text: Done" },
    ]);
  });

  // Label alone cannot tell repeated calls apart: pairing tap-by-tap in order makes the two
  // unchanged taps look changed and the real last tap look added — three wrong rows for one insert.
  test("an inserted repeat of an existing call is the only difference reported", () => {
    const tap = (i: number, text: string) => toolRow(i, "tap", argsOf("tap", [`text: ${text}`]));
    const result = compareToolTimelines(
      [tap(0, "A"), tap(1, "B")],
      [tap(0, "X"), tap(1, "A"), tap(2, "B")],
    );
    expect(result.sameCount).toBe(2);
    expect(result.argsChangedCount).toBe(0);
    const inserted = result.rows.filter((r) => r.status === "current_only");
    expect(inserted.map((r) => r.changes)).toEqual([[{ sign: "+", text: "text: X" }]]);
  });

  // Context is a window, not the whole call: a long argument list prints only the lines near the
  // change, and the agreement further out folds to a dim gap line — GitHub's 3-line-context idea.
  test("unchanged arguments far from the change fold into a gap line", () => {
    const args = (text: string) => argsOf("fill", ["a: 1", "b: 2", "c: 3", "d: 4", "e: 5", "f: 6", `text: ${text}`]);
    const result = compareToolTimelines([toolRow(0, "fill", args("X"))], [toolRow(0, "fill", args("Y"))]);
    expect(result.rows[0].changes).toEqual([
      { sign: " ", text: "⋯ 4 matching argument lines", gap: true },
      { sign: " ", text: "e: 5" },
      { sign: " ", text: "f: 6" },
      { sign: "-", text: "text: X", hi: [6, 7] },
      { sign: "+", text: "text: Y", hi: [6, 7] },
    ]);
  });

  // A pure insertion inside a value has nothing to mark on the shorter line: highlighting a
  // zero-width span draws an empty box, so only the side with a differing middle carries `hi`.
  test("an inserted span highlights only the side that has one", () => {
    const result = compareToolTimelines(
      [toolRow(0, "tap", argsOf("tap", ["text: AB"]))],
      [toolRow(0, "tap", argsOf("tap", ["text: AxB"]))],
    );
    const [removed, added] = result.rows[0].changes;
    expect(removed).toEqual({ sign: "-", text: "text: AB" });
    // The inserted `x` is marked as part of the value it landed in, not on its own: `A«x»B` reads
    // as three values where the change is one.
    expect(added.text.slice(added.hi![0], added.hi![1])).toBe("AxB");
  });

  // The counterweight: a name-only pairing must still beat no pairing, or a call whose arguments
  // genuinely changed would report as a removal plus an addition instead of args_changed.
  test("a repeated call whose arguments changed still pairs up", () => {
    const tap = (i: number, text: string) => toolRow(i, "tap", argsOf("tap", [`text: ${text}`]));
    const result = compareToolTimelines([tap(0, "A"), tap(1, "B")], [tap(0, "A"), tap(1, "CHANGED")]);
    expect(result.sameCount).toBe(1);
    expect(result.baselineOnlyCount).toBe(0);
    expect(result.currentOnlyCount).toBe(0);
    expect(result.rows[1].changes).toEqual([
      { sign: "-", text: "text: B", hi: [6, 7] },
      { sign: "+", text: "text: CHANGED", hi: [6, 13] },
    ]);
  });
});

describe("the changed-word mark", () => {
  // Slice with the returned span, so these read as "what does the reader see marked" rather than
  // as offsets — offsets shift whenever the surrounding text does.
  const marks = (x: string, y: string) => {
    const [xSpan, ySpan] = highlightSpans(x, y);
    return [xSpan && x.slice(...xSpan), ySpan && y.slice(...ySpan)];
  };

  // The case that sent a reader digging: two amounts that happen to share a leading digit. Marking
  // what is left after the shared characters says a fragment was replaced, and `1«450»` reads as
  // though the front of the number were cut off rather than part of the old value.
  test("marks whole values, not the characters two values happen to share", () => {
    expect(marks("amount_cents=1450", "amount_cents=1625")).toEqual(["1450", "1625"]);
    expect(marks("on=true", "on=false")).toEqual(["true", "false"]);
    expect(marks("price: 1000", "price: 999")).toEqual(["1000", "999"]);
  });

  test("a value's quotes and its key stay outside the mark", () => {
    expect(marks('item="Coffee"', 'item="Bagel"')).toEqual(["Coffee", "Bagel"]);
    expect(marks("text: Coffee", "text: Bagel")).toEqual(["Coffee", "Bagel"]);
  });

  // A URL's path segments are separate values to a reader scanning for which one moved.
  test("a path marks only the segment that moved", () => {
    expect(marks("url=/v2/inventory/sync", "url=/v2/catalog/sync")).toEqual(["inventory", "catalog"]);
  });

  // Past the token cap the offset IS the information: a reader comparing two long opaque strings
  // needs to see where they part, and painting the whole of both says only "these differ", which
  // the −/+ gutters already said.
  test("a long token keeps the precise span instead of swallowing the line", () => {
    const [x, y] = [`blob=${"z".repeat(80)}A${"z".repeat(80)}`, `blob=${"z".repeat(80)}B${"z".repeat(80)}`];
    expect(marks(x, y)).toEqual(["A", "B"]);
  });

  // Both sides fall back together — one line marking a whole value while the other marked a
  // fragment would read as two different kinds of change. So a short value paired against a long
  // one stays precise too, even though on its own it would have grown to `1450`.
  test("a long token on one side keeps both sides precise", () => {
    const blob = "z".repeat(60);
    expect(marks("v=1450 tail", `v=1${blob} tail`)).toEqual(["450", blob]);
  });

  // Accepted limitation, pinned so it is changed deliberately rather than by accident: one span per
  // side cannot mark two separated fields, so a two-field change marks everything between them —
  // the same thing a git word-diff does. The expanded row's per-field ± lines carry the detail.
  test("two fields changing at once mark as one span across both", () => {
    expect(marks("a=1 b=2", "a=9 b=8")).toEqual(["1 b=2", "9 b=8"]);
  });
});

// EventStream payloads in the shapes the viewer carries.
const genericStream = (name: string, payloads: unknown[]) => ({
  name,
  total: payloads.length,
  truncated: false,
  events: payloads.map((data, i) => ({ t: 1000 + i, d: JSON.stringify(data) })),
});
const analyticsEvent = (event: string, id: string) => ({ columnItems: { Event: event, Time: id, Properties: "{}" } });

describe("event-streams lane (mirrors SessionEventDiffTest)", () => {
  test("groups a stream by the enum-ish field, never a unique-per-event field", () => {
    const result = compareEventStreams(
      { events: [genericStream("analytics", [analyticsEvent("Tap", "t1"), analyticsEvent("Tap", "t2"), analyticsEvent("View", "t3"), analyticsEvent("View", "t4")])] },
      { events: [genericStream("analytics", [analyticsEvent("Tap", "t5"), analyticsEvent("Tap", "t6"), analyticsEvent("Tap", "t7"), analyticsEvent("View", "t8")])] },
    );
    const stream = result.streams[0];
    expect(stream.groupPath).toBe("columnItems.Event");
    expect(stream.baselineCount).toBe(4);
    expect(stream.currentCount).toBe(4);
    expect(stream.changed).toBe(true); // same total but a different mix must still read as changed
    expect(stream.groups.map((g) => [g.key, g.baselineCount, g.currentCount])).toEqual([
      ["Tap", 2, 3],
      ["View", 2, 1],
    ]);
  });

  test("a stream captured by only one run diffs against zero", () => {
    const result = compareEventStreams(
      { events: [] },
      { events: [genericStream("userjourneys", [{ journeyName: "checkout" }, { journeyName: "checkout" }])] },
    );
    const stream = result.streams[0];
    expect(stream.baselineCount).toBe(0);
    expect(stream.currentCount).toBe(2);
    expect(stream.delta).toBe(2);
  });

  test("excluded streams are left out entirely", () => {
    const result = compareEventStreams(
      { events: [genericStream("logging", [{ level: "INFO" }]), genericStream("analytics", [analyticsEvent("Tap", "t1")])] },
      { events: [genericStream("logging", [{ level: "INFO" }, { level: "WARN" }]), genericStream("analytics", [analyticsEvent("Tap", "t2")])] },
      new Set(["logging"]),
    );
    expect(result.streams.map((s) => s.stream)).toEqual(["analytics"]);
  });

  test("no qualifying grouping field falls back to counts only", () => {
    // Every field is unique per event — ids and timestamps must never become the grouping key.
    const events = Array.from({ length: 6 }, (_, i) => leafStrings({ id: `unique-${i}` }));
    expect(pickGroupPath(events.slice(0, 3), events.slice(3))).toBeNull();
  });

  test("a field whose runs share no value partitions the runs, not the events, so it never groups", () => {
    // A per-run session/trace id repeats within a run but the two runs' values are disjoint.
    const run = (traceId: string) => Array.from({ length: 4 }, () => leafStrings({ traceId }));
    expect(pickGroupPath(run("run-a-trace"), run("run-b-trace"))).toBeNull();
  });

  test("changed streams sort before unchanged ones", () => {
    const result = compareEventStreams(
      { events: [genericStream("stable", [{ kind: "a" }]), genericStream("busy", [{ kind: "a" }])] },
      { events: [genericStream("stable", [{ kind: "a" }]), genericStream("busy", [{ kind: "a" }, { kind: "b" }, { kind: "c" }])] },
    );
    expect(result.streams.map((s) => s.stream)).toEqual(["busy", "stable"]);
    expect(result.summary).toContain("busy (1→3)");
  });

  test("a formatter-rendered stream groups by its row labels", () => {
    const rowsStream = (labels: string[]) => ({ name: "net.log", total: labels.length, truncated: false, events: [], rows: labels.map((label) => ({ t: null, label })) });
    const result = compareEventStreams(
      { events: [rowsStream(["GET /v1/items", "GET /v1/items", "POST /v1/checkout", "POST /v1/checkout"])] },
      { events: [rowsStream(["GET /v1/items", "GET /v1/items", "GET /v1/items", "POST /v1/checkout", "POST /v1/checkout"])] },
    );
    const stream = result.streams[0];
    expect(stream.groupPath).toBe("label");
    expect(stream.groups[0]).toEqual({ key: "GET /v1/items", baselineCount: 2, currentCount: 3, delta: 1 });
  });

  test("the parsed network side-channel diffs as a network stream", () => {
    const hit = (urlPath: string) => ({ method: "GET", statusCode: 200, durationMs: 10, urlPath, phase: "end" });
    const result = compareEventStreams(
      { network: [hit("/v1/items"), hit("/v1/items"), hit("/v1/cart"), hit("/v1/cart")] },
      { network: [hit("/v1/items"), hit("/v1/items"), hit("/v1/items"), hit("/v1/cart"), hit("/v1/cart")] },
    );
    const stream = result.streams[0];
    expect(stream.stream).toBe("network");
    expect(stream.baselineCount).toBe(4);
    expect(stream.currentCount).toBe(5);
    expect(stream.groupPath).toBe("urlPath");
  });

  // A formatter folds a request and its response into ONE row, so row count is not event count.
  // Counting rows called a completed exchange against a request-only run `1 → 1`.
  test("counts come from the producer's total, not the folded row count", () => {
    const stream = (total: number) => ({
      events: [{
        name: "network",
        total,
        truncated: false,
        events: [],
        rows: [{ label: "GET /a", raw: ["{}"] }],
      }] as any,
    });
    const result = compareEventStreams(stream(2), stream(1));
    expect(result.streams[0].baselineCount).toBe(2);
    expect(result.streams[0].currentCount).toBe(1);
    expect(result.streams[0].delta).toBe(-1);
    expect(result.streams[0].changed).toBe(true);
  });

  // A matching prefix says nothing about the tail past the reader's cap, so "identical" is a
  // claim the data does not support.
  test("a truncated stream is never reported as unchanged", () => {
    const stream = (truncated: boolean) => ({
      events: [{
        name: "analytics",
        total: 2,
        truncated,
        events: [{ t: 1, d: '{"kind":"a"}' }, { t: 2, d: '{"kind":"b"}' }],
      }] as any,
    });
    const whole = compareEventStreams(stream(false), stream(false));
    expect(whole.streams[0].changed).toBe(false);
    expect(whole.streams[0].incomplete).toBe(false);

    const capped = compareEventStreams(stream(true), stream(false));
    expect(capped.streams[0].incomplete).toBe(true);
    expect(capped.streams[0].changed).toBe(true);
    expect(capped.summary).toContain("partial");
  });

  test("eventObjectsOf skips unparsable events but keeps scalar payloads", () => {
    const byStream = eventObjectsOf({
      events: [{ name: "custom", total: 3, truncated: false, events: [{ t: 1, d: "not json" }, { t: 2, d: '"a string"' }, { t: 3, d: '{"kind":"a"}' }] } as any],
    });
    // The event contract permits any JsonElement, log strings included — only `not json` is a
    // parse failure. Dropping the scalar left a stream of string records reading as empty.
    expect(byStream.get("custom")).toEqual(["a string", { kind: "a" }]);
  });
});

// ---------------------------------------------------------------------------
// Event content diff (viewer-only extension)
// ---------------------------------------------------------------------------

describe("volatile field detection", () => {
  test("id-like fields — values that never repeat — are volatile; enumerable fields are not", () => {
    const events = Array.from({ length: 6 }, (_, i) => ({ t: 1000 + i, tag: i < 3 ? "A" : "B", seq: `id-${i}` }));
    const volatile = volatileScalarPaths(events);
    expect(volatile.has("t")).toBe(true); // numeric timestamps count too, not just strings
    expect(volatile.has("seq")).toBe(true);
    expect(volatile.has("tag")).toBe(false);
  });

  test("too few sightings to judge means not volatile", () => {
    const events = Array.from({ length: 3 }, (_, i) => ({ u: `only-${i}` }));
    expect(volatileScalarPaths(events).has("u")).toBe(false);
  });
});

describe("event display lines", () => {
  test("sorted keys, nested indentation, array dashes, and masked volatile values", () => {
    const lines = eventDisplayLines({ list: [1, 2], b: { z: 1, a: "x" }, t: 5, empty: {} }, new Set(["t"]));
    expect(lines).toEqual([
      "b:",
      '  a: "x"',
      "  z: 1",
      "empty: {}",
      "list:",
      "  - 1",
      "  - 2",
      "t: ‹…›",
    ]);
  });

  // These lines ARE the identity events match on, so an untyped render made a string "1" and a
  // number 1 the same event — schema drift that reads as no change at all.
  test("a string and the same-looking number are not the same event", () => {
    expect(eventDisplayLines({ value: 1 }, new Set())).toEqual(["value: 1"]);
    expect(eventDisplayLines({ value: "1" }, new Set())).toEqual(['value: "1"']);
    const stream = (value: unknown) => ({
      events: [{ name: "flags", total: 1, truncated: false, events: [{ t: 1, d: JSON.stringify({ value }) }] }] as any,
    });
    const result = compareEventStreams(stream(1), stream("1"));
    expect(result.streams[0].contentSame).toBe(false);
    expect(result.streams[0].changed).toBe(true);
  });

  test("newlines inside values are escaped so one field stays one diff line", () => {
    expect(eventDisplayLines({ msg: "a\nb" }, new Set())).toEqual(['msg: "a\\nb"']);
  });

  test("display crops long values and elides the tail, while the matching lines stay whole", () => {
    const event = { aaa: "z".repeat(400), ...Object.fromEntries(Array.from({ length: 40 }, (_, i) => [`f${String(i).padStart(2, "0")}`, i])) };
    const shown = eventDisplayLines(event, new Set());
    expect(shown.length).toBe(31); // 30 lines + the elision line
    expect(shown[shown.length - 1]).toBe("… +11 more lines");
    expect(shown[0].endsWith("…")).toBe(true);
    expect(shown[0].length).toBeLessThan(200);
    expect(eventLines(event, new Set()).length).toBe(41);
    expect(eventLines(event, new Set()).some((l) => l.length > 400)).toBe(true);
  });
});

describe("event content diff", () => {
  // 6+ events so the unique timestamps cross the volatility threshold and mask out.
  const evt = (tag: string, t: number, extra: Record<string, unknown> = {}) => ({ tag, t, ...extra });

  test("appended events land in an added hunk after a collapsed matching run", () => {
    const diff = diffEventContent(
      [evt("A", 1), evt("B", 2)],
      [evt("A", 3), evt("B", 4), evt("C", 5), evt("D", 6)],
    );
    expect(diff.ordered).toBe(true);
    expect(diff.hunks.map((h) => h.kind)).toEqual(["same", "added"]);
    expect((diff.hunks[0] as any).count).toBe(2); // timestamps differ but are masked, so A/B match
    // Each added event is one row: its summary line, with the whole payload behind it.
    expect((diff.hunks[1] as any).rows.map((r: any) => r.summary)).toEqual(['tag="C"', 'tag="D"']);
    expect((diff.hunks[1] as any).rows[0].detail).toEqual(["t: ‹…›", 'tag: "C"']);
    expect(diff.addedCount).toBe(2);
  });

  test("a removed event shows whole between the matching runs around it", () => {
    const diff = diffEventContent(
      [evt("X", 1), evt("M", 2), evt("Y", 3)],
      [evt("X", 4), evt("Y", 5)],
    );
    expect(diff.hunks.map((h) => h.kind)).toEqual(["same", "removed", "same"]);
    expect((diff.hunks[1] as any).rows[0].summary).toBe('tag="M"');
    expect((diff.hunks[1] as any).rows[0].detail).toContain('tag: "M"');
    expect(diff.removedCount).toBe(1);
  });

  test("the events both runs share stay in the list, and a long matching run folds to its edges", () => {
    const run = (tag: string) => [evt(`${tag}1`, 1), evt(`${tag}2`, 2), evt(`${tag}3`, 3), evt(`${tag}4`, 4), evt(`${tag}5`, 5)];
    const diff = diffEventContent(run("A"), [...run("A"), evt("NEW", 9)]);

    const same = diff.hunks[0] as any;
    // A matching run is context, not a hole: the reader still sees where the change sits in the
    // stream. Only the middle of a long run folds away.
    expect(same.count).toBe(5);
    expect(same.head.map((r: any) => r.summary)).toEqual(['tag="A1"', 'tag="A2"']);
    expect(same.tail.map((r: any) => r.summary)).toEqual(['tag="A4"', 'tag="A5"']);
    expect(same.folded).toBe(1);
  });

  test("a short matching run shows every event rather than folding one away", () => {
    const diff = diffEventContent(
      [evt("A", 1), evt("B", 2), evt("C", 3)],
      [evt("A", 1), evt("B", 2), evt("C", 3), evt("NEW", 9)],
    );
    const same = diff.hunks[0] as any;
    expect(same.head.map((r: any) => r.summary)).toEqual(['tag="A"', 'tag="B"', 'tag="C"']);
    expect(same.tail).toEqual([]);
    expect(same.folded).toBe(0);
  });

  test("an adjacent removed+added run pairs into a per-line change, git-style", () => {
    const diff = diffEventContent(
      [evt("X", 1), evt("mid", 2, { value: 1 }), evt("Y", 3)],
      [evt("X", 4), evt("mid", 5, { value: 2 }), evt("Y", 6)],
    );
    expect(diff.hunks.map((h) => h.kind)).toEqual(["same", "changed", "same"]);
    const pair = (diff.hunks[1] as any).pairs[0];
    // The two summary rows say which event changed and mark the word that changed in it; the full
    // line diff waits behind the row, so the list stays one line per event.
    expect(pair.before.summary).toBe("mid  value=1");
    expect(pair.after.summary).toBe("mid  value=2");
    expect(pair.before.hi).toEqual([11, 12]);
    expect(pair.after.hi).toEqual([11, 12]);
    // The replaced value carries the word-level span, same contract as the tool lane: the reader's
    // eye lands on the `1`→`2`, not on a whole line whose key repeats verbatim.
    expect(pair.before.detail).toEqual(["t: ‹…›", 'tag: "mid"', "value: 1"]);
    expect(pair.lines).toEqual([
      { sign: " ", text: "t: ‹…›" },
      { sign: " ", text: 'tag: "mid"' },
      { sign: "-", text: "value: 1", hi: [7, 8] },
      { sign: "+", text: "value: 2", hi: [7, 8] },
    ]);
    expect(diff.changedCount).toBe(1);
  });

  test("the fields an expanded event hides are counted, not just marked with a ⋯", () => {
    const wide = (value: number) => ({ tag: "wide", value, ...Object.fromEntries(Array.from({ length: 12 }, (_, i) => [`f${i}`, i])) });
    const diff = diffEventContent([evt("X", 1), wide(1), evt("Y", 3)], [evt("X", 4), wide(2), evt("Y", 6)]);

    const pair = (diff.hunks.find((h) => h.kind === "changed") as any).pairs[0];
    // `value` is the only change; the fields either side of it print as context and the rest are
    // hidden — with their count, so the reader knows how much is behind the ⋯.
    expect(pair.lines.filter((l: any) => l.text.startsWith("⋯")).map((l: any) => l.text))
      .toEqual(["⋯ 11 unchanged fields"]);
  });

  // Timestamps unique across BOTH runs, so `t` clears the volatility threshold and masks out and
  // the events pair on their tags alone. Reusing timestamps between the runs would leave `t` in the
  // compared text, and the alignment would then turn on which events happened to collide.
  let clock = 0;
  const runOf = (tags: string[]) => tags.map((tag) => evt(tag, ++clock));

  // "How much of this run differs" and "where" are the first two things asked of a diff, and both
  // were left to the reader to work out by counting rows and folds by hand.
  test("the diff counts the sequence it spans and the places it diverges", () => {
    // A shared head, one replaced event, a shared middle, one removal, a shared tail.
    const diff = diffEventContent(
      runOf(["A", "B", "C", "D", "E", "F", "G", "H"]),
      runOf(["A", "B", "X", "D", "E", "G", "H"]),
    );
    expect(diff.hunks.map((h) => h.kind)).toEqual(["same", "changed", "same", "removed", "same"]);
    // Eight places in the sequence: the seven events both runs emitted plus the one only the
    // baseline did. The replaced C→X is one place, not two.
    expect(diff.slots).toBe(8);
    expect(diff.clusters).toBe(2);
  });

  test("a removal and the addition beside it are one place the runs diverge", () => {
    const diff = diffEventContent(
      runOf(["A", "B", "C", "D", "E"]),
      runOf(["A", "B", "P", "Q", "R", "D", "E"]),
    );
    // Whether the aligner splits this into changed/added or removed/added hunks is its business;
    // to the reader it is one stretch where the runs parted, so it counts once.
    expect(diff.hunks.filter((h) => h.kind !== "same").length).toBeGreaterThan(1);
    expect(diff.clusters).toBe(1);
  });

  test("a matching run knows where in the sequence it starts", () => {
    const diff = diffEventContent(
      runOf(["A", "B", "C", "D", "E", "F"]),
      runOf(["A", "Z", "C", "D", "E", "F"]),
    );
    const sames = diff.hunks.filter((h) => h.kind === "same") as any[];
    // The run before the change starts the sequence; the run after it picks up past the change.
    expect(sames[0].from).toBe(0);
    expect(sames[1].from).toBe(2);
    expect(sames[1].count).toBe(4);
  });

  // The prefix and suffix both runs share are stripped before aligning, and they have to come back
  // into the count — a diff that measured only the region it aligned would call a one-event change
  // in a 500-event run enormous.
  test("the stripped matching prefix and suffix still count toward the sequence", () => {
    const long = (tag: string) => Array.from({ length: 20 }, (_, i) => evt(tag, i + 1));
    const head = long("H");
    const tail = long("T");
    const diff = diffEventContent(
      [...head, evt("M", 99), ...tail],
      [...head, evt("N", 99), ...tail],
    );
    expect(diff.slots).toBe(41);
    expect(diff.clusters).toBe(1);
    expect((diff.hunks.find((h) => h.kind === "same") as any).from).toBe(0);
  });

  test("a pair whose only difference is a noisy field brings that field back into the summary", () => {
    // `t` is dropped from the summary as an ordering key, and here it repeats too often to be
    // masked — so the quiet summary would print the same text on both rows and claim nothing
    // changed. The row falls back to the full field list rather than lying.
    const diff = diffEventContent(
      [evt("A", 1), evt("A", 1), evt("B", 1)],
      [evt("A", 1), evt("A", 2), evt("B", 1)],
    );
    const pair = (diff.hunks.find((h) => h.kind === "changed") as any).pairs[0];
    expect(pair.before.summary).toContain("t=1");
    expect(pair.after.summary).toContain("t=2");
  });

  test("streams too large to align fall back to the unordered multiset difference", () => {
    const diff = diffEventContent(
      [evt("A", 1), evt("B", 2)],
      [evt("B", 3), evt("A", 4), evt("C", 5), evt("C", 6)],
      1, // force the fallback
    );
    expect(diff.ordered).toBe(false);
    // A and B both still exist in the current run — only the two Cs are genuinely new.
    expect(diff.hunks.map((h) => h.kind)).toEqual(["same", "added"]);
    expect((diff.hunks[0] as any).count).toBe(2);
    expect(diff.addedCount).toBe(2);
    expect(diff.removedCount).toBe(0);
  });

  test("lineDiff keeps common lines as context and orders removals before additions", () => {
    expect(lineDiff(["a", "b"], ["a", "c"])).toEqual([
      { sign: " ", text: "a" },
      { sign: "-", text: "b" },
      { sign: "+", text: "c" },
    ]);
  });

  // The LCS allocates an (n+1)×(m+1) matrix up front. A payload with tens of thousands of fields is
  // a valid capture, and asking for billions of cells to render it would take the tab down.
  // A moved line is the case that tells the two paths apart: the LCS aligns around it, the bounded
  // fallback rewrites the whole span between the shared head and tail.
  const withLineMoved = (lines: string[], from: number, to: number) => {
    const out = lines.slice();
    out.splice(to, 0, out.splice(from, 1)[0]);
    return out;
  };

  test("a pair too large to align falls back to a linear diff instead of allocating the matrix", () => {
    const size = Math.ceil(Math.sqrt(MAX_LINE_DIFF_CELLS)) + 10;
    const before = Array.from({ length: size }, (_, i) => `f${i}: v`);

    const diff = lineDiff(before, withLineMoved(before, 10, size - 20));

    expect(diff.filter((l) => l.sign === "-").length).toBeGreaterThan(size / 2);
    expect(diff.filter((l) => l.sign === "+").length).toBeGreaterThan(size / 2);
  });

  test("the same shape under the cap is still aligned precisely", () => {
    // The control for the test above: the bound, not the input, is what coarsens the diff.
    const before = Array.from({ length: 50 }, (_, i) => `f${i}: v`);

    const diff = lineDiff(before, withLineMoved(before, 10, 40));

    expect(diff.filter((l) => l.sign === "-").length).toBe(1);
    expect(diff.filter((l) => l.sign === "+").length).toBe(1);
  });
});

describe("content changes surface through compareEventStreams", () => {
  test("a stream with identical counts and groups but changed payloads reads as changed", () => {
    const result = compareEventStreams(
      { events: [genericStream("flags", [{ name: "x", on: true }, { name: "y", on: true }])] },
      { events: [genericStream("flags", [{ name: "x", on: false }, { name: "y", on: true }])] },
    );
    const stream = result.streams[0];
    expect(stream.delta).toBe(0);
    expect(stream.groups.every((g) => g.delta === 0)).toBe(true);
    expect(stream.contentSame).toBe(false);
    expect(stream.changed).toBe(true);
    const changedHunk = stream.content!.hunks.find((h) => h.kind === "changed") as any;
    // The row leads with the event's own name, so the reader knows which flag changed before
    // opening anything.
    expect(changedHunk.pairs[0].before.summary).toBe("x  on=true");
    expect(changedHunk.pairs[0].after.summary).toBe("x  on=false");
    // `hi` marks the changed value whole. The two words share a trailing "e", but marking `tru`
    // against `fals` would report the letters that moved rather than the values that did.
    const marked = (sign: string) => {
      const line = changedHunk.pairs[0].lines.find((l: any) => l.sign === sign);
      return line.text.slice(line.hi[0], line.hi[1]);
    };
    expect(marked("-")).toBe("true");
    expect(marked("+")).toBe("false");
    expect(result.summary).toContain("flags (2→2, content)");
  });

  // Matching has to read the whole event: a change past the display caps is exactly the change a
  // reader can't spot by eye, so it's the one the diff must not swallow.
  test("a change past the display caps still reads as changed", () => {
    const long = (tail: string) => ({ blob: `${"z".repeat(300)}${tail}` });
    const result = compareEventStreams(
      { events: [genericStream("blobs", [long("A")])] },
      { events: [genericStream("blobs", [long("B")])] },
    );
    expect(result.streams[0].contentSame).toBe(false);
    expect(result.streams[0].changed).toBe(true);
  });

  // A formatted stream keeps its payloads in `raw` and leaves `events` empty, so pairing rows by
  // label alone would call two same-named events with different properties unchanged.
  test("formatter rows compare on their raw payloads, not just their labels", () => {
    const rowStream = (on: boolean) => ({
      name: "analytics",
      total: 1,
      truncated: false,
      events: [],
      rows: [{ t: 1, label: "Tap", raw: [{ event: "Tap", on }] }],
    });
    expect(eventObjectsOf({ events: [rowStream(true)] }).get("analytics")).toEqual([{ label: "Tap", raw: [{ event: "Tap", on: true }] }]);
    const result = compareEventStreams({ events: [rowStream(true)] }, { events: [rowStream(false)] });
    expect(result.streams[0].contentSame).toBe(false);
    expect(result.streams[0].changed).toBe(true);
  });

  test("a stream differing only in volatile fields is content-same and unchanged", () => {
    const withTs = (ts: number) => ({ tag: "A", ts });
    const result = compareEventStreams(
      { events: [genericStream("quiet", [withTs(1), withTs(2)])] },
      { events: [genericStream("quiet", [withTs(3), withTs(4)])] },
    );
    expect(result.streams[0].contentSame).toBe(true);
    expect(result.streams[0].changed).toBe(false);
    expect(result.streams[0].content).toBeNull();
    expect(result.summary).toContain("all identical");
  });

  // Masking is what lets the test above read as unchanged, so the stream has to say which fields it
  // applied to. Without the list, "the two runs agree" is indistinguishable from "the field that
  // disagreed is one we hid".
  test("a stream names the fields its comparison left out", () => {
    const evt = (ts: number, screen: string) => ({ tag: "A", ts, screen });
    const result = compareEventStreams(
      { events: [genericStream("checkout", [evt(1, "cart"), evt(2, "cart")])] },
      { events: [genericStream("checkout", [evt(3, "cart"), evt(4, "pay")])] },
    );
    // `ts` never repeats, so it is masked; `screen` repeats, so it stays in the comparison — and it
    // is the field the diff then reports.
    expect(result.streams[0].maskedPaths).toEqual(["ts"]);
    expect(result.streams[0].changed).toBe(true);
  });

  // A stream is a sequence, not a bag: the run that logged "checkout started" then "payment
  // declined" is not the run that logged them the other way round. Comparing the two as multisets
  // matches every event against a partner and reports nothing.
  test("the same events emitted in a different order read as changed", () => {
    const result = compareEventStreams(
      { events: [genericStream("flow", [{ step: "A" }, { step: "B" }])] },
      { events: [genericStream("flow", [{ step: "B" }, { step: "A" }])] },
    );
    const stream = result.streams[0];
    expect(stream.delta).toBe(0);
    expect(stream.groups.every((g) => g.delta === 0)).toBe(true);
    expect(stream.contentSame).toBe(false);
    expect(stream.changed).toBe(true);
    expect(stream.content).not.toBeNull();
  });
});

// Which pair the view opens on. A report holding several trails used to open on the first two runs
// in the document — routinely two DIFFERENT trails, diffed as confidently as a repeat.
describe("default compare pair", () => {
  const runs = (...keys: string[]) => keys.map((trailKey, index) => ({ index, trailKey }));

  test("prefers the first trail that ran twice over the first two runs in the document", () => {
    // Runs 0 and 1 are different trails; runs 1 and 3 are the same trail on two devices.
    expect(defaultComparePair(runs("trail:search", "trail:items", "trail:cart", "trail:items")))
      .toEqual([1, 3]);
  });

  test("baseline is the earlier run of the pair", () => {
    expect(defaultComparePair(runs("trail:items", "trail:items", "trail:items"))).toEqual([0, 1]);
  });

  test("falls back to the first two runs when no trail ran twice", () => {
    expect(defaultComparePair(runs("trail:a", "trail:b", "trail:c"))).toEqual([0, 1]);
  });

  // An unidentified run is not "the same trail" as another unidentified run — they are simply
  // unnamed. Two of them share the empty key, so treating that key as a trail would let them win
  // the pair over an actual repeat: the identified runs at 1 and 3 are the same-trail pair here.
  test("unidentified runs never establish a pair", () => {
    expect(defaultComparePair(runs("", "trail:a", "", "trail:a"))).toEqual([1, 3]);
  });

  test("index is the document position, not the position among comparable runs", () => {
    // Link-out stubs are filtered upstream, so the indexes arriving here can have gaps.
    const sparse = [{ index: 2, trailKey: "trail:a" }, { index: 5, trailKey: "trail:b" }, { index: 9, trailKey: "trail:a" }];
    expect(defaultComparePair(sparse)).toEqual([2, 9]);
  });
});

describe("diffPixels (the screens lane's pixel comparison)", () => {
  // 4 bytes per pixel, RGBA. Width×height must agree with the byte count.
  const image = (width: number, height: number, pixels: number[][]) => ({ width, height, data: Uint8ClampedArray.from(pixels.flat()) });
  const opaque = (r: number, g: number, b: number) => [r, g, b, 255];

  test("identical buffers differ nowhere", () => {
    const px = image(2, 1, [opaque(10, 20, 30), opaque(200, 200, 200)]);
    const result = diffPixels(px, image(2, 1, [opaque(10, 20, 30), opaque(200, 200, 200)]));
    if (result.kind !== "diff") throw new Error(result.kind);
    expect(result.differing).toBe(0);
    expect(result.percent).toBe(0);
    expect(Array.from(result.mask)).toEqual([0, 0]);
  });

  test("a changed pixel is counted and located in the mask", () => {
    const baseline = image(2, 1, [opaque(0, 0, 0), opaque(0, 0, 0)]);
    const current = image(2, 1, [opaque(0, 0, 0), opaque(255, 255, 255)]);
    const result = diffPixels(baseline, current);
    if (result.kind !== "diff") throw new Error(result.kind);
    expect(result.differing).toBe(1);
    expect(result.total).toBe(2);
    expect(result.percent).toBe(50);
    expect(Array.from(result.mask)).toEqual([0, 1]);
  });

  // The threshold is differ's SimpleImageComparator default (0.1 in normalised RGBA space), the
  // same constant the JVM golden gate compares with — a pair that passes there must read as
  // identical here. 0.1 of a single channel is 25.5/255, so 25 is inside and 26 outside.
  test("sub-threshold noise does not count as a difference", () => {
    const baseline = image(1, 1, [opaque(100, 100, 100)]);
    const inside = diffPixels(baseline, image(1, 1, [opaque(125, 100, 100)]));
    const outside = diffPixels(baseline, image(1, 1, [opaque(126, 100, 100)]));
    if (inside.kind !== "diff" || outside.kind !== "diff") throw new Error("expected diffs");
    expect(inside.differing).toBe(0);
    expect(outside.differing).toBe(1);
  });

  // Two devices' captures share no pixel grid; scaling one onto the other would manufacture
  // differences everywhere. The mismatch is its own answer, not a 100% diff.
  test("different dimensions report a size mismatch, not a diff", () => {
    const result = diffPixels(image(2, 1, [opaque(0, 0, 0), opaque(0, 0, 0)]), image(1, 1, [opaque(0, 0, 0)]));
    expect(result.kind).toBe("size_mismatch");
    if (result.kind === "size_mismatch") {
      expect(result.baseline).toEqual([2, 1]);
      expect(result.current).toEqual([1, 1]);
    }
  });
});

describe("alignedScenes (the screens lane's scene detection)", () => {
  const row = (toolName: string, baselineStep: number | null, currentStep: number | null) =>
    ({ toolName, status: "same", changes: [], baselineStep, currentStep }) as any;

  test("a new scene starts only where either side's frame moves on", () => {
    const baselineFrames = { 1: "b1.png", 2: "b1.png", 3: "b2.png" };
    const currentFrames = { 1: "c1.png", 2: "c1.png", 3: "c1.png" };
    const scenes = alignedScenes(
      [row("launchApp", 1, 1), row("inputText", 2, 2), row("tapOn", 3, 3)],
      (step) => baselineFrames[step] || null,
      (step) => currentFrames[step] || null,
    );
    expect(scenes).toEqual([
      { position: 1, toolName: "launchApp", baselineFile: "b1.png", currentFile: "c1.png" },
      { position: 3, toolName: "tapOn", baselineFile: "b2.png", currentFile: "c1.png" },
    ]);
  });

  // A call that captured nothing still ran ON some screen: the side keeps the frame it was last
  // seen on rather than dropping out of the scene.
  test("a side with no frame at a call keeps the one it was last seen on", () => {
    const scenes = alignedScenes(
      [row("launchApp", 1, 1), row("wait", 2, null)],
      (step) => (step === 1 ? "b1.png" : null),
      (step) => (step === 1 ? "c1.png" : null),
    );
    expect(scenes).toEqual([{ position: 1, toolName: "launchApp", baselineFile: "b1.png", currentFile: "c1.png" }]);
  });

  test("rows before any capture produce no scene", () => {
    expect(alignedScenes([row("wait", 1, 1)], () => null, () => null)).toEqual([]);
  });
});
