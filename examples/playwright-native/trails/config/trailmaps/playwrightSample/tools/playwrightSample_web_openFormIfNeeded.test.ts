// Sample test exercising `client.stub(name, response)` to drive a conditional branch.
//
// `playwrightSample_web_openFormIfNeeded` probes the page with `web_verifyTextVisible`
// and, on a thrown failure, recovers by clicking the form link and re-verifying. The
// default mock client returns success for every dispatch — to exercise the recovery code
// path under unit test we register a stub that mirrors the production client's failure
// shape (a non-empty `errorMessage` causes the mock to throw with the same wording the
// real client uses, see `unwrapCallbackResponse` in `sdks/typescript/src/client.ts`).
//
// Run via:
//
//   ./trailblaze check playwrightSample

import { describe, expect, test } from "bun:test";
import { createMockClient, createMockContext } from "@trailblaze/scripting/testing";

import { playwrightSample_web_openFormIfNeeded } from "./playwrightSample_web_openFormIfNeeded";

describe("playwrightSample_web_openFormIfNeeded", () => {
  test("returns the already-visible message when the heading probe succeeds", async () => {
    const client = createMockClient();
    const ctx = createMockContext({ platform: "web" });

    const result = await playwrightSample_web_openFormIfNeeded({}, ctx, client);

    // Default mock success on every dispatch — the try-branch returns without invoking
    // the recovery click, so the call list is the two-tool happy path.
    expect(client.calls.map((c) => c.tool)).toEqual([
      "web_navigate",
      "web_verifyTextVisible",
    ]);
    expect(result).toContain("already visible");
  });

  test("attempts the recovery click when the heading probe throws", async () => {
    const client = createMockClient();
    // Stubs persist for every call to the named tool until `client.reset()` — both
    // `web_verifyTextVisible` invocations in the tool body will throw, which means
    // the tool itself ultimately propagates the second failure. That's fine: this test
    // is documenting the stub-as-fault-injection pattern, and asserting on
    // `client.calls` is how you confirm the recovery branch ran even when the overall
    // tool fails. A real test of the recovery's happy path would need a sequence-aware
    // stub (not yet supported by the mock client) or a sibling tool that doesn't share
    // a name with the failure injection point.
    client.stub("web_verifyTextVisible", {
      textContent: "",
      errorMessage: "Text not found",
    });
    const ctx = createMockContext({ platform: "web" });

    // Match the production wrapper format (`tool failed: <errorMessage>`) so a future
    // tweak to the wrapping string in `client.ts` fails this assertion explicitly
    // instead of silently passing on the substring.
    await expect(
      playwrightSample_web_openFormIfNeeded({}, ctx, client),
    ).rejects.toThrow(/tool failed: Text not found/);

    expect(client.calls.map((c) => c.tool)).toEqual([
      "web_navigate",
      "web_verifyTextVisible", // initial probe — thrown by the stub
      "web_click", // recovery branch reached
      "web_verifyTextVisible", // retry — thrown by the same stub
    ]);
    // The recovery branch's click ref is a load-bearing implementation detail —
    // asserting on it catches a regression where someone "cleans up" the ref into a
    // selector that doesn't exist in the sample-app fixture.
    expect(client.calls[2]?.args).toMatchObject({
      ref: "css=a[href='#form']",
    });
  });
});
