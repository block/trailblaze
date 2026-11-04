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
  type DeviceClockSync,
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

describe("clockTrackForStream on a session with many devices", () => {
  test("finds each stream's device, the longest name winning, without searching every device per lookup", () => {
    const devices = Array.from({ length: 50_000 }, (_, i) => ({ deviceName: `d${i}`, samples: [{ deviceMs: 0, hostMs: i }] }));
    devices.push({ deviceName: "pair.d7", samples: [{ deviceMs: 0, hostMs: -1 }] });
    const sync = { devices } as unknown as DeviceClockSync;
    const started = performance.now();
    for (let i = 0; i < 100_000; i++) expect(clockTrackForStream(sync, `app.screens.d${i % 50_000}`)).toBe(devices[i % 50_000] as never);
    expect(performance.now() - started).toBeLessThan(5_000);
    expect(clockTrackForStream(sync, "network.pair.d7")).toBe(devices[50_000] as never);
  });

  test("a megabyte stream name full of dots costs no more than the devices' own names", () => {
    const devices = [{ deviceName: "d7", samples: [{ deviceMs: 0, hostMs: 0 }] }];
    const sync = { devices } as unknown as DeviceClockSync;
    const started = performance.now();
    expect(clockTrackForStream(sync, "a.".repeat(500_000) + "d7")).toBe(devices[0] as never);
    expect(performance.now() - started).toBeLessThan(1_000);
  });
});
