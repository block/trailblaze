// Unit tests for the `sampleapp_launchToLoadedContent` TypeScript trailhead. Drives the tool
// directly (no daemon, no device) with the mock context + queued client from
// `@trailblaze/scripting/testing`, pinning the launch → open Loading tab → start load →
// findMatches-wait orchestration on the accessibility driver, and that it refuses a non-
// accessibility driver up front.
//
// Run via:  ./trailblaze check sampleapp

import { describe, expect, test } from "bun:test";
import { createMockContext, createQueuedFindMatchesClient } from "@trailblaze/scripting/testing";
import type { MatchDescriptor, TrailblazeTarget } from "@trailblaze/scripting";

import { sampleapp_launchToLoadedContent } from "./sampleapp_launchToLoadedContent";

const ACCESSIBILITY_DRIVER = "android-ondevice-accessibility";
const INSTRUMENTATION_DRIVER = "android-ondevice-instrumentation";
const APP_ID = "xyz.block.trailblaze.examples.sampleapp";
const MATCH: MatchDescriptor = { indexPath: [0, 1] };

const ctxFor = (driverType: string) => createMockContext({ platform: "android", device: { driverType } });
const names = (c: { calls: Array<{ tool: string }> }) => c.calls.map((x) => x.tool);
const argsOf = (c: { calls: Array<{ tool: string; args: Record<string, unknown> }> }, tool: string) =>
  c.calls.filter((x) => x.tool === tool).map((x) => x.args);

describe("sampleapp_launchToLoadedContent — accessibility driver", () => {
  test("launches, opens Loading, starts the load, then waits via findMatches", async () => {
    const c = createQueuedFindMatchesClient();
    c.queueFindMatches([[MATCH]]); // "Content Loaded" appears

    const result = await sampleapp_launchToLoadedContent({}, ctxFor(ACCESSIBILITY_DRIVER), c);

    expect(names(c)).toEqual([
      "launchApp",
      "tapOnElementBySelector", // Loading tab
      "tapOnElementBySelector", // Start Loading
      "findMatches", // wait for Content Loaded
    ]);
    // Falls back to the module default app id when ctx.target is absent.
    expect(argsOf(c, "launchApp")[0]).toEqual({ appId: APP_ID, launchMode: "FORCE_RESTART" });
    // Taps use anchored androidAccessibility selectors.
    expect(argsOf(c, "tapOnElementBySelector").map((a) => a.nodeSelector)).toEqual([
      { androidAccessibility: { textRegex: "^Loading$" } },
      { androidAccessibility: { textRegex: "^Start Loading$" } },
    ]);
    // The wait is an event-driven findMatches with a budget.
    expect(argsOf(c, "findMatches")[0]).toEqual({
      selector: { androidAccessibility: { textRegex: "^Content Loaded$" } },
      timeoutMs: 30_000,
    });
    expect(result).toBe(
      "Launched xyz.block.trailblaze.examples.sampleapp and landed on the loaded-content screen.",
    );
  });

  test("resolves the app id from ctx.target when present", async () => {
    const c = createQueuedFindMatchesClient();
    c.queueFindMatches([[MATCH]]);
    const target: TrailblazeTarget = {
      id: "sampleapp",
      appIds: ["com.example.custom"],
      appId: "com.example.custom",
      // Ignores the defaultAppId and returns the target-resolved id, so the assertion proves the
      // tool threads ctx.target's resolution through rather than always using the module default.
      resolveAppId: () => "com.example.custom",
      resolveBaseUrl: () => "",
    };
    const ctx = createMockContext({
      platform: "android",
      device: { driverType: ACCESSIBILITY_DRIVER },
      target,
    });

    const result = await sampleapp_launchToLoadedContent({}, ctx, c);

    expect(argsOf(c, "launchApp")[0]).toEqual({
      appId: "com.example.custom",
      launchMode: "FORCE_RESTART",
    });
    expect(result).toBe("Launched com.example.custom and landed on the loaded-content screen.");
  });

  test("throws if the content never appears within the budget", async () => {
    const c = createQueuedFindMatchesClient();
    c.queueFindMatches([[]]); // never appears

    await expect(
      sampleapp_launchToLoadedContent({}, ctxFor(ACCESSIBILITY_DRIVER), c),
    ).rejects.toThrow(/"Content Loaded" did not appear within 30s/);
  });
});

describe("sampleapp_launchToLoadedContent — driver scope", () => {
  test("refuses a non-accessibility driver before touching the device", async () => {
    const c = createQueuedFindMatchesClient();

    await expect(
      sampleapp_launchToLoadedContent({}, ctxFor(INSTRUMENTATION_DRIVER), c),
    ).rejects.toThrow(/targets android-ondevice-accessibility/);
    // Bailed out up front — no tools dispatched.
    expect(c.calls).toHaveLength(0);
  });
});
