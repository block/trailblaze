// Pure-logic tests for app/trail-platform.js — the Trails screen's platform filter. No browser, no
// DOM. Run: `bun test app/trail-platform.test.ts` from the web/ directory.
import { describe, expect, test } from "bun:test";
// trail-platform.js dual-exports via module.exports; bun interops the CJS default import.
import TP from "./trail-platform.js";

const trail = (over: any = {}) => ({ id: "0/a", path: "a.trail.yaml", kind: "trail", format: "v1", ...over });

describe("trailPlatform", () => {
  test("prefers the declared platform over the file name", () => {
    expect(TP.trailPlatform(trail({ platform: "web", path: "android-phone.trail.yaml" }))).toBe("web");
  });

  test("falls back to the file name when nothing is declared", () => {
    expect(TP.trailPlatform(trail({ path: "case_1/ios-iphone.trail.yaml" }))).toBe("ios");
  });

  test("normalizes a declared platform, which is freeform YAML the author typed", () => {
    expect(TP.trailPlatform(trail({ platform: "Android" }))).toBe("android");
  });

  test("is null when nothing declares or implies a platform", () => {
    expect(TP.trailPlatform(trail({ path: "checkout-flow.trail.yaml" }))).toBeNull();
  });
});

describe("trailMatchesPlatform", () => {
  test("no filter keeps everything", () => {
    expect(TP.trailMatchesPlatform(trail({ platform: "ios" }), null)).toBe(true);
  });

  test("a trail declaring Android is kept by the android filter", () => {
    // The regression: a device reports "android" lowercase, `config.platform` is whatever the
    // author typed, and comparing them raw hid the trail behind "All platforms".
    expect(TP.trailMatchesPlatform(trail({ platform: "Android" }), "android")).toBe(true);
  });

  test("a trail of another platform is dropped", () => {
    expect(TP.trailMatchesPlatform(trail({ platform: "ios" }), "android")).toBe(false);
  });

  test("blazes and unified trails serve every platform", () => {
    expect(TP.trailMatchesPlatform(trail({ kind: "blaze", path: "blaze.yaml" }), "ios")).toBe(true);
    expect(TP.trailMatchesPlatform(trail({ format: "unified", path: "trail.yaml" }), "ios")).toBe(true);
  });

  test("a trail with no platform at all is dropped by a specific filter", () => {
    expect(TP.trailMatchesPlatform(trail({ path: "checkout-flow.trail.yaml" }), "android")).toBe(false);
  });
});

describe("platformScopeFor", () => {
  const androidTrail = trail({ id: "0/a", path: "android-phone.trail.yaml", target: "pos" });
  const iosTrail = trail({ id: "0/b", path: "ios-iphone.trail.yaml", target: "pos" });

  test("applies the platform when a trail in scope carries it", () => {
    expect(TP.platformScopeFor([androidTrail, iosTrail], "pos", "android")).toBe("android");
  });

  test("falls back to all when no trail carries the platform", () => {
    // The bug this guard fixes: the platform comes from the devices picked on another screen, so
    // applying it blind emptied the tree and the reader had to find the filter to get back.
    expect(TP.platformScopeFor([iosTrail], "pos", "android")).toBe("all");
  });

  test("only trails of the target being scoped to count", () => {
    expect(TP.platformScopeFor([androidTrail, trail({ path: "ios-iphone.trail.yaml", target: "kiosk" })], "kiosk", "android")).toBe("all");
  });

  test("an unscoped target considers every trail", () => {
    expect(TP.platformScopeFor([androidTrail], "all", "android")).toBe("android");
  });

  test("no implied platform means no platform filter", () => {
    expect(TP.platformScopeFor([androidTrail], "pos", null)).toBe("all");
  });

  test("a unified trail counts as carrying every platform", () => {
    expect(TP.platformScopeFor([trail({ format: "unified", path: "trail.yaml", target: "pos" })], "pos", "web")).toBe("web");
  });

  // What comes back is a filter value: it gets stored and compared against the picker's options,
  // which are lowercase. Handing back "Android" would leave the control looking unset.
  test("what comes back is normalized, not the caller's casing", () => {
    expect(TP.platformScopeFor([androidTrail], "pos", "Android")).toBe("android");
  });

  test("a declared platform in another casing still counts as in scope", () => {
    expect(TP.platformScopeFor([trail({ platform: "Android", path: "trail.yaml", target: "pos" })], "pos", "android")).toBe("android");
  });
});
