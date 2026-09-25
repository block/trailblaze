// Fixture tests for the bundled memory formatter: the exact NDJSON lines MemoryCapture
// writes go through the same pipeline the report driver runs (buildEventStream) with the
// formatter attached, so a producer-side rename would fail here before it blanked a report.
// Run: `bun test memory-formatter.test.ts` from this directory.
import { describe, expect, test } from "bun:test";

import { buildEventStream, resolveFormatterModule } from "./run-report-events";
import memoryFormatter, { formatDeltaKb, formatKb } from "./event-formatters/memory.formatter";

const envelope = (timeMs: number, data: unknown) => JSON.stringify({ timeMs, data });
const badgeTexts = (row: any): string[] => (row.badges || []).map((b: any) => b.text);
const field = (row: any, k: string): string | undefined => (row.fields || []).find((f: any) => f.k === k)?.v;
const rowFor = (data: unknown) => buildEventStream("memory.ndjson", [envelope(1, data)], [memoryFormatter])!.rows![0];

// One Android event exactly as MemorySnapshot.toEventPayload writes it.
const RUNNING = {
  reason: "after_tool",
  tool: "tapOnElement",
  appId: "com.example.store",
  pid: 17220,
  heapUsedKb: 41_200,
  heapLimitKb: 196_608,
  deltaKb: 2_048,
  gcForced: true,
  deviceAvailableKb: 1_268_432,
  source: "ondevice",
  readMs: 214,
};

describe("memory formatter", () => {
  test("declares the stream name MemoryCapture writes, plus its device-scoped form", () => {
    const formatter = resolveFormatterModule(memoryFormatter);
    expect(formatter).not.toBeNull();
    expect(formatter!.id).toBe("memory");
    expect(formatter!.streams).toEqual(["memory", "memory.*"]);
  });

  test("a running app reads as heap used of the limit with the delta, the tool, the GC and the read cost", () => {
    const stream = buildEventStream("memory.ndjson", [envelope(1_700_000_001_000, RUNNING)], [memoryFormatter]);
    expect(stream).not.toBeNull();
    const row = stream!.rows![0];
    expect(row.t).toBe(1_700_000_001_000);
    expect(row.label).toBe("Heap 40.2 MB of 192.0 MB (+2.0 MB)");
    expect(badgeTexts(row)).toEqual(["after tapOnElement"]);
    expect(field(row, "gc")).toBe("forced");
    expect(field(row, "pid")).toBe("17220");
    expect(field(row, "device free")).toBe("1.21 GB");
    expect(field(row, "via")).toBe("ondevice");
    expect(field(row, "read")).toBe("214 ms");
    expect(row.tone).toBeUndefined();
    expect(row.raw).toEqual([RUNNING]);
  });

  test("no GC and no read time read as such", () => {
    const row = rowFor({ ...RUNNING, gcForced: false, readMs: undefined });
    expect(field(row, "gc")).toBe("not forced");
    expect(field(row, "read")).toBeUndefined();
  });

  test("a periodic sample says the heap changed; a tool-boundary sample without a tool name still reads", () => {
    expect(badgeTexts(rowFor({ ...RUNNING, reason: "memory_changed", tool: undefined }))).toEqual(["heap changed"]);
    expect(badgeTexts(rowFor({ ...RUNNING, reason: "before_tool", tool: "launchApp" }))).toEqual(["before launchApp"]);
    expect(badgeTexts(rowFor({ ...RUNNING, reason: "after_tool", tool: undefined }))).toEqual(["after tool"]);
    const first = rowFor({ ...RUNNING, reason: "first", tool: undefined, deltaKb: undefined });
    expect(badgeTexts(first)).toEqual(["first sample"]);
    expect(first.label).toBe("Heap 40.2 MB of 192.0 MB");
  });

  test("a sample from a device that did not report its own free memory just omits it", () => {
    // iOS never has it (the Mac's RAM is not the device's), and an Android reading whose
    // /proc/meminfo leg was dropped for budget has none either. Neither is a zero.
    expect(field(rowFor({ ...RUNNING, deviceAvailableKb: undefined }), "device free")).toBeUndefined();
  });

  test("an iOS sample reads as its footprint, with no limit and no GC", () => {
    const ios = { reason: "after_tool", tool: "tapOnElement", appId: "com.example.ios", pid: 5950, footprintKb: 65_741, deltaKb: 1_536, source: "simulator" };
    const row = rowFor(ios);
    expect(row.label).toBe("Footprint 64.2 MB (+1.5 MB)");
    expect(badgeTexts(row)).toEqual(["after tapOnElement"]);
    expect(field(row, "gc")).toBeUndefined();
    expect(field(row, "via")).toBe("simulator");
    expect(badgeTexts(rowFor({ ...ios, reason: "memory_changed", tool: undefined }))).toEqual(["footprint changed"]);
    expect(rowFor({ ...ios, deltaKb: 60 * 1024 }).tone).toBe("warn");
  });

  test("a large jump or a heap near its limit is a warn row; ordinary movement is not", () => {
    expect(rowFor({ ...RUNNING, deltaKb: 60 * 1024 }).tone).toBe("warn");
    expect(rowFor({ ...RUNNING, heapUsedKb: 180_000 }).tone).toBe("warn");
    const small = rowFor({ ...RUNNING, deltaKb: -2_048 });
    expect(small.tone).toBeUndefined();
    expect(small.label).toBe("Heap 40.2 MB of 192.0 MB (−2.0 MB)");
  });

  test("the app not running yet reads by name, and a process death is a warn row", () => {
    const idle = rowFor({ reason: "first", appId: "com.example.store", source: "adb", readMs: 40 });
    expect(idle.label).toBe("com.example.store not running");
    expect(badgeTexts(idle)).toEqual(["first sample"]);
    expect(field(idle, "pid")).toBeUndefined();
    expect(field(idle, "gc")).toBeUndefined();
    const died = rowFor({ reason: "process_died", appId: "com.example.store", source: "adb" });
    expect(badgeTexts(died)).toEqual(["process died"]);
    expect(died.tone).toBe("warn");
  });

  test("a live process whose memory would not read is not reported as a dead one", () => {
    // The probes keep the pid when the dump does not parse, precisely so this case stays
    // distinguishable from the app being gone. The row has to keep them apart too.
    const unreadable = rowFor({ reason: "after_tool", tool: "tapOnElement", appId: "com.example.store", pid: 17220, source: "adb" });
    expect(unreadable.label).toBe("com.example.store — memory not readable");
    expect(field(unreadable, "pid")).toBe("17220");
    expect(unreadable.tone).toBeUndefined();
  });

  test("an unknown reason or an empty payload still yields a row instead of throwing", () => {
    const rows = buildEventStream(
      "memory.ndjson",
      [envelope(1, { reason: "constructor" }), envelope(2, null), envelope(3, "not an object")],
      [memoryFormatter],
    )!.rows!;
    expect(rows.map((r) => r.label)).toEqual(["Memory sample", "Memory sample", "Memory sample"]);
    expect(badgeTexts(rows[0])).toEqual(["memory"]);
  });

  test("size formatting picks the unit by magnitude and signs deltas", () => {
    expect(formatKb(512)).toBe("512 kB");
    expect(formatKb(1_536)).toBe("1.5 MB");
    expect(formatKb(2_012_188)).toBe("1.92 GB");
    expect(formatDeltaKb(0)).toBe("±0");
    expect(formatDeltaKb(1_024)).toBe("+1.0 MB");
    expect(formatDeltaKb(-300)).toBe("−300 kB");
  });
});
