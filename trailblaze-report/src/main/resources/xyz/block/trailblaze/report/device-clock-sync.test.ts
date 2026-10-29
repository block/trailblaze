// Parity tests for device-clock-sync.ts. The same fixture drives the Kotlin side
// (DeviceClockParityFixturesTest over DeviceClockSync), so a drift in either fails that side.
//
// Run: `bun test device-clock-sync.test.ts` from this directory.
import { describe, expect, test } from "bun:test";
import {
  clockTrackFor,
  clockTrackForStream,
  deviceToHostMs,
  parseDeviceClockSync,
  tightestSample,
  trackToHostMs,
  type DeviceClockSample,
} from "./device-clock-sync";

const fixtures = require("./device-clock-parity-fixtures.json") as {
  tightest: Array<{ samples: DeviceClockSample[]; index: number | null }>;
  conversions: Array<{
    name: string;
    sync: unknown;
    cases: Array<{ deviceMs: number; deviceId: string | null; stream?: string; hostMs: number | null }>;
  }>;
};

describe("cross-language parity fixtures", () => {
  test("the tightest sample is the shortest round trip", () => {
    expect(fixtures.tightest.length).toBeGreaterThan(0);
    for (const c of fixtures.tightest) {
      expect(tightestSample(c.samples)).toBe(c.index == null ? null : c.samples[c.index]);
    }
  });

  for (const group of fixtures.conversions) {
    test(`conversion: ${group.name}`, () => {
      const sync = parseDeviceClockSync(JSON.stringify(group.sync));
      expect(sync).not.toBeNull();
      for (const c of group.cases) {
        const hostMs = c.stream != null
          ? trackToHostMs(clockTrackForStream(sync, c.stream), c.deviceMs)
          : deviceToHostMs(sync, c.deviceMs, c.deviceId);
        expect([c.deviceMs, c.deviceId, c.stream, hostMs]).toEqual([c.deviceMs, c.deviceId, c.stream, c.hostMs]);
      }
    });
  }
});

describe("parseDeviceClockSync", () => {
  test("rejects unreadable input", () => {
    expect(parseDeviceClockSync("not json")).toBeNull();
    expect(parseDeviceClockSync("{}")).toBeNull();
  });

  test("drops malformed samples instead of the whole file", () => {
    const sync = parseDeviceClockSync(
      JSON.stringify({
        devices: [
          {
            deviceId: "a",
            samples: [{ hostBeforeMs: 1, hostAfterMs: 2, deviceMs: "x" }, { hostBeforeMs: 0, hostAfterMs: 0, deviceMs: 10 }],
          },
          { samples: [] },
        ],
      }),
    );
    expect(sync!.devices).toHaveLength(1);
    expect(clockTrackFor(sync, null)!.samples).toEqual([{ hostBeforeMs: 0, hostAfterMs: 0, deviceMs: 10 }]);
  });
});
