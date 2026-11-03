// A log stamped on a device's clock reads on the host clock, where the report places its step, so a
// time copied from it (a survey's evidence, a derived row) lines up with that step.

import { afterAll, expect, test } from "bun:test";
import { mkdtempSync, rmSync, symlinkSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { SurveySession } from "./index.js";
import { hostTimesOf } from "./log-clock.js";

const LOG = "xyz.block.trailblaze.logs.client.TrailblazeLog";
const iso = (ms: number) => new Date(ms).toISOString();
const dirs: string[] = [];
afterAll(() => dirs.forEach((d) => rmSync(d, { recursive: true, force: true })));

function session(logs: object[]): SurveySession {
  const dir = mkdtempSync(join(tmpdir(), "log-clock-"));
  dirs.push(dir);
  logs.forEach((log, i) => writeFileSync(join(dir, `${String(i + 1).padStart(3, "0")}_Log.json`), JSON.stringify(log)));
  return SurveySession.load(dir);
}

const tool = (timestamp: number, extra: object) => ({
  class: `${LOG}.TrailblazeToolLog`,
  session: "s",
  timestamp: iso(timestamp),
  toolName: "tapOn",
  trailblazeTool: { toolName: "tapOn", raw: {} },
  successful: true,
  durationMs: 100,
  ...extra,
});

test("a device-stamped tool log reads on the host clock its receipt anchors", () => {
  // The device's clock runs 5s behind: the host received the log 5s after the device says it ended.
  const s = session([tool(1_000_000, { clock: "device", hostReceivedAt: iso(1_005_100) })]);
  expect(s.tools.all().map((t) => t.timeMs)).toEqual([1_005_000]);
  expect(s.trailblazeLogs.all().map((l) => l.timeMs)).toEqual([1_005_000]);
});

test("host-stamped logs and device logs nothing anchors keep their own times", () => {
  expect(session([tool(2_000_000, {})]).tools.all().map((t) => t.timeMs)).toEqual([2_000_000]);
  expect(session([tool(3_000_000, { clock: "device" })]).tools.all().map((t) => t.timeMs)).toEqual([3_000_000]);
});

test("a session with more anchored tool logs than a call takes arguments still loads", () => {
  const n = 200_000;
  const logs = Array.from({ length: n }, (_, i) => ({ class: `${LOG}.TrailblazeToolLog`, timestamp: iso(1_000_000 + i), clock: "device", hostReceivedAt: iso(1_005_100 + i), durationMs: 100 }));
  const times = hostTimesOf(logs);
  expect(times[0]).toBe(1_005_000);
  expect(times[n - 1]).toBe(1_005_000 + n - 1);
});

test("a span the device recorded takes the logs' offset when the clock file did not measure the device", () => {
  const anchored = tool(1_000_000, { clock: "device", hostReceivedAt: iso(1_005_100) });
  const dir = mkdtempSync(join(tmpdir(), "trace-clock-"));
  dirs.push(dir);
  writeFileSync(join(dir, "001_Log.json"), JSON.stringify(anchored));
  writeFileSync(join(dir, "trace.json"), JSON.stringify([{ name: "tap", cat: "driver", ph: "X", ts: 2_000_000_000, dur: 1, clock: "device" }]));
  expect(SurveySession.load(dir).trace.all().map((t) => [t.timeMs, t.clock ?? null])).toEqual([[2_005_000, null]]);
  // Measured: the report converts the row from the clock file, so the span stays on the device clock.
  writeFileSync(join(dir, "session_device_clock.json"), JSON.stringify({ devices: [{ deviceId: "d", samples: [{ hostBeforeMs: 0, hostAfterMs: 0, deviceMs: 0 }] }] }));
  expect(SurveySession.load(dir).trace.all().map((t) => [t.timeMs, t.clock ?? null])).toEqual([[2_000_000, "device"]]);
});

test("a clock file whose start device has no usable reading leaves the span on the logs' offset", () => {
  const anchored = tool(1_000_000, { clock: "device", hostReceivedAt: iso(1_005_100) });
  const dir = mkdtempSync(join(tmpdir(), "clock-invalid-"));
  dirs.push(dir);
  writeFileSync(join(dir, "001_Log.json"), JSON.stringify(anchored));
  writeFileSync(join(dir, "trace.json"), JSON.stringify([{ name: "tap", cat: "driver", ph: "X", ts: 2_000_000_000, dur: 1, clock: "device" }]));
  for (const devices of [[{ deviceId: "d", samples: [{}] }], [{ samples: [{ hostBeforeMs: 0, hostAfterMs: 0, deviceMs: 0 }] }]]) {
    writeFileSync(join(dir, "session_device_clock.json"), JSON.stringify({ devices }));
    expect(SurveySession.load(dir).trace.all().map((t) => [t.timeMs, t.clock ?? null])).toEqual([[2_005_000, null]]);
  }
});

test("a clock file that is a link or too big is not read", () => {
  const anchored = tool(1_000_000, { clock: "device", hostReceivedAt: iso(1_005_100) });
  const dir = mkdtempSync(join(tmpdir(), "clock-link-"));
  dirs.push(dir);
  writeFileSync(join(dir, "001_Log.json"), JSON.stringify(anchored));
  writeFileSync(join(dir, "trace.json"), JSON.stringify([{ name: "tap", cat: "driver", ph: "X", ts: 2_000_000_000, dur: 1, clock: "device" }]));
  const measured = join(dir, "elsewhere.json");
  writeFileSync(measured, JSON.stringify({ devices: [{ deviceId: "d", samples: [{ hostBeforeMs: 0, hostAfterMs: 0, deviceMs: 0 }] }] }));
  symlinkSync(measured, join(dir, "session_device_clock.json"));
  // Not read, so the span takes the logs' offset.
  expect(SurveySession.load(dir).trace.all().map((t) => t.timeMs)).toEqual([2_005_000]);
  rmSync(join(dir, "session_device_clock.json"));
  writeFileSync(join(dir, "session_device_clock.json"), " ".repeat(4 * 1024 * 1024 + 1));
  expect(SurveySession.load(dir).trace.all().map((t) => t.timeMs)).toEqual([2_005_000]);
});
