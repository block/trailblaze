import { describe, expect, test } from "bun:test";
import TargetPickerModel from "./target-picker-model.js";

describe("buildTargetGroups", () => {
  const web = { id: "browser", name: "Chromium", platform: "web", connected: true };
  const android = { id: "phone", name: "Pixel", platform: "android", connected: true };

  test("makes a web-only target selectable on browser devices", () => {
    const result = TargetPickerModel.buildTargetGroups({
      deviceList: [web],
      trailmapList: [
        { id: "storefront", displayName: "Storefront", platforms: ["web"], workspaceListed: true },
      ],
    });

    expect(result.hasDeclaredWebTargets).toBe(true);
    expect(result.groupList).toEqual([
      { id: "storefront", label: "Storefront", items: [{ device: web, app: null }], platforms: ["web"] },
    ]);
  });

  test("merges browser devices into a cross-platform target", () => {
    const installed = { id: "checkout", displayName: "Checkout", appId: "com.example.checkout" };
    const result = TargetPickerModel.buildTargetGroups({
      deviceList: [android, web],
      appsByDevice: { phone: [installed] },
      trailmapList: [
        { id: "checkout", displayName: "Checkout", platforms: ["android", "web"], workspaceListed: true },
      ],
    });

    expect(result.groupList[0].items).toEqual([
      { device: android, app: installed },
      { device: web, app: null },
    ]);
  });

  test("keeps a web target disabled while it awaits daemon restart", () => {
    const result = TargetPickerModel.buildTargetGroups({
      deviceList: [web],
      trailmapList: [
        { id: "new-site", displayName: "New site", platforms: ["web"], workspaceListed: true },
      ],
      restartIds: ["new-site"],
    });

    expect(result.hasDeclaredWebTargets).toBe(true);
    expect(result.groupList[0].items).toEqual([]);
  });

  test("surfaces the declared platforms so an empty card can explain itself", () => {
    // No browser connected: the web target has no items, but the card needs its declared
    // platforms to say "no browser connected" instead of "app not installed".
    const result = TargetPickerModel.buildTargetGroups({
      deviceList: [android],
      appsByDevice: { phone: [] },
      trailmapList: [
        { id: "storefront", displayName: "Storefront", platforms: ["web"], workspaceListed: true },
        { id: "native", displayName: "Native", platforms: ["android"], workspaceListed: true },
      ],
    });

    const storefront = result.groupList.find((g) => g.id === "storefront");
    const native = result.groupList.find((g) => g.id === "native");
    expect(storefront.items).toEqual([]);
    expect(storefront.platforms).toEqual(["web"]);
    expect(native.items).toEqual([]);
    expect(native.platforms).toEqual(["android"]);
  });

  test("does not surface targets excluded by the workspace allow-list", () => {
    const result = TargetPickerModel.buildTargetGroups({
      deviceList: [web],
      trailmapList: [
        { id: "hidden", displayName: "Hidden", platforms: ["web"], workspaceListed: false },
      ],
    });

    expect(result.hasDeclaredWebTargets).toBe(false);
    expect(result.groupList).toEqual([]);
  });
});

describe("hasTargetSelection", () => {
  test("treats the anonymous Web choice as a real selection", () => {
    expect(TargetPickerModel.hasTargetSelection({ target: null, label: "Web", deviceIds: ["browser"] })).toBe(true);
  });

  test("does not treat an empty persisted shape as selected", () => {
    expect(TargetPickerModel.hasTargetSelection({ target: null, label: null, deviceIds: [] })).toBe(false);
  });
});
