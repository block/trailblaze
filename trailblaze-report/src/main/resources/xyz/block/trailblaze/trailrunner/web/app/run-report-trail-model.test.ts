// Behavioral contracts for the cross-run trail matrix: the Trail view's join of the same authored
// trail across several devices. Alignment is positional (step number), timing comes from each
// lane's own clock, and frame selection matches the Lightbox's rules.
import { describe, expect, test } from "bun:test";
import { buildReportTraceModel, failureAnchorIndex } from "./run-report-trace-model";
import { buildTrailMatrix, pruneIdleTrailCells, traceDeviceLanes, trailIdentity, trailJoinFor, trailViewScopes, type TrailCandidate } from "./run-report-trail-model";

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

const isLlmTurn = (r: TraceStep) => r.tool === "agent step";
const everyShot = () => true;

describe("buildTrailMatrix", () => {
  test("joins two lanes on step number, with per-lane timing off each lane's own clock", () => {
    const laneA = buildReportTraceModel([
      row(1, { objective: true, trailhead: true, label: "Prepare", ts: 1000 }),
      row(2, { ts: 1000, ms: 500 }),
      row(3, { objective: true, label: "Sign in", ts: 2000 }),
      row(4, { ts: 2000, ms: 3000, screenshotFile: "a-signin.webp" }),
      row(5, { objective: true, label: "Verify home", ts: 6000 }),
      row(6, { ts: 6000, ms: 1000, screenshotFile: "a-home.webp" }),
    ], 0);
    // Lane B starts on a different clock, runs slower, and never reaches step 2.
    const laneB = buildReportTraceModel([
      row(1, { objective: true, trailhead: true, label: "Prepare", ts: 50000 }),
      row(2, { ts: 50000, ms: 800 }),
      row(3, { objective: true, label: "Sign in", ts: 53000, ok: false }),
      row(4, { ts: 53000, ms: 9000, ok: false, screenshotFile: "b-signin.webp" }),
    ], 0);

    const matrix = buildTrailMatrix([laneA, laneB], everyShot, isLlmTurn);

    expect(matrix.rows.map((r) => r.num)).toEqual([0, 1, 2]);
    expect(matrix.rows[0].label).toBe("Trailhead");
    expect(matrix.rows[1].label).toBe("Sign in");

    // Step 1 aligns across lanes; offsets are from each lane's OWN first record.
    const [a1, b1] = matrix.rows[1].cells;
    expect(a1?.startMs).toBe(1000);
    expect(a1?.durationMs).toBe(3000);
    expect(a1?.ok).toBe(true);
    expect(b1?.startMs).toBe(3000);
    expect(b1?.durationMs).toBe(9000);
    expect(b1?.ok).toBe(false);

    // Lane B never reached step 2: a null cell, not a fabricated one.
    expect(matrix.rows[2].cells[0]?.lastFrame?.file).toBe("a-home.webp");
    expect(matrix.rows[2].cells[1]).toBeNull();

    // The shared axis spans the slowest lane.
    expect(matrix.maxEndMs).toBe(12000);
  });

  test("a position join names no row, so neither lane's step wording is put on the other's cell", () => {
    // Runs of DIFFERENT trails, put side by side because a reader picked them. Lane A's authored
    // steps are numbered 1 and 2; lane B's single step is its own step 1 of something else.
    const laneA = buildReportTraceModel([
      row(1, { objective: true, trailhead: true, label: "Prepare", ts: 1000 }),
      row(2, { ts: 1000, ms: 500 }),
      row(3, { objective: true, label: "Sign in", ts: 2000 }),
      row(4, { ts: 2000, ms: 1000 }),
      row(5, { objective: true, label: "Pay", ts: 3000 }),
      row(6, { ts: 3000, ms: 1000 }),
    ], 0);
    const laneB = buildReportTraceModel([
      row(1, { objective: true, label: "Open the refunds list", ts: 9000 }),
      row(2, { ts: 9000, ms: 400 }),
    ], 0);

    const joined = buildTrailMatrix([laneA, laneB], everyShot, isLlmTurn, () => null, "position");

    expect(joined.join).toBe("position");
    // Row 1 holds each lane's first step — lane B's only step sits beside lane A's "Sign in"
    // rather than under a row that calls them both the same thing.
    expect(joined.rows.map((r) => r.label)).toEqual(["Trailhead", "", ""]);
    expect(joined.rows[1].cells[0]?.label).toBe("Sign in");
    expect(joined.rows[1].cells[1]?.label).toBe("Open the refunds list");
    // With no row label to agree with, every cell shows its own wording.
    expect(joined.rows[1].cells.map((c) => c?.labelDiffers)).toEqual([true, true]);
    // Lane B has no second step; the row is lane A's alone.
    expect(joined.rows[2].cells[1]).toBeNull();

    // The same lanes joined by authored step number instead: lane B's step 1 lands on row 1 too,
    // but the row takes a shared name — which is exactly the claim a position join won't make.
    const byStep = buildTrailMatrix([laneA, laneB], everyShot, isLlmTurn);
    expect(byStep.join).toBe("step");
    expect(byStep.rows[1].label).toBe("Sign in");
  });

  test("keeps a lane's own step wording when it differs from the row label", () => {
    const laneA = buildReportTraceModel([row(1, { objective: true, label: "Tap Save" })], 0);
    const laneB = buildReportTraceModel([row(1, { objective: true, label: "Tap Save (top right on iPad)" })], 0);
    const matrix = buildTrailMatrix([laneA, laneB], everyShot, isLlmTurn);
    expect(matrix.rows[0].label).toBe("Tap Save");
    expect(matrix.rows[0].cells[0]?.labelDiffers).toBe(false);
    expect(matrix.rows[0].cells[1]?.labelDiffers).toBe(true);
    expect(matrix.rows[0].cells[1]?.label).toBe("Tap Save (top right on iPad)");
  });

  test("collects every distinct frame but summarizes with the last ROW frame, like the Lightbox", () => {
    const model = buildReportTraceModel([
      row(1, { objective: true, label: "Batch step" }),
      row(2, {
        screenshotFile: "own.webp",
        children: [
          { i: 0, label: "tap A", ms: 1, ts: null, ok: true, err: null, screenshotFile: "kid-a.webp", mark: null },
          { i: 1, label: "tap B", ms: 1, ts: null, ok: true, err: null, screenshotFile: "kid-b.webp", mark: null },
        ],
      } as Partial<TraceStep>),
    ], 0);
    const matrix = buildTrailMatrix([model], everyShot, isLlmTurn);
    const cell = matrix.rows[0].cells[0];
    expect(cell?.frames.map((f) => f.file)).toEqual(["own.webp", "kid-a.webp", "kid-b.webp"]);
    // The row's own capture summarizes the step even though dispatch frames follow it.
    expect(cell?.lastFrame?.file).toBe("own.webp");
    expect(cell?.lastFrame?.kid).toBeNull();
  });

  test("places a capture and its interaction at the moment the action ran, not the row's start", () => {
    // A tool logged at timeBeforeExecution that spent 4s resolving its selector before tapping: the
    // frame and the tap both belong at 4s into the lane, not at the row's start. Replay draws them
    // over a recording synchronized to the same clock, so this offset is what the reader sees.
    const mark = { kind: "tap", x: 10, y: 20, dw: 100, dh: 200 } as TraceStep["mark"];
    const late = buildReportTraceModel([
      row(1, { objective: true, label: "Sign in", ts: 1000 }),
      row(2, { ts: 1000, ms: 5000, screenshotFile: "tap.webp", mark, shotTs: 5000, markTs: 5000 }),
    ], 0);
    const lateCell = buildTrailMatrix([late], everyShot, isLlmTurn).rows[0].cells[0];
    expect(lateCell?.frames.map((f) => f.atMs)).toEqual([4000]);
    expect(lateCell?.lastFrame?.atMs).toBe(4000);
    expect(lateCell?.actions.map((a) => a.atMs)).toEqual([4000]);

    // A row that reports no separate instant for its cues — every row before this change, and every
    // row whose tool acted immediately — still places them at its own start.
    const same = buildReportTraceModel([
      row(1, { objective: true, label: "Sign in", ts: 1000 }),
      row(2, { ts: 1000, ms: 5000, screenshotFile: "tap.webp", mark }),
    ], 0);
    const sameCell = buildTrailMatrix([same], everyShot, isLlmTurn).rows[0].cells[0];
    expect(sameCell?.frames.map((f) => f.atMs)).toEqual([0]);
    expect(sameCell?.actions.map((a) => a.atMs)).toEqual([0]);
  });

  test("a crashed run's step reads failed even though no objective recorded the failure", () => {
    // A crash logs no Complete bookend, so every objective row stays ok and only the tool row that
    // died is failed. Without the run's failure anchor the step it died in paints green — the run
    // reads as failed in the index while the Trail view shows nothing wrong anywhere.
    const trace = [
      row(1, { objective: true, trailhead: true, label: "Prepare" }),
      row(2, {}),
      row(3, { objective: true, label: "Sign in" }),
      row(4, { ok: false, err: "process died" }),
      row(5, { objective: true, label: "Verify home" }),
    ];
    const model = buildReportTraceModel(trace, 0);
    const anchor = () => trace[failureAnchorIndex(trace)] || null;

    const unanchored = buildTrailMatrix([model], everyShot, isLlmTurn);
    expect(unanchored.rows[1].cells[0]?.ok).toBe(true);

    const anchored = buildTrailMatrix([model], everyShot, isLlmTurn, anchor);
    expect(anchored.rows[1].cells[0]?.ok).toBe(false);
    // Only the anchored step: the steps around it did not fail, and painting the whole lane red
    // would lose the one piece of information the reader came for.
    expect(anchored.rows[0].cells[0]?.ok).toBe(true);
    expect(anchored.rows[2].cells[0]?.ok).toBe(true);
    // This trace has no timestamps at all, so the failure has a step but no placeable instant.
    expect(anchored.rows[1].cells[0]?.failureAtMs).toBeNull();
  });

  test("stamps the failure's own instant on the anchored cell, on the lane clock", () => {
    // The anchor row's ts is its timeBeforeExecution — the last placeable moment before the failing
    // work ran — NOT the step's start: a step can run half a minute before the tool that dies in it
    // even begins, and a scrubber jumping to the step start would land nowhere near the failure.
    const trace = [
      row(1, { objective: true, trailhead: true, label: "Prepare", ts: 1000 }),
      row(2, { ts: 1000, ms: 500 }),
      row(3, { objective: true, label: "Sign in", ts: 2000 }),
      row(4, { ts: 2000, ms: 1000 }),
      row(5, { ts: 3500, ms: 800, ok: false, err: "process died" }),
    ];
    const model = buildReportTraceModel(trace, 0);
    const anchor = () => trace[failureAnchorIndex(trace)] || null;

    const matrix = buildTrailMatrix([model], everyShot, isLlmTurn, anchor);
    // The dying row logged ts 3500 on a lane whose clock starts at 1000.
    expect(matrix.rows[1].cells[0]?.failureAtMs).toBe(2500);
    // Only the anchored cell carries it; a lane whose run passed carries none anywhere.
    expect(matrix.rows[0].cells[0]?.failureAtMs).toBeNull();
    const passing = buildTrailMatrix([model], everyShot, isLlmTurn, () => null);
    expect(passing.rows[1].cells[0]?.failureAtMs).toBeNull();
  });

  test("an anchor with no timestamp of its own places the failure at the step span's end", () => {
    // A crash's dying row can log nothing — the step's clock simply stops, and where it stopped is
    // the best placeable account of when the run died.
    const trace = [
      row(1, { objective: true, trailhead: true, label: "Prepare", ts: 1000 }),
      row(3, { objective: true, label: "Sign in", ts: 2000 }),
      row(4, { ts: 2000, ms: 1000 }),
      row(5, { ok: false, err: "process died" }),
    ];
    const model = buildReportTraceModel(trace, 0);
    const matrix = buildTrailMatrix([model], everyShot, isLlmTurn, () => trace[failureAnchorIndex(trace)] || null);
    expect(matrix.rows[1].cells[0]?.failureAtMs).toBe(2000);
  });

  test("a tolerated failure inside a step that passed stays passing", () => {
    // Retry polling and a trailhead's recovery loops fail rows on purpose. The lane's anchor is null
    // for a run that passed, so none of them can redden a step; and in a run that DID fail
    // elsewhere, the anchor names that objective's row rather than the tolerated one.
    const trace = [
      row(1, { objective: true, label: "Wait for sync" }),
      row(2, { ok: false, err: "not ready yet" }),
      row(3, {}),
      row(4, { objective: true, label: "Check out", ok: false }),
      row(5, { ok: false, err: "no such element" }),
    ];
    const model = buildReportTraceModel(trace, 0);

    const passing = buildTrailMatrix([model], everyShot, isLlmTurn, () => null);
    expect(passing.rows[0].cells[0]?.ok).toBe(true);

    const failed = buildTrailMatrix([model], everyShot, isLlmTurn, () => trace[failureAnchorIndex(trace)] || null);
    expect(failed.rows[0].cells[0]?.ok).toBe(true);
    expect(failed.rows[1].cells[0]?.ok).toBe(false);
  });

  test("excludes LLM turn rows from the tool count and honors the shot filter", () => {
    const model = buildReportTraceModel([
      row(1, { objective: true, label: "Step" }),
      row(2, { tool: "agent step", screenshotFile: "thinking.webp" }),
      row(3, { screenshotFile: "missing.webp" }),
      row(4, { screenshotFile: "present.webp" }),
    ], 0);
    const matrix = buildTrailMatrix([model], (_, file) => file === "present.webp", isLlmTurn);
    const cell = matrix.rows[0].cells[0];
    expect(cell?.toolCount).toBe(2);
    expect(cell?.frames.map((f) => f.file)).toEqual(["present.webp"]);
    expect(cell?.lastFrame?.file).toBe("present.webp");
  });
});

// One SESSION that drove two devices through switchDevice handovers (TraceStep.device), split
// into one lane per device instead of one lane per run. Timing intent: the session starts on the
// storefront device, step 2's objective is ANNOUNCED while the storefront still has focus, then
// the agent hands over and does the work on the kitchen device.
describe("traceDeviceLanes", () => {
  const sessionTrace = (): TraceStep[] => [
    row(1, { objective: true, trailhead: true, label: "Prepare", ts: 1000, device: "storefront" }),
    row(2, { ts: 1000, ms: 500, device: "storefront" }),
    row(3, { objective: true, label: "Place the order", ts: 2000, screenshotFile: "step1-announce.webp", device: "storefront" }),
    row(4, { ts: 2000, ms: 1000, screenshotFile: "order.webp", device: "storefront" }),
    row(5, { objective: true, label: "Confirm the ticket", ts: 3000, device: "storefront" }),
    row(6, { label: "switchDevice", ts: 3200, ms: 100, device: "kitchen" }),
    row(7, { ts: 3500, ms: 2000, screenshotFile: "ticket.webp", device: "kitchen" }),
  ];

  test("returns [] unless the trace names at least two devices", () => {
    expect(traceDeviceLanes([row(1), row(2)])).toEqual([]);
    expect(traceDeviceLanes([row(1, { device: "storefront" }), row(2, { device: "storefront" })])).toEqual([]);
  });

  test("splits by device in first-appearance order; non-header rows stay only in their own lane", () => {
    const [storefront, kitchen] = traceDeviceLanes(sessionTrace());
    expect(storefront.device).toBe("storefront");
    expect(kitchen.device).toBe("kitchen");
    expect(storefront.trace.filter((r) => !r.objective).map((r) => r.i)).toEqual([2, 4]);
    expect(kitchen.trace.filter((r) => !r.objective).map((r) => r.i)).toEqual([6, 7]);
  });

  test("every lane keeps every step header for alignment, but a borrowed header is declocked and captureless", () => {
    const trace = sessionTrace();
    const [storefront, kitchen] = traceDeviceLanes(trace);
    expect(storefront.trace.filter((r) => r.objective).map((r) => r.i)).toEqual([1, 3, 5]);
    expect(kitchen.trace.filter((r) => r.objective).map((r) => r.i)).toEqual([1, 3, 5]);
    // The owning lane keeps the ORIGINAL row object — failure anchors are matched by identity.
    expect(storefront.trace.find((r) => r.i === 3)).toBe(trace[2]);
    // The borrowed copy must not carry the clock or the capture: a timestamped header would
    // stretch the idle lane's cell across the step, and the screenshot belongs to the other device.
    const borrowed = kitchen.trace.find((r) => r.i === 3);
    expect(borrowed).not.toBe(trace[2]);
    expect(borrowed?.ts).toBeNull();
    expect(borrowed?.screenshotFile).toBeNull();
  });

  test("device lanes join into a matrix where idle steps prune to gaps, on one shared session clock", () => {
    const lanes = traceDeviceLanes(sessionTrace());
    // Every lane is zeroed on the SESSION's start, not its own first record — the lanes ran
    // interleaved in one run, so per-lane zeroing would slide them against each other.
    const models = lanes.map((lane) => ({ ...buildReportTraceModel(lane.trace, 0), traceT0: 1000 }));
    const matrix = pruneIdleTrailCells(buildTrailMatrix(models, everyShot, isLlmTurn));
    expect(matrix.rows.map((r) => r.num)).toEqual([0, 1, 2]);
    // The kitchen sat out the trailhead and step 1: gaps, not hollow cells — and not the
    // storefront's announcement frame either (the borrowed header carries no capture).
    expect(matrix.rows[0].cells[1]).toBeNull();
    expect(matrix.rows[1].cells[1]).toBeNull();
    expect(matrix.rows[1].cells[0]?.frames.map((f) => f.file)).toEqual(["step1-announce.webp", "order.webp"]);
    // Step 2 was announced on the storefront but WORKED on the kitchen: the storefront's
    // header-only cell prunes away, and the kitchen's cell starts at the handover — not at the
    // announcement, which is the storefront's instant.
    expect(matrix.rows[2].cells[0]).toBeNull();
    expect(matrix.rows[2].cells[1]?.toolCount).toBe(2);
    expect(matrix.rows[2].cells[1]?.frames.map((f) => f.file)).toEqual(["ticket.webp"]);
    expect(matrix.rows[2].cells[1]?.startMs).toBe(2200);
  });

  test("a failure anchors only to the lane whose device the failing row acted on", () => {
    const trace = sessionTrace();
    trace[6] = row(7, { ts: 3500, ms: 2000, ok: false, err: "ticket never appeared", device: "kitchen" });
    const lanes = traceDeviceLanes(trace);
    const anchor = trace[failureAnchorIndex(trace)];
    const models = lanes.map((lane) => ({ ...buildReportTraceModel(lane.trace, 0), traceT0: 1000 }));
    const matrix = pruneIdleTrailCells(buildTrailMatrix(models, everyShot, isLlmTurn,
      (lane) => (lanes[lane].device === (anchor.device ?? null) ? anchor : null)));
    const kitchenCell = matrix.rows[2].cells[1];
    expect(kitchenCell?.ok).toBe(false);
    expect(kitchenCell?.failureAtMs).toBe(2500);
    // The storefront lane reads idle there — it was abandoned, it didn't die.
    expect(matrix.rows[2].cells[0]).toBeNull();
  });

  test("a step where no device acted (pure narration) disappears from the split", () => {
    const trace = [
      row(1, { objective: true, label: "Act", ts: 1000, device: "storefront" }),
      row(2, { ts: 1000, ms: 100, device: "storefront" }),
      row(3, { label: "switchDevice", ts: 1200, ms: 50, device: "kitchen" }),
      row(4, { objective: true, label: "Ponder", ts: 2000, device: "kitchen" }),
      row(5, { tool: "agent step", ts: 2000, ms: 50, device: "kitchen" }),
    ];
    const lanes = traceDeviceLanes(trace);
    const models = lanes.map((lane) => ({ ...buildReportTraceModel(lane.trace, 0), traceT0: 1000 }));
    const matrix = pruneIdleTrailCells(buildTrailMatrix(models, everyShot, isLlmTurn));
    expect(matrix.rows.map((r) => r.num)).toEqual([1]);
  });
});

// ── Which trails a document can offer a Trail view for ────────────────────────────────────────
// The view used to be all-or-nothing per DOCUMENT: a report holding more than one trail offered it
// for none of them, even when a trail inside it had run on several devices. These pin the per-trail
// rule that replaced it.
describe("trailViewScopes", () => {
  const run = (patch: Partial<TrailCandidate> = {}): TrailCandidate =>
    ({ key: "trail:a:", skipped: false, linkOut: false, hydrated: true, hasTrace: true, ...patch });

  test("a trail that ran on several devices is comparable inside a many-trail report", () => {
    const scopes = trailViewScopes([
      run({ key: "trail:a:" }), run({ key: "trail:b:" }),
      run({ key: "trail:a:" }), run({ key: "trail:c:" }),
    ]);
    // Trail a's two runs become its lanes, in document order. b and c ran once — still comparable,
    // a one-lane trail is the single-run report's own case.
    expect(scopes.get("trail:a:")).toEqual([0, 2]);
    expect(scopes.get("trail:b:")).toEqual([1]);
    expect(Array.from(scopes.keys()).sort()).toEqual(["trail:a:", "trail:b:", "trail:c:"]);
  });

  test("a run with no trace takes only ITS trail out, not the whole document", () => {
    const scopes = trailViewScopes([
      run({ key: "trail:a:", hasTrace: false }), run({ key: "trail:a:" }), run({ key: "trail:b:" }),
    ]);
    expect(scopes.has("trail:a:")).toBe(false);
    expect(scopes.get("trail:b:")).toEqual([2]);
  });

  test("a link-out stub disqualifies its own trail", () => {
    expect(trailViewScopes([run({ linkOut: true })]).size).toBe(0);
    expect(trailViewScopes([run({ linkOut: true }), run({ key: "trail:b:" })]).size).toBe(1);
  });

  test("an unhydrated run is offered — its trace is unknown, not empty", () => {
    // A chunked report parses traces on first open, so on a fresh load every run looks traceless.
    // Judging those empty would hide the entry points on exactly the big CI reports the view is
    // for, and would make the run index sprout buttons as chunks land.
    expect(trailViewScopes([run({ hydrated: false, hasTrace: false })]).get("trail:a:")).toEqual([0]);
    // Once parsed, a genuinely empty run is judged on what it turned out to hold.
    expect(trailViewScopes([run({ hydrated: true, hasTrace: false })]).size).toBe(0);
  });

  test("a title identity joins no runs — only an explicit trailId coalesces", () => {
    // The run index gives a trailId-less run a key of its own (indexGroupKey), because same-title
    // runs can be independent histories rather than one trail's devices. Staging them as one
    // comparison would contradict the rows the reader is looking at, and each of those rows would
    // carry a button opening it.
    expect(trailViewScopes([run({ key: "title:Checkout:" }), run({ key: "title:Checkout:" })]).size).toBe(0);
    // A title still identifies a run to itself — the single unidentified run keeps its view.
    expect(trailViewScopes([run({ key: "title:Checkout:" })]).get("title:Checkout:")).toEqual([0]);
    // The same shape with a real trailId is a genuine multi-device comparison.
    expect(trailViewScopes([run({ key: "trail:checkout:" }), run({ key: "trail:checkout:" })]).get("trail:checkout:")).toEqual([0, 1]);
  });

  test("a skipped run is excluded from its trail's lanes rather than disqualifying it", () => {
    // A held-back run is a stub with no trace. Counting it would take the comparison away from the
    // devices that DID run, which is the reader losing the view because one device opted out.
    const scopes = trailViewScopes([
      run({ key: "trail:a:", skipped: true, hasTrace: false, linkOut: true }),
      run({ key: "trail:a:" }),
    ]);
    expect(scopes.get("trail:a:")).toEqual([1]);
  });

  test("a trail every device skipped has no lanes and is not offered", () => {
    expect(trailViewScopes([run({ skipped: true }), run({ skipped: true })]).size).toBe(0);
  });

  test("runs that name no trail are never comparable", () => {
    // Two unnamed runs are not "the same trail" — they are unidentified, and aligning them would
    // invent a correspondence between unrelated steps.
    expect(trailViewScopes([run({ key: "" }), run({ key: "" })]).size).toBe(0);
  });
});

describe("trailJoinFor", () => {
  test("only an explicit shared trailId earns the step join", () => {
    // The join decides what a ROW claims. "Step 3" across lanes is a claim about an authored spine,
    // so it is made for one trailId's runs and for nothing else — the same line trailViewScopes
    // draws, and the run index before it.
    expect(trailJoinFor(["trail:checkout:", "trail:checkout:"])).toBe("step");
    expect(trailJoinFor(["trail:checkout:", "trail:refunds:"])).toBe("position");
    // Two runs that share only a title can be independent histories.
    expect(trailJoinFor(["title:Checkout:", "title:Checkout:"])).toBe("position");
    // Same trail, different targets: two identities, so positional.
    expect(trailJoinFor(["trail:checkout:kiosk", "trail:checkout:storefront"])).toBe("position");
  });

  test("runs that name no trail at all are positional, not one nameless trail", () => {
    // Unidentified runs are not "the same trail" — an empty identity is the absence of one, so
    // reading it as a shared key would align steps that have nothing to do with each other.
    expect(trailJoinFor(["", ""])).toBe("position");
    expect(trailJoinFor(["", "trail:checkout:"])).toBe("position");
  });

  test("a lane on its own keeps its own spine", () => {
    // There is nothing to line it up against, so there is nothing to disclaim: a single run staged
    // alone still shows its authored step labels, whatever identity it carries.
    expect(trailJoinFor(["title:Checkout:"])).toBe("step");
    expect(trailJoinFor([""])).toBe("step");
    expect(trailJoinFor([])).toBe("step");
  });
});

describe("trailIdentity", () => {
  test("the same trail YAML against two targets is two trails", () => {
    // Aligning them would put step 3 of one app beside step 3 of another.
    expect(trailIdentity({ trailId: "t", target: "storefront" }))
      .not.toBe(trailIdentity({ trailId: "t", target: "kiosk" }));
  });

  test("title identifies a run that carries no trailId", () => {
    expect(trailIdentity({ title: "Sell an item" })).toBe(trailIdentity({ title: "Sell an item" }));
    expect(trailIdentity({ title: "Sell an item" })).not.toBe(trailIdentity({ title: "Refund" }));
  });

  test("trailId wins over title, so a retitled run still joins its own trail", () => {
    expect(trailIdentity({ trailId: "t", title: "one" })).toBe(trailIdentity({ trailId: "t", title: "two" }));
  });

  test("a run with neither has no identity", () => {
    expect(trailIdentity({})).toBe("");
    expect(trailIdentity(null)).toBe("");
  });

  test("identities are escaped, so a colon in a trail id cannot forge another trail's key", () => {
    expect(trailIdentity({ trailId: "a:b", target: "" })).not.toBe(trailIdentity({ trailId: "a", target: "b" }));
  });
});
