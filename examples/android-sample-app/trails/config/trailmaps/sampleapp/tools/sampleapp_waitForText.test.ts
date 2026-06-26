// Unit tests for the `sampleapp_waitForText` "wait until visible" tool. Drives the typed tool
// directly (no daemon, no device) with the mock context + queued client from
// `@trailblaze/scripting/testing`, asserting the dual-driver split:
//   - accessibility driver  -> findMatches({ selector: ^text$, timeoutMs }); empty result throws
//   - instrumentation driver -> maestro({ extendedWaitUntil: { visible: { text }, timeout } })
//
// Run via:  ./trailblaze check sampleapp

import { describe, expect, test } from "bun:test";
import { createMockContext, createQueuedFindMatchesClient } from "@trailblaze/scripting/testing";
import type { MatchDescriptor } from "@trailblaze/scripting";

import { sampleapp_waitForText } from "./sampleapp_waitForText";

const ACCESSIBILITY_DRIVER = "android-ondevice-accessibility";
const INSTRUMENTATION_DRIVER = "android-ondevice-instrumentation";
const MATCH: MatchDescriptor = { indexPath: [0, 1] };

const accessibilityCtx = () =>
  createMockContext({ platform: "android", device: { driverType: ACCESSIBILITY_DRIVER } });
const instrumentationCtx = () =>
  createMockContext({ platform: "android", device: { driverType: INSTRUMENTATION_DRIVER } });

const callsTo = (c: { calls: Array<{ tool: string }> }, tool: string) =>
  c.calls.filter((x) => x.tool === tool);

describe("sampleapp_waitForText — accessibility driver", () => {
  test("waits via findMatches with an anchored selector and returns once the text appears", async () => {
    const c = createQueuedFindMatchesClient();
    c.queueFindMatches([[MATCH]]);

    const result = await sampleapp_waitForText(
      { text: "Content Loaded", timeoutMs: 5_000 },
      accessibilityCtx(),
      c,
    );

    const findMatchesCalls = callsTo(c, "findMatches");
    expect(findMatchesCalls).toHaveLength(1);
    expect(findMatchesCalls[0].args).toEqual({
      selector: { androidAccessibility: { textRegex: "^Content Loaded$" } },
      timeoutMs: 5_000,
    });
    // Never falls through to the Maestro branch on the accessibility driver.
    expect(callsTo(c, "mobile_maestro")).toHaveLength(0);
    expect(result).toBe('"Content Loaded" became visible within 5s.');
  });

  test("throws a clear timeout error when the text never appears", async () => {
    const c = createQueuedFindMatchesClient();
    c.queueFindMatches([[]]); // empty match set == not visible within the budget

    await expect(
      sampleapp_waitForText({ text: "Content Loaded", timeoutMs: 2_000 }, accessibilityCtx(), c),
    ).rejects.toThrow('"Content Loaded" did not become visible within 2s.');
  });

  test("defaults to a 30s budget when timeoutMs is omitted", async () => {
    const c = createQueuedFindMatchesClient();
    c.queueFindMatches([[MATCH]]);

    const result = await sampleapp_waitForText({ text: "Content Loaded" }, accessibilityCtx(), c);

    expect(callsTo(c, "findMatches")[0].args.timeoutMs).toBe(30_000);
    expect(result).toBe('"Content Loaded" became visible within 30s.');
  });

  test("escapes regex metacharacters so the text is matched literally", async () => {
    const c = createQueuedFindMatchesClient();
    c.queueFindMatches([[MATCH]]);

    await sampleapp_waitForText({ text: "Total: $5.00 (USD)" }, accessibilityCtx(), c);

    expect(callsTo(c, "findMatches")[0].args.selector).toEqual({
      androidAccessibility: { textRegex: "^Total: \\$5\\.00 \\(USD\\)$" },
    });
  });
});

describe("sampleapp_waitForText — instrumentation driver", () => {
  test("waits via Maestro extendedWaitUntil rather than findMatches", async () => {
    const c = createQueuedFindMatchesClient();

    const result = await sampleapp_waitForText(
      { text: "Content Loaded", timeoutMs: 4_000 },
      instrumentationCtx(),
      c,
    );

    expect(callsTo(c, "findMatches")).toHaveLength(0);
    const maestroCalls = callsTo(c, "mobile_maestro");
    expect(maestroCalls).toHaveLength(1);
    // Same anchored/regex-escaped value as the accessibility branch (Maestro's text is regex-backed).
    expect(maestroCalls[0].args).toEqual({
      commands: [
        { extendedWaitUntil: { visible: { text: "^Content Loaded$" }, timeout: 4_000 } },
      ],
    });
    expect(result).toBe('"Content Loaded" became visible within 4s.');
  });
});

describe("sampleapp_waitForText — validation", () => {
  test("rejects empty text rather than waiting for nothing", async () => {
    const c = createQueuedFindMatchesClient();
    await expect(
      sampleapp_waitForText({ text: "   " }, accessibilityCtx(), c),
    ).rejects.toThrow(/`text` is required/);
  });
});
