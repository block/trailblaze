// Behavioral contracts for the report's canonical trace model. These tests cover the authored
// step/retry structure and exact LLM-call correlation consumed by every report surface.
import { describe, expect, test } from "bun:test";
import { buildReportTraceModel, createReportTraceModelResolver } from "./run-report-trace-model";

const row = (i: number, patch: Partial<TraceStep> = {}): TraceStep => ({
  i,
  label: `row ${i}`,
  tool: "tool",
  note: null,
  ms: 1,
  ts: null,
  ok: true,
  err: null,
  screenshotFile: null,
  objective: false,
  trailhead: false,
  count: null,
  mark: null,
  ...patch,
});

const session = (trace: TraceStep[], llmCount: number): SessionPayload => ({
  meta: {},
  trace,
  llm: new Array(llmCount).fill(null).map((_, i) => ({
    model: "model",
    inputTokens: null,
    outputTokens: null,
    cacheReadTokens: 0,
    totalCost: null,
    promptCost: null,
    completionCost: null,
    cacheSavings: 0,
    comp: null,
    durationMs: 0,
    label: `call ${i}`,
    instructions: null,
    response: [],
  })),
  shots: {},
  recordingYaml: null,
  originalYaml: null,
});

describe("buildReportTraceModel", () => {
  test("groups authored retries and correlates every exact LLM call once", () => {
    const trailhead = row(1, { objective: true, trailhead: true, label: "Prepare" });
    const setupCall = row(2, { llm: 0, ts: 120 });
    const failedStep = row(3, { objective: true, label: "Pay", ok: false, selfHeal: true });
    const firstAttempt = row(4, { llm: 1 });
    const retry = row(5, { objective: true, label: "Pay" });
    const healedAttempt = row(6, { llm: 2 });
    const nextStep = row(7, { objective: true, label: "Confirm" });
    const nextCall = row(8, { llm: 3 });

    const model = buildReportTraceModel([
      trailhead, setupCall, failedStep, firstAttempt, retry, healedAttempt, nextStep, nextCall,
    ], 4);

    expect(model.groups.map((group) => group.num)).toEqual([0, 1, 2]);
    expect(model.groups[1].items).toEqual([firstAttempt, healedAttempt]);
    expect(model.groups[1].retryAt).toEqual([1]);
    expect(model.groups[1].retryHeaders).toEqual([retry]);
    expect(model.groupByRow.get(retry)).toBe(model.groups[1]);
    expect(model.callMap.byCall.map((context) => context && context.row)).toEqual([
      setupCall, firstAttempt, healedAttempt, nextCall,
    ]);
    expect(model.callMap.steps.map((step) => step.calls)).toEqual([[0], [1, 2], [3]]);
    expect(model.callMap.stepIndexByGroup.get(model.groups[1])).toBe(1);
    expect(model.traceT0).toBe(120);
    expect(model.indexById.get(8)).toBe(7);
  });

  test("keeps unscoped legacy calls directly reachable without inventing a step", () => {
    const legacyCall = row(10, { llm: 0 });
    const invalidCall = row(11, { llm: 5 });
    const model = buildReportTraceModel([legacyCall, invalidCall], 1);

    expect(model.groups).toHaveLength(1);
    expect(model.groups[0].header).toBeNull();
    expect(model.callMap.byCall[0]?.row).toBe(legacyCall);
    expect(model.callMap.steps).toEqual([]);
  });

  test("timeline entries follow each row with the dispatches it absorbed that captured a frame", () => {
    const header = row(1, { objective: true, label: "Open the transaction" });
    // A folded batch: the row's own frame, then three dispatches — one with its own frame, one
    // declared but never logged (no frame), one whose frame is already what the row shows.
    const batch = row(2, {
      screenshotFile: "a.png",
      ts: 1000,
      children: [
        { label: "tapOn", tool: "tapOn keypad", ms: 40, ts: 1100, screenshotFile: "b.png" },
        { label: "tapOn", tool: "tapOn declared", ms: null, ts: null, screenshotFile: null },
        { label: "tapOn", tool: "tapOn again", ms: 40, ts: 1200, screenshotFile: "b.png" },
      ],
    });
    const plain = row(3, { screenshotFile: "c.png", ts: 1300 });

    const model = buildReportTraceModel([header, batch, plain], 0);

    expect(model.entries.map((e) => [e.row.i, e.kid])).toEqual([[1, null], [2, null], [2, 0], [3, null]]);
    // A dispatch entry carries its OWN clock and duration — what playback schedules it on.
    expect(model.entries[2].ts).toBe(1100);
    expect(model.entries[2].ms).toBe(40);
    expect(model.entries[2].child?.screenshotFile).toBe("b.png");
    // Rows resolve to their own entry; their dispatches sit immediately after it.
    expect(model.entryIndexById.get(2)).toBe(1);
    expect(model.entryIndexById.get(3)).toBe(3);
  });

  test("a row with no frame of its own still gives every framed dispatch an entry", () => {
    // A frameless row only BORROWS a dispatch's frame for the preview pane. The dispatch that
    // captured it owns the label and tap mark that frame is about, so it still earns its own stop.
    const batch = row(1, {
      children: [
        { label: "tapOn", tool: "tapOn", ms: 10, ts: 20, screenshotFile: "a.png" },
        { label: "swipe", tool: "swipe", ms: 10, ts: 30, screenshotFile: "b.png" },
      ],
    });

    const model = buildReportTraceModel([batch], 0);

    expect(model.entries.map((e) => e.kid)).toEqual([null, 0, 1]);
  });

  test("a dispatch repeating the frame its own row displays earns no entry", () => {
    // The one dedupe the model can make honestly: a row shows its OWN screenshotFile, so a dispatch
    // carrying that same image would stall playback on a frame already on screen.
    const batch = row(1, {
      screenshotFile: "a.png",
      children: [
        { label: "tapOn", tool: "tapOn", ms: 10, ts: 20, screenshotFile: "a.png" },
        { label: "swipe", tool: "swipe", ms: 10, ts: 30, screenshotFile: "b.png" },
      ],
    });

    const model = buildReportTraceModel([batch], 0);

    expect(model.entries.map((e) => e.kid)).toEqual([null, 1]);
  });

  test("a dispatch is deduped against its own row's frame, never an earlier row's", () => {
    // Row 1 already showed a.png, but row 2's first dispatch is a different interaction that landed
    // on the same screen. Carrying the shown frame across rows would leave it unaddressable.
    const first = row(1, { screenshotFile: "a.png", ts: 10 });
    const folded = row(2, {
      children: [
        { label: "tapOn", tool: "tapOn", ms: 10, ts: 20, screenshotFile: "a.png" },
        { label: "swipe", tool: "swipe", ms: 10, ts: 30, screenshotFile: "b.png" },
      ],
    });

    const model = buildReportTraceModel([first, folded], 0);

    expect(model.entries.map((e) => [e.row.i, e.kid])).toEqual([[1, null], [2, null], [2, 0], [2, 1]]);
  });

  test("dispatches with no timestamp of their own get distinct instants inside the row's span", () => {
    // Payloads predating TraceChild.ts still have to replay dispatch by dispatch: sharing the row's
    // instant would give every entry the same playback offset, collapsing the fold onto its last
    // dispatch. The instants are spread across the row's OWN span, weighted by each dispatch's
    // duration and offset to its midpoint, and a skipped dispatch still consumes its slice.
    const batch = row(1, {
      screenshotFile: "a.png",
      ts: 5000,
      ms: 100,
      children: [
        { label: "scroll", tool: "scroll", ms: 60, screenshotFile: "a.png" }, // same frame: no entry
        { label: "tapOn", tool: "tapOn", ms: 40, screenshotFile: "b.png" },
        { label: "swipe", tool: "swipe", ms: 30, screenshotFile: "c.png" },
      ],
    });
    const next = row(2, { ts: 5100, screenshotFile: "d.png" });

    const model = buildReportTraceModel([batch, next], 0);

    expect(model.entries.map((e) => [e.kid, e.ts])).toEqual([
      [null, 5000],
      [1, 5062], // 100ms span × (100 - 40/2)/130 of the dispatches' 130ms
      [2, 5088], // 100ms span × (130 - 30/2)/130
      [null, 5100],
    ]);
    // The row's span is the ceiling: no synthetic instant may reach the next row's real ts, or
    // playback drags every offset after the fold forward with it.
    expect(model.entries.every((e) => e.ts != null && e.ts >= 5000 && e.ts <= 5100)).toBe(true);
  });

  test("a row with no recorded duration still spreads its timestamp-less dispatches", () => {
    // No span to divide, so the dispatches' own cumulative durations carry the clock. There is no
    // next-row ceiling to respect either: the row itself claims no elapsed time.
    const batch = row(1, { screenshotFile: "a.png", ts: 900, ms: 0, children: [
      { label: "tapOn", tool: "tapOn", ms: 20, screenshotFile: "b.png" },
      { label: "swipe", tool: "swipe", ms: 30, screenshotFile: "c.png" },
    ] });

    const model = buildReportTraceModel([batch], 0);

    expect(model.entries.map((e) => [e.kid, e.ts])).toEqual([[null, 900], [0, 920], [1, 950]]);
  });
});

describe("createReportTraceModelResolver", () => {
  test("reuses stable session models and invalidates replaced or appended inputs", () => {
    const report = session([row(1, { objective: true }), row(2, { llm: 0 })], 1);
    const resolve = createReportTraceModelResolver();
    const first = resolve(report);

    expect(resolve(report)).toBe(first);

    report.trace.push(row(3));
    const appended = resolve(report);
    expect(appended).not.toBe(first);
    expect(appended.indexById.get(3)).toBe(2);

    report.trace = [...report.trace];
    const replacedTrace = resolve(report);
    expect(replacedTrace).not.toBe(appended);

    report.llm = [...report.llm];
    expect(resolve(report)).not.toBe(replacedTrace);
  });
});
