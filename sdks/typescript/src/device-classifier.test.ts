import { describe, expect, test } from "bun:test";

import { trailblaze as inProcess, type DeviceClassifierInput } from "./in-process.js";
import { trailblaze as full } from "./sub-process.js";

const phone: DeviceClassifierInput = { platform: "android", key: ["android", "phone"] };

const acme = inProcess.deviceClassifier((device) => (device.manufacturer === "Acme" ? ["acme", "k2"] : device.key));

describe("trailblaze.deviceClassifier", () => {
  test("returns the whole key", () => {
    expect(acme({ ...phone, manufacturer: "Acme" })).toEqual(["acme", "k2"]);
    expect(acme(phone)).toEqual(["android", "phone"]);
  });

  test("can read the device's model", () => {
    const byModel = inProcess.deviceClassifier((device) =>
      device.model?.startsWith("Acme Kiosk") ? ["acme", "kiosk"] : device.key,
    );
    expect(byModel({ ...phone, model: "Acme Kiosk 2" })).toEqual(["acme", "kiosk"]);
  });

  test("rejects a value that can't be part of a key", () => {
    const bad = inProcess.deviceClassifier((device) => [...device.key, "Test-Rack"]);
    expect(() => bad(phone)).toThrow(/lowercase letters, digits and _/);
  });

  test("rejects a result that isn't an array", () => {
    const bad = inProcess.deviceClassifier(() => "android-phone" as unknown as string[]);
    expect(() => bad(phone)).toThrow(/array/);
  });

  test("is the same function on both profiles", () => {
    expect(full.deviceClassifier).toBe(inProcess.deviceClassifier);
  });
});
