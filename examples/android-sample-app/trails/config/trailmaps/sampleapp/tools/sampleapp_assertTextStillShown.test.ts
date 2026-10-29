// Unit tests for `sampleapp_assertTextStillShown`, the inverted not-visible check. Drives the typed
// tool directly (no daemon, no device) with the mock context + client from
// `@trailblaze/scripting/testing`.
//
// Run via:  ./trailblaze check sampleapp

import { describe, expect, test } from "bun:test";
import { createMockClient, createMockContext } from "@trailblaze/scripting/testing";

import { sampleapp_assertTextStillShown } from "./sampleapp_assertTextStillShown";

const ctx = () =>
  createMockContext({ platform: "android", device: { driverType: "android-ondevice-accessibility" } });

const TEXT = "Name: Jane Doe\nEmail:";

describe("sampleapp_assertTextStillShown", () => {
  test("passes when assertNotVisibleWithText finds the text, and changes nothing on screen", async () => {
    const c = createMockClient();
    c.stub("assertNotVisibleWithText", { textContent: "", errorMessage: `"${TEXT}" still present on screen` });

    const result = await sampleapp_assertTextStillShown({ text: TEXT }, ctx(), c);

    expect(c.calls).toEqual([{ tool: "assertNotVisibleWithText", args: { text: TEXT } }]);
    expect(result).toBe('assertNotVisibleWithText found "Name: Jane Doe\\nEmail:" on screen.');
  });

  test("fails and clears the form when assertNotVisibleWithText reports the text absent", async () => {
    const c = createMockClient();

    await expect(sampleapp_assertTextStillShown({ text: TEXT }, ctx(), c)).rejects.toThrow(
      'assertNotVisibleWithText reported "Name: Jane Doe\\nEmail:" is not on screen.',
    );
    expect(c.calls.map((x) => x.tool)).toEqual(["assertNotVisibleWithText", "tapOnElementBySelector"]);
    expect(c.calls[1].args).toEqual({
      nodeSelector: { containsChild: { androidAccessibility: { textRegex: "Clear All" } } },
    });
  });

  test("rethrows any other assertNotVisibleWithText failure without clearing the form", async () => {
    const c = createMockClient();
    c.stub("assertNotVisibleWithText", { textContent: "", errorMessage: "no complete accessibility tree was captured" });

    await expect(sampleapp_assertTextStillShown({ text: TEXT }, ctx(), c)).rejects.toThrow(
      "no complete accessibility tree was captured",
    );
    expect(c.calls.map((x) => x.tool)).toEqual(["assertNotVisibleWithText"]);
  });
});
