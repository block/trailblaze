// Behavioral contracts for the Perfetto export: what ui.perfetto.dev receives when the reader asks
// for it, and how the handshake that delivers it behaves.
import { describe, expect, test } from "bun:test";
import {
  buildPerfettoTrace,
  flattenThread,
  handToPerfetto,
  nestThread,
  openPerfettoWindow,
  perfettoBuffer,
  perfettoMemoryFrom,
  perfettoTraceJson,
  streamRowSizes,
  PERFETTO_FIRST_SPAN_THREAD,
  PERFETTO_FIRST_STREAM_THREAD,
  PERFETTO_HEAP_COUNTER,
  PERFETTO_ORIGIN,
  PERFETTO_THREADS,
  type PerfettoHost,
  type PerfettoLane,
  type PerfettoMemory,
  type PerfettoTraceEvent,
  type PerfettoWindow,
} from "./run-report-perfetto";
import { buildReplayMemorySeries, MEMORY_SERIES_FIELDS, type ReplayMemorySeries } from "./run-report-trail-replay";

const T0 = 1_700_000_000_000;

const row = (over: Partial<TraceStep>): TraceStep => ({
  i: 1, label: "row", tool: "tool", note: null, ms: 100, ts: T0, ok: true, err: null, screenshotFile: null,
  objective: false, trailhead: false, count: null, mark: null, ...over,
});

const memory = (): PerfettoMemory => ({
  samples: [
    { atMs: 500, usedKb: 100 * 1024, limitKb: 192 * 1024 },
    { atMs: 2_500, usedKb: 150 * 1024, limitKb: 192 * 1024 },
  ],
  deaths: [3_000],
});

const lane = (over: Partial<PerfettoLane> = {}): PerfettoLane => ({
  name: "android-phone",
  t0: T0,
  steps: [
    { num: 0, label: "Trailhead", startMs: 0, endMs: 1_000, outcome: "passed", headerId: 1 },
    { num: 1, label: "Sign in", startMs: 1_000, endMs: 4_000, outcome: "failed", headerId: 2 },
  ],
  failure: { atMs: 3_500, stepNum: 1, label: "Sign in" },
  trace: [
    row({ i: 1, objective: true, trailhead: true, label: "Trailhead", ts: T0 }),
    // As the timeline holds them: `label` is the tool's name, `tool` the argument crop shown beside it.
    row({ i: 2, label: "launchApp", tool: "com.example.app", ts: T0 + 100, ms: 800, traceId: "abc" }),
    row({ i: 3, objective: true, label: "Sign in", ts: T0 + 1_000 }),
    row({ i: 4, label: "LLM Request", tool: "llm · gpt", llm: 0, ts: T0 + 1_100, ms: 900 }),
    row({ i: 5, label: "tapOnElement", tool: "text: Sign in", ts: T0 + 2_000, ms: 1_200, ok: false, err: "not found",
      children: [{ label: "tap", tool: "x=1", ts: T0 + 2_100, ms: 300, ok: true }, { label: "tap", tool: "x=2", ts: T0 + 2_600, ms: 300, ok: false }] }),
    row({ i: 6, label: "untimed", tool: "", ts: null }),
  ],
  memory: memory(),
  ...over,
});

const byThread = (events: PerfettoTraceEvent[], tid: number, ph = "X") => events.filter((e) => e.tid === tid && e.ph === ph);

describe("what Perfetto receives", () => {
  test("each lane is a process named for its device, with named threads for steps, tools, dispatches and LLM calls", () => {
    const events = buildPerfettoTrace([lane(), lane({ name: "ios-ipad" })]);
    const names = events.filter((e) => e.ph === "M" && e.name === "process_name").map((e) => [e.pid, e.args?.name]);
    expect(names).toEqual([[100, "android-phone"], [200, "ios-ipad"]]);
    // No pid may equal a tid: Perfetto would badge that thread "main thread".
    const tids = new Set(events.map((e) => e.tid));
    for (const [pid] of names) expect(tids.has(pid as number)).toBe(false);
    const threads = events.filter((e) => e.ph === "M" && e.name === "thread_name" && e.pid === 100).map((e) => [e.tid, e.args?.name]);
    expect(threads).toEqual([[1, "Steps"], [2, "Tools"], [3, "Dispatches"], [4, "LLM calls"]]);
    // Lane order is process order, so the reader's left-to-right survives into Perfetto.
    expect(events.filter((e) => e.name === "process_sort_index").map((e) => e.args?.sort_index)).toEqual([0, 1]);
  });

  test("steps become complete events in microseconds, named the way the report names them", () => {
    const steps = byThread(buildPerfettoTrace([lane()]), PERFETTO_THREADS.steps);
    expect(steps.map((e) => [e.name, e.ts, e.dur])).toEqual([
      ["TRAILHEAD · Trailhead", 0, 1_000_000],
      ["STEP 1 · Sign in", 1_000_000, 3_000_000],
    ]);
    expect(steps[1].args).toEqual({ step: 1, label: "Sign in", outcome: "failed" });
  });

  test("tool rows are named for the tool, carry its argument crop, and land on the lane's clock; objective and untimed rows are left out", () => {
    const events = buildPerfettoTrace([lane()]);
    const tools = byThread(events, PERFETTO_THREADS.tools);
    expect(tools.map((e) => [e.name, e.ts, e.dur])).toEqual([["launchApp", 100_000, 800_000], ["tapOnElement", 2_000_000, 1_200_000]]);
    expect(tools[1].args).toEqual({ detail: "text: Sign in", ok: false, error: "not found" });
    expect(tools[0].args?.traceId).toBe("abc");
    expect(events.some((e) => e.name === "untimed")).toBe(false);
    expect(events.some((e) => e.name === "Trailhead" && e.tid === PERFETTO_THREADS.tools)).toBe(false);
  });

  test("an LLM turn is its own thread and a composite's dispatches are theirs, so nothing has to nest", () => {
    const events = buildPerfettoTrace([lane()]);
    expect(byThread(events, PERFETTO_THREADS.llm).map((e) => [e.name, e.ts, e.dur, e.cat, e.args?.detail])).toEqual([["LLM Request", 1_100_000, 900_000, "llm", "llm · gpt"]]);
    expect(byThread(events, PERFETTO_THREADS.dispatches).map((e) => [e.name, e.ts, e.args?.detail, e.args?.parent])).toEqual([["tap", 2_100_000, "x=1", "tapOnElement"], ["tap", 2_600_000, "x=2", "tapOnElement"]]);
  });

  test("the run's failure is an instant on the Steps thread", () => {
    const instants = byThread(buildPerfettoTrace([lane()]), PERFETTO_THREADS.steps, "i");
    expect(instants.map((e) => [e.name, e.ts, e.s])).toEqual([["failed · Sign in", 3_500_000, "t"]]);
  });

  test("memory is a process counter with the limit alongside, and a death drops it to zero", () => {
    const counters = buildPerfettoTrace([lane()]).filter((e) => e.name === PERFETTO_HEAP_COUNTER);
    expect(counters.map((e) => [e.ts, e.args?.used, e.args?.limit])).toEqual([[500_000, 100, 192], [2_500_000, 150, 192], [3_000_000, 0, 192]]);
    const died = buildPerfettoTrace([lane()]).find((e) => e.name === "process died");
    expect([died?.ph, died?.s, died?.ts]).toEqual(["i", "p", 3_000_000]);
  });

  test("a limit that moves mid-run graphs each reading under the ceiling in force at it, including a death's zero", () => {
    // A restarted process can be granted a different heap. Drawing the run under one figure would
    // redraw its earlier ceilings as the last one, so headroom before the change reads wrong.
    const moved = buildPerfettoTrace([lane({ memory: {
      samples: [
        { atMs: 500, usedKb: 100 * 1024, limitKb: 192 * 1024 },
        { atMs: 2_500, usedKb: 150 * 1024, limitKb: 512 * 1024 },
      ],
      deaths: [3_000],
    } })]).filter((e) => e.name === PERFETTO_HEAP_COUNTER);
    expect(moved.map((e) => [e.ts, e.args?.used, e.args?.limit])).toEqual([[500_000, 100, 192], [2_500_000, 150, 512], [3_000_000, 0, 512]]);
    // A death stamped in the reading's own millisecond — capture clamps a boundary row to the last
    // row it emitted — is under the ceiling THAT reading was taken against, not the one before it.
    const tied = buildPerfettoTrace([lane({ memory: {
      samples: [
        { atMs: 500, usedKb: 100 * 1024, limitKb: 192 * 1024 },
        { atMs: 2_500, usedKb: 150 * 1024, limitKb: 512 * 1024 },
      ],
      deaths: [2_500],
    } })]).filter((e) => e.name === PERFETTO_HEAP_COUNTER);
    expect(tied.map((e) => [e.ts, e.args?.used, e.args?.limit])).toEqual([[500_000, 100, 192], [2_500_000, 150, 512], [2_500_000, 0, 512]]);
    // A reading taken before the device reported any limit is graphed without one rather than under
    // a ceiling borrowed from later, and so is a death that precedes every reading.
    const late = buildPerfettoTrace([lane({ memory: {
      samples: [{ atMs: 500, usedKb: 100 * 1024, limitKb: null }, { atMs: 2_500, usedKb: 150 * 1024, limitKb: 192 * 1024 }],
      deaths: [400],
    } })]).filter((e) => e.name === PERFETTO_HEAP_COUNTER);
    expect(late.map((e) => [e.ts, e.args?.used, e.args?.limit])).toEqual([[500_000, 100, undefined], [2_500_000, 150, 192], [400_000, 0, undefined]]);
  });

  test("a lane without memory has no counter, and a lane with no clock origin keeps its steps but not its rows", () => {
    const bare = buildPerfettoTrace([lane({ memory: null, t0: null })]);
    expect(bare.some((e) => e.ph === "C")).toBe(false);
    expect(byThread(bare, PERFETTO_THREADS.steps).length).toBe(2);
    expect(byThread(bare, PERFETTO_THREADS.tools).length).toBe(0);
  });

  test("every event stream is a thread named for it, its rows instants that carry the payload; untimed rows stay out", () => {
    const events = buildPerfettoTrace([lane({ streams: [
      { name: "network", rows: [{ t: T0 + 200, label: "GET /ping · 200", data: { method: "GET", statusCode: 200 } }, { t: null, label: "untimed" }] },
      { name: "app-log", rows: [{ t: T0 + 300, label: "W  low memory" }] },
    ] })]);
    const names = events.filter((e) => e.name === "thread_name").map((e) => [e.tid, e.args?.name]);
    expect(names).toContainEqual([PERFETTO_FIRST_STREAM_THREAD, "network"]);
    expect(names).toContainEqual([PERFETTO_FIRST_STREAM_THREAD + 1, "app-log"]);
    const network = byThread(events, PERFETTO_FIRST_STREAM_THREAD, "i");
    expect(network.map((e) => [e.name, e.ts, e.cat, e.args?.data])).toEqual([["GET /ping · 200", 200_000, "stream:network", { method: "GET", statusCode: 200 }]]);
    expect(byThread(events, PERFETTO_FIRST_STREAM_THREAD + 1, "i").map((e) => e.name)).toEqual(["W  low memory"]);
    // A stream thread never collides with the fixed ones.
    expect(PERFETTO_FIRST_STREAM_THREAD).toBeGreaterThan(Math.max(...Object.values(PERFETTO_THREADS)));
  });

  test("a stream row's size fields graph as counters — one track per field — so a memory reading is a heap graph, not just a marker", () => {
    const events = buildPerfettoTrace([lane({ memory: null, streams: [{ name: "memory", rows: [
      // A raw stream row: the producer's record inside the line's envelope.
      { t: T0 + 200, label: "memory", data: { timeMs: T0 + 200, data: { reason: "first", pid: 14245, heapUsedKb: 5301, heapLimitKb: 196608, readMs: 174 } } },
      // A formatted row: the record itself, here from a device that reports a footprint.
      { t: T0 + 700, label: "after launchApp", data: { reason: "after_tool", pid: 94938, footprintKb: 33792, deltaKb: -14336 } },
      { t: T0 + 900, label: "no sizes", data: { reason: "note", text: "nothing numeric" } },
    ] }] })]);
    const counters = events.filter((e) => e.ph === "C" && e.name === "memory");
    expect(counters.map((e) => [e.ts, e.args])).toEqual([
      [200_000, { heapUsedKb: 5301, heapLimitKb: 196608 }],
      // The delta since the last reading is not a level, so it is not graphed.
      [700_000, { footprintKb: 33792 }],
    ]);
    // The row is still an instant on the stream's thread, and a size-less row adds no counter.
    expect(byThread(events, PERFETTO_FIRST_STREAM_THREAD, "i").map((e) => e.name)).toEqual(["memory", "after launchApp", "no sizes"]);
    // Durations and ids are not levels: a network row graphs nothing.
    expect(streamRowSizes({ method: "GET", statusCode: 200, durationMs: 5, bytes: 10 })).toBeNull();
    expect(streamRowSizes("a line of text")).toBeNull();
    // A delta is excluded wherever it appears in the name, not only as a prefix. Graphing one as a
    // counter draws a line holding the CHANGE until the next row, which says the opposite of what
    // the number means — and next to a real heap track it reads as a second, much flatter heap.
    expect(streamRowSizes({ heapUsedKb: 5301, heapDeltaKb: 12, deltaKb: 12 })).toEqual({ heapUsedKb: 5301 });
    // Inner wins over outer on the same key: the producer's own record is the reading, and the
    // envelope around it can carry a stale or summarised copy.
    expect(streamRowSizes({ heapUsedKb: 9, data: { heapUsedKb: 5 } })).toEqual({ heapUsedKb: 5 });
    // Not a finite number, and not a record: neither is a level.
    expect(streamRowSizes({ heapUsedKb: Number.NaN })).toBeNull();
    expect(streamRowSizes([{ heapUsedKb: 5 }])).toBeNull();
  });

  test("a field a lane's memory counter already graphs is dropped from the stream's counter; the rest of the row still graphs", () => {
    // An Android reading: the heap and its limit, which the dedicated track draws in MB, plus how
    // much the DEVICE had free, which nothing else draws.
    const rows = [{ t: T0 + 200, label: "heap 5 MB", data: { reason: "first", heapUsedKb: 5301, heapLimitKb: 196608, deviceAvailableKb: 902_144 } }];
    const graphed = buildPerfettoTrace([lane({ streams: [{ name: "memory", rows, graphed: ["heapUsedKb", "heapLimitKb"] }] })]);
    const ungraphed = buildPerfettoTrace([lane({ streams: [{ name: "memory", rows }] })]);
    // The duplicate is gone, the device's free memory is not: suppressing the whole row would take
    // the only track that figure has with it.
    expect(graphed.filter((e) => e.ph === "C" && e.name === "memory").map((e) => e.args)).toEqual([{ deviceAvailableKb: 902_144 }]);
    expect(ungraphed.filter((e) => e.ph === "C" && e.name === "memory").map((e) => e.args)).toEqual([{ heapUsedKb: 5301, heapLimitKb: 196608, deviceAvailableKb: 902_144 }]);
    // Dropping a field costs the row neither its instant nor the lane's own counter.
    expect(byThread(graphed, PERFETTO_FIRST_STREAM_THREAD, "i").map((e) => e.name)).toEqual(["heap 5 MB"]);
    expect(graphed.filter((e) => e.name === PERFETTO_HEAP_COUNTER).length).toBe(3);
    // A row whose every size field is already graphed adds no counter at all, rather than an empty one.
    const allGraphed = buildPerfettoTrace([lane({ streams: [{ name: "memory", rows: [{ t: T0 + 200, label: "heap", data: { heapUsedKb: 5301 } }], graphed: ["heapUsedKb"] }] })]);
    expect(allGraphed.some((e) => e.ph === "C" && e.name === "memory")).toBe(false);
  });

  const spanAt = (over: Partial<TracerSpan>): TracerSpan => ({ name: "span", cat: "tool", ts: T0 * 1000, dur: 1_000, tid: 92, ...over });

  test("the session's traced spans keep the thread they were recorded on, so a tool call nests the driver calls it made", () => {
    const events = buildPerfettoTrace([lane({ spans: [
      spanAt({ name: "tapOnElementBySelector", cat: "tool", ts: (T0 + 2_000) * 1000, dur: 1_200_000, args: { selector: "text: Sign in" } }),
      // Opened inside the tool on the same thread; its separately-stamped end runs 50ms past the tool's.
      spanAt({ name: "tap", cat: "MaestroDriver", ts: (T0 + 2_100) * 1000, dur: 1_150_000, tid: 92 }),
      spanAt({ name: "captureScreenState", cat: "screenState", ts: (T0 + 500) * 1000, dur: 300_000, tid: 30, kind: "CLIENT" }),
    ] })]);
    const names = events.filter((e) => e.name === "thread_name").map((e) => [e.tid, e.args?.name]);
    // One thread per recording thread, in order of first appearance, after the fixed and stream threads.
    expect(names).toContainEqual([PERFETTO_FIRST_SPAN_THREAD, "host thread 92"]);
    expect(names).toContainEqual([PERFETTO_FIRST_SPAN_THREAD + 1, "host thread 30"]);
    expect(PERFETTO_FIRST_SPAN_THREAD).toBeGreaterThan(PERFETTO_FIRST_STREAM_THREAD);
    // On the lane's clock, nested as recorded, the child trimmed to its parent's end.
    expect(byThread(events, PERFETTO_FIRST_SPAN_THREAD).map((e) => [e.name, e.cat, e.ts, e.dur]))
      .toEqual([["tapOnElementBySelector", "tool", 2_000_000, 1_200_000], ["tap", "MaestroDriver", 2_100_000, 1_100_000]]);
    expect(byThread(events, PERFETTO_FIRST_SPAN_THREAD + 1).map((e) => [e.name, e.ts, e.args?.spanKind])).toEqual([["captureScreenState", 500_000, "CLIENT"]]);
    expect(byThread(events, PERFETTO_FIRST_SPAN_THREAD)[0].args).toEqual({ selector: "text: Sign in" });
    // The report's own threads are untouched by the spans.
    expect(byThread(events, PERFETTO_THREADS.tools).map((e) => e.name)).toEqual(["launchApp", "tapOnElement"]);
  });

  test("an async span — an HTTP call, observed from a callback thread — is an async slice matched by id, so overlapping calls never mis-nest", () => {
    const events = buildPerfettoTrace([lane({ spans: [
      spanAt({ name: "POST /v1/chat", cat: "http", ts: (T0 + 1_100) * 1000, dur: 900_000, tid: 61, args: { async: "true", method: "POST", status: "200" } }),
      spanAt({ name: "POST /agentlog", cat: "http", ts: (T0 + 1_200) * 1000, dur: 5_000, tid: 61, args: { async: "true", method: "POST" } }),
    ] })]);
    const begins = events.filter((e) => e.ph === "b");
    const ends = events.filter((e) => e.ph === "e");
    expect(begins.map((e) => [e.name, e.cat, e.ts, e.pid])).toEqual([["POST /v1/chat", "http", 1_100_000, 100], ["POST /agentlog", "http", 1_200_000, 100]]);
    expect(ends.map((e) => [e.name, e.id2, e.ts])).toEqual([["POST /v1/chat", begins[0].id2, 2_000_000], ["POST /agentlog", begins[1].id2, 1_205_000]]);
    // Process-local ids: Perfetto files the slices under the device, not in a global group.
    expect(begins[0].id2?.local).toBeTruthy();
    expect(begins[0].id2?.local).not.toBe(begins[1].id2?.local);
    expect(begins[0].args).toEqual({ async: "true", method: "POST", status: "200" });
    // No thread was made for the callback thread they happened to be observed on.
    expect(events.filter((e) => e.name === "thread_name").map((e) => e.args?.name)).not.toContain("host thread 61");
    expect(events.filter((e) => e.ph === "X" && e.cat === "http")).toEqual([]);
  });

  test("a span stamped on a device's clock gets its own thread that says so, apart from the host's thread of the same number", () => {
    const events = buildPerfettoTrace([lane({ spans: [
      spanAt({ name: "tap", ts: (T0 + 100) * 1000, tid: 7 }),
      spanAt({ name: "AccessibilityNodeInfo.walk", cat: "driver", ts: (T0 + 3_000) * 1000, tid: 7, clock: "device" }),
    ] })]);
    const names = events.filter((e) => e.name === "thread_name").map((e) => e.args?.name);
    expect(names).toContain("host thread 7");
    expect(names).toContain("device thread 7 (device clock)");
    const device = events.find((e) => e.name === "AccessibilityNodeInfo.walk");
    expect(device?.tid).not.toBe(events.find((e) => e.name === "tap")?.tid);
    expect(device?.args).toEqual({ clock: "device" });
  });

  test("two processes recording into one trace.json keep their same-numbered threads apart, and the names say which process", () => {
    // A run that started while another was still recording: both processes have a thread 92, and
    // the second's span overlaps the first's without being inside it.
    const events = buildPerfettoTrace([lane({ spans: [
      spanAt({ name: "run A tool", ts: (T0 + 100) * 1000, dur: 2_000_000, tid: 92, pid: 111 }),
      spanAt({ name: "run B tool", ts: (T0 + 1_000) * 1000, dur: 2_000_000, tid: 92, pid: 222 }),
    ] })]);
    const a = events.find((e) => e.name === "run A tool");
    const b = events.find((e) => e.name === "run B tool");
    expect(a?.tid).not.toBe(b?.tid);
    // Neither is trimmed against the other: they were never on one thread.
    expect([a?.dur, b?.dur]).toEqual([2_000_000, 2_000_000]);
    const names = events.filter((e) => e.name === "thread_name").map((e) => e.args?.name);
    expect(names).toContain("host 111 thread 92");
    expect(names).toContain("host 222 thread 92");
    // One process (the usual case) keeps the short name.
    const single = buildPerfettoTrace([lane({ spans: [spanAt({ tid: 92, pid: 111 })] })]);
    expect(single.filter((e) => e.name === "thread_name").map((e) => e.args?.name)).toContain("host thread 92");
  });

  test("a recording thread keeps spans nested: a child that ends after its parent is cut at the parent's end, and a gap is left alone", () => {
    const events: PerfettoTraceEvent[] = [
      { name: "child", cat: "t", ph: "X", ts: 200, dur: 900, pid: 1, tid: 9 },
      { name: "parent", cat: "t", ph: "X", ts: 0, dur: 1_000, pid: 1, tid: 9 },
      { name: "later", cat: "t", ph: "X", ts: 1_500, dur: 400, pid: 1, tid: 9 },
    ];
    expect(nestThread(events).map((e) => [e.name, e.ts, e.dur])).toEqual([["parent", 0, 1_000], ["child", 200, 800], ["later", 1_500, 400]]);
    // Two spans starting together: the longer one encloses the shorter, whatever order they came in.
    expect(nestThread([
      { name: "inner", cat: "t", ph: "X", ts: 0, dur: 100, pid: 1, tid: 9 },
      { name: "outer", cat: "t", ph: "X", ts: 0, dur: 500, pid: 1, tid: 9 },
    ]).map((e) => e.name)).toEqual(["outer", "inner"]);
  });

  test("a reading taken before the run's first row is kept: the whole export slides so nothing is negative", () => {
    const early = lane({ streams: [{ name: "memory", rows: [{ t: T0 - 250, label: "heap 90 MB" }] }] });
    const events = buildPerfettoTrace([early, lane({ name: "ios-ipad" })]);
    expect(events.filter((e) => e.ph !== "M").every((e) => e.ts >= 0)).toBe(true);
    // The early reading is at zero, and everything else — in EVERY process — moved by the same 250ms.
    expect(byThread(events, PERFETTO_FIRST_STREAM_THREAD, "i")[0]?.ts).toBe(0);
    expect(events.filter((e) => e.ph === "X" && e.tid === PERFETTO_THREADS.steps).map((e) => e.ts)).toEqual([250_000, 1_250_000, 250_000, 1_250_000]);
    // Nothing to slide when nothing is early.
    expect(byThread(buildPerfettoTrace([lane()]), PERFETTO_THREADS.steps)[0]?.ts).toBe(0);
  });

  test("a lane clock map (the step alignment) moves rows and readings, while steps arrive already moved", () => {
    // Everything after 1s on this lane sits 5s later on the export clock.
    const shifted = lane({ clock: (t) => (t >= 1_000 ? t + 5_000 : t) });
    const events = buildPerfettoTrace([shifted]);
    expect(byThread(events, PERFETTO_THREADS.tools).map((e) => e.ts)).toEqual([100_000, 7_000_000]);
    expect(events.filter((e) => e.name === PERFETTO_HEAP_COUNTER).map((e) => e.ts)).toEqual([500_000, 7_500_000, 8_000_000]);
    expect(byThread(events, PERFETTO_THREADS.steps).map((e) => e.ts)).toEqual([0, 1_000_000]);
  });

  test("a row that runs past the next on its thread is cut at the next's start, so the thread never mis-nests", () => {
    const events: PerfettoTraceEvent[] = [
      { name: "b", cat: "tool", ph: "X", ts: 500, dur: 100, pid: 1, tid: 2 },
      { name: "a", cat: "tool", ph: "X", ts: 0, dur: 800, pid: 1, tid: 2 },
    ];
    expect(flattenThread(events).map((e) => [e.name, e.ts, e.dur])).toEqual([["a", 0, 500], ["b", 500, 100]]);
    // A gap is left alone.
    expect(flattenThread([{ name: "a", cat: "tool", ph: "X", ts: 0, dur: 100, pid: 1, tid: 2 }, { name: "b", cat: "tool", ph: "X", ts: 500, dur: 100, pid: 1, tid: 2 }]).map((e) => e.dur)).toEqual([100, 100]);
    // An instant between two overlapping slices (the failure marker on the Steps thread) does not
    // hide the overlap: the first slice is still cut at the next SLICE's start.
    const withInstant = flattenThread([
      { name: "a", cat: "step", ph: "X", ts: 0, dur: 800, pid: 1, tid: 1 },
      { name: "failed", cat: "failure", ph: "i", s: "t", ts: 300, pid: 1, tid: 1 },
      { name: "b", cat: "step", ph: "X", ts: 500, dur: 100, pid: 1, tid: 1 },
    ]);
    expect(withInstant.map((e) => [e.name, e.ts, e.dur])).toEqual([["a", 0, 500], ["failed", 300, undefined], ["b", 500, 100]]);
  });

  test("the file is Chrome Trace JSON under traceEvents, and the buffer is a standalone ArrayBuffer of it", () => {
    const json = perfettoTraceJson(buildPerfettoTrace([lane()]));
    const parsed = JSON.parse(json);
    expect(Array.isArray(parsed.traceEvents)).toBe(true);
    expect(parsed.displayTimeUnit).toBe("ms");
    const buffer = perfettoBuffer(json);
    expect(buffer instanceof ArrayBuffer).toBe(true);
    expect(new TextDecoder().decode(buffer)).toBe(json);
  });
});

// A fake browser: the opened window records what it was posted, and the test decides when (and
// from which origin) a PONG comes back.
const fakeHost = (opts: { blocked?: boolean } = {}) => {
  const posted: Array<{ message: unknown; origin: string }> = [];
  const win: PerfettoWindow & { closed: boolean } = { closed: false, postMessage: (message, origin) => posted.push({ message, origin }) };
  let handler: ((data: unknown, origin: string, source: unknown) => void) | null = null;
  const timers: Array<{ fn: () => void; ms: number; kind: "interval" | "timeout"; cleared: boolean }> = [];
  const host: PerfettoHost = {
    open: () => (opts.blocked ? null : win),
    onMessage: (h) => { handler = h; return () => { handler = null; }; },
    setInterval: (fn, ms) => { const t = { fn, ms, kind: "interval" as const, cleared: false }; timers.push(t); return t; },
    clearInterval: (t) => { (t as { cleared: boolean }).cleared = true; },
    setTimeout: (fn, ms) => { const t = { fn, ms, kind: "timeout" as const, cleared: false }; timers.push(t); return t; },
    clearTimeout: (t) => { (t as { cleared: boolean }).cleared = true; },
  };
  return {
    host, win, posted, timers,
    // A PONG as the browser would deliver it: from the Perfetto origin, sent by the opened window.
    pong: (origin = PERFETTO_ORIGIN, source: unknown = win) => handler && handler("PONG", origin, source),
    tick: () => timers.filter((t) => t.kind === "interval" && !t.cleared).forEach((t) => t.fn()),
    expire: () => timers.filter((t) => t.kind === "timeout" && !t.cleared).forEach((t) => t.fn()),
    listening: () => handler != null,
  };
};

describe("the memory rail as a Perfetto counter", () => {
  // Real producer rows through the real series builder: which stops are deaths and which are
  // restarts is the builder's judgement, and the export has to read it the same way.
  const at = (ms: number, data: Record<string, unknown>) => ({ t: T0 + ms, data });
  const series = (rows: Array<{ t: number; data: Record<string, unknown> }>) => buildReplayMemorySeries(rows, T0) as ReplayMemorySeries;

  test("a death drops the counter to zero, and a restart — which lands on a live reading — does not", () => {
    const died = series([at(1_000, { heapUsedKb: 4_000, heapLimitKb: 8_000, pid: 7 }), at(3_000, { reason: "after_tool" })]);
    expect(perfettoMemoryFrom(died)).toEqual({ samples: [{ atMs: 1_000, usedKb: 4_000, limitKb: 8_000 }], deaths: [3_000] });
    // A restart is marked at the first reading of the NEW process, because nobody sampled the old
    // one's end — so its instant already carries a live figure, and a zero would sit on top of it.
    const restarted = series([at(1_000, { heapUsedKb: 4_000, pid: 7 }), at(3_000, { heapUsedKb: 900, pid: 9 })]);
    expect(restarted.stops).toEqual([3_000]);
    expect(perfettoMemoryFrom(restarted).deaths).toEqual([]);
  });

  test("a death stamped in the same millisecond as the reading before it still drops the counter", () => {
    const tied = series([at(1_000, { heapUsedKb: 4_000, pid: 7 }), at(1_000, { reason: "after_tool" })]);
    expect(tied.stops).toEqual([1_000]);
    // Capture clamps a boundary reading's stamp to the last row it emitted, so a stop row really can
    // land in the millisecond of the reading before it. Nothing outlived this one.
    expect(tied.samples[0].stopAfterMs).toBe(1_000);
    expect(perfettoMemoryFrom(tied).deaths).toEqual([1_000]);
  });

  test("a restart clamped onto the dead process's own instant is still no downtime", () => {
    // The same clamp can put the new process's first reading on the old one's instant: one reading
    // there died, the other did not, so the instant carries a live figure and no stretch to draw.
    const clamped = series([at(1_000, { heapUsedKb: 4_000, pid: 7 }), at(1_000, { heapUsedKb: 900, pid: 9 })]);
    expect(clamped.stops).toEqual([1_000]);
    expect(clamped.samples.map((sample) => [sample.usedKb, sample.stopAfterMs])).toEqual([[4_000, 1_000], [900, null]]);
    expect(perfettoMemoryFrom(clamped).deaths).toEqual([]);
  });

  test("the fields the export drops from a stream's counter are the ones the series is built from", () => {
    // Held against the builder rather than asserted as a list: each of these lands in the dedicated
    // track, so graphing it again under the stream's name is the duplicate.
    expect(MEMORY_SERIES_FIELDS).toEqual(["heapUsedKb", "footprintKb", "heapLimitKb"]);
    expect(series([at(0, { heapUsedKb: 4_000, heapLimitKb: 8_000 })]).samples[0]).toMatchObject({ usedKb: 4_000, limitKb: 8_000 });
    expect(series([at(0, { footprintKb: 2_000 })]).samples[0]).toMatchObject({ usedKb: 2_000 });
    // And a figure NOT in the list builds no series at all, which is why the stream's own counter is
    // the only place it can be graphed.
    expect(buildReplayMemorySeries([at(0, { deviceAvailableKb: 902_144 })], T0)).toBeNull();
  });
});

const payload = () => ({ buffer: perfettoBuffer("{}"), title: "Trail · 2 devices", fileName: "trace.json" });

describe("handing the trace to ui.perfetto.dev", () => {
  test("the window is opened at Perfetto's origin, and a blocked popup reads as null", () => {
    const open = fakeHost();
    expect(openPerfettoWindow(open.host)).toBe(open.win);
    expect(openPerfettoWindow(fakeHost({ blocked: true }).host)).toBeNull();
  });

  test("PING is knocked until PONG, then the trace is posted once, to Perfetto's origin only", async () => {
    const f = fakeHost();
    const result = handToPerfetto(f.win, f.host, payload());
    f.tick(); f.tick();
    expect(f.posted.map((p) => p.message)).toEqual(["PING", "PING", "PING"]);
    f.pong();
    expect(await result).toBe("opened");
    const trace = f.posted[f.posted.length - 1];
    expect(trace.origin).toBe(PERFETTO_ORIGIN);
    expect((trace.message as { perfetto: { title: string; fileName: string; buffer: ArrayBuffer } }).perfetto.title).toBe("Trail · 2 devices");
    expect((trace.message as { perfetto: { buffer: ArrayBuffer } }).perfetto.buffer instanceof ArrayBuffer).toBe(true);
    // Everything is torn down: no more pings, no listener, no pending deadline.
    expect(f.timers.every((t) => t.cleared)).toBe(true);
    expect(f.listening()).toBe(false);
    f.tick();
    expect(f.posted.length).toBe(4);
  });

  test("a PONG from any other origin is ignored", async () => {
    const f = fakeHost();
    const result = handToPerfetto(f.win, f.host, payload(), 1_000);
    f.pong("https://evil.example");
    expect(f.posted.filter((p) => typeof p.message !== "string").length).toBe(0);
    f.expire();
    expect(await result).toBe("timeout");
  });

  test("a PONG from another Perfetto window is ignored, so two exports in flight each wait for their own tab", async () => {
    const f = fakeHost();
    const result = handToPerfetto(f.win, f.host, payload(), 1_000);
    // The other export's tab answers first — at Perfetto's origin, but not from this window.
    const otherTab = { postMessage: () => {} };
    f.pong(PERFETTO_ORIGIN, otherTab);
    expect(f.posted.filter((p) => typeof p.message !== "string").length).toBe(0);
    f.pong();
    expect(await result).toBe("opened");
    expect(f.posted.filter((p) => typeof p.message !== "string").length).toBe(1);
  });

  test("a window already closed when the handoff starts is reported closed with every timer cleared", async () => {
    const f = fakeHost();
    f.win.closed = true;
    expect(await handToPerfetto(f.win, f.host, payload())).toBe("closed");
    // The first knock finished the handoff; the interval and deadline it would have left ticking are gone.
    expect(f.timers.length).toBe(2);
    expect(f.timers.every((t) => t.cleared)).toBe(true);
    expect(f.listening()).toBe(false);
  });

  test("a Perfetto that never answers times out, and a window the reader closed is reported as such", async () => {
    const f = fakeHost();
    const result = handToPerfetto(f.win, f.host, payload(), 1_000);
    f.expire();
    expect(await result).toBe("timeout");
    expect(f.timers.every((t) => t.cleared)).toBe(true);

    const g = fakeHost();
    const closing = handToPerfetto(g.win, g.host, payload());
    g.win.closed = true;
    g.tick();
    expect(await closing).toBe("closed");
  });
});
