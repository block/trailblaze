// Fixture tests for the bundled crash formatter: the exact NDJSON lines CrashEventArtifactWriter
// writes go through the same pipeline the report driver runs (buildEventStream) with the formatter
// attached, so a producer-side rename would fail here before it turned crashes back into raw JSON.
// Run: `bun test crash-formatter.test.ts` from this directory.
import { describe, expect, test } from "bun:test";

import { buildEventStream } from "./run-report-events";
import crashFormatter from "./event-formatters/crash.formatter";

const envelope = (timeMs: number, data: unknown) => JSON.stringify({ timeMs, data });
const field = (row: any, k: string): string | undefined => (row.fields || []).find((f: any) => f.k === k)?.v;
const rowsIn = (file: string, ...data: unknown[]) =>
  buildEventStream(file, data.map((d, i) => envelope(1_772_846_522_234 + i, d)), [crashFormatter])!.rows!;
const rowsFor = (...data: unknown[]) => rowsIn("crash.ndjson", ...data);

// One Android event exactly as CrashEventArtifactWriter.CrashEvent.payload writes it.
const ANDROID_CRASH = {
  kind: "fatal_exception",
  platform: "android",
  summary: "FATAL EXCEPTION: main",
  source: { path: "device.log", line: 812 },
};

describe("crash formatter", () => {
  test("a crash is an error row at its log instant, naming the kind and the log line", () => {
    const [row] = rowsFor(ANDROID_CRASH);
    expect(row.t).toBe(1_772_846_522_234);
    expect(row.label).toBe("FATAL EXCEPTION: main");
    expect(row.tone).toBe("error");
    expect((row.badges || []).map((b: any) => b.text)).toEqual(["Java crash"]);
    expect(field(row, "platform")).toBe("android");
    expect(field(row, "log")).toBe("device.log line 812");
  });

  test("every crash in the stream gets its own row", () => {
    const rows = rowsFor(ANDROID_CRASH, { ...ANDROID_CRASH, kind: "native_crash", summary: "Fatal signal 11 (SIGSEGV)" });
    expect(rows.map((r: any) => r.label)).toEqual(["FATAL EXCEPTION: main", "Fatal signal 11 (SIGSEGV)"]);
    expect(rows.map((r: any) => r.tone)).toEqual(["error", "error"]);
  });

  test("a multi-device session's device-scoped crash stream is formatted the same way", () => {
    const [row] = rowsIn("crash.emulator-5554.ndjson", ANDROID_CRASH);
    expect(row.label).toBe("FATAL EXCEPTION: main");
    expect(row.tone).toBe("error");
  });

  test("an unknown kind or an empty payload is still an error row instead of throwing", () => {
    const [unknown, empty] = rowsFor({ kind: "new_kind", summary: "boom" }, {});
    expect((unknown.badges || []).map((b: any) => b.text)).toEqual(["new_kind"]);
    expect(empty.label).toBe("App crashed");
    expect(empty.tone).toBe("error");
  });
});
