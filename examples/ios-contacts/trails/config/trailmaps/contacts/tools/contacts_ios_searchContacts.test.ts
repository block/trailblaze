// Canonical reference for unit-testing an iOS-trailmap scripted tool. Imports the tool
// function directly (no daemon, no simulator) and drives it with the mock client + mock
// context from `@trailblaze/scripting/testing`. Run via:
//
//   ./trailblaze check contacts
//
// — its third phase discovers `*.test.ts` files in this directory and shells out to
// `bun test`. `bun test` also works as a drop-in if invoked from this directory.

import { describe, expect, test } from "bun:test";
import { createMockClient, createMockContext } from "@trailblaze/scripting/testing";

import { contacts_ios_searchContacts } from "./contacts_ios_searchContacts";

describe("contacts_ios_searchContacts", () => {
  test("dispatches launchApp → assertVisible → swipe → tap Search → input → fires the No Results pre-flight branch", async () => {
    // The default mock client returns success for every dispatch — including the
    // `assertVisibleWithAccessibilityText({ accessibilityText: "No Results" })` probe the
    // tool runs after typing the query. That probe-success is the exact condition the
    // tool surfaces as a descriptive error instead of letting the subsequent row-tap fail
    // with a generic "element not found". This is the agent's recommended ideal demo —
    // exercises the real conditional logic without booting a simulator.
    const client = createMockClient();
    const ctx = createMockContext({ platform: "ios" });

    await expect(
      contacts_ios_searchContacts({ query: "Nobody" }, ctx, client),
    ).rejects.toThrow(/contacts_ios_searchContacts: query "Nobody" returned no results/);

    // Order matters — a regression that reorders the gesture sequence (or drops the
    // ensureContactsRoot prelude) breaks this test even though tsc would still be green.
    expect(client.calls.map((c) => c.tool)).toEqual([
      "launchApp", // ensureContactsRoot — force-restart Contacts
      "assertVisibleWithAccessibilityText", // ensureContactsRoot — "Contacts" list-root anchor
      "swipe", // pull the search field into view
      "tapOnElementWithText", // focus the "Search" input
      "inputText", // type the query
      "assertVisibleWithAccessibilityText", // "No Results" pre-flight probe — succeeds (mock default), fires the branch
    ]);
    // The launchApp force-restart is a load-bearing implementation detail — the tool's
    // contract is "swipe-down from a known list-root state", not "swipe-down from
    // wherever the device happens to be". Assert the launchMode so a refactor that
    // downgrades the restart to a plain launch fails here.
    expect(client.calls[0]?.args).toMatchObject({
      appId: "com.apple.MobileAddressBook",
      launchMode: "FORCE_RESTART",
    });
    expect(client.calls[1]?.args).toMatchObject({ accessibilityText: "Contacts" });
    expect(client.calls[2]?.args).toMatchObject({ direction: "DOWN" });
    expect(client.calls[3]?.args).toMatchObject({ text: "Search" });
    expect(client.calls[4]?.args).toMatchObject({ text: "Nobody" });
    // The pre-flight probe ran with the right needle — confirms we reached the
    // conditional rather than failing somewhere upstream.
    expect(client.calls[5]?.args).toMatchObject({ accessibilityText: "No Results" });
    // The row-tap was NOT called — the pre-flight branch short-circuited before it.
    expect(client.calls).toHaveLength(6);
  });

  test("returns early without probing No Results when openFirstResult is false", async () => {
    // `openFirstResult: false` is the "type the query and stop" branch — used by callers
    // that want to verify the inline autocomplete suggestions instead of opening a row.
    // The tool returns after the inputText call without ever probing for the No Results
    // banner or tapping a row.
    const client = createMockClient();
    const ctx = createMockContext({ platform: "ios" });

    const result = await contacts_ios_searchContacts(
      { query: "alb", openFirstResult: false },
      ctx,
      client,
    );

    expect(client.calls.map((c) => c.tool)).toEqual([
      "launchApp",
      "assertVisibleWithAccessibilityText",
      "swipe",
      "tapOnElementWithText",
      "inputText",
    ]);
    expect(result).toContain("stopped (no result tapped)");
  });

  test("applies module defaults when args fields are omitted", async () => {
    const client = createMockClient();
    const ctx = createMockContext({ platform: "ios" });

    // No `query` → tool falls back to its `DEFAULT_QUERY` module constant. The exact
    // default string is a tool-side implementation detail — assert via shape (non-empty
    // string forwarded to inputText) rather than equality so a doc-only tweak to the
    // default doesn't break the test.
    await expect(
      contacts_ios_searchContacts({}, ctx, client),
    ).rejects.toThrow(/returned no results/);

    const inputCall = client.calls.find((c) => c.tool === "inputText");
    expect(inputCall).toBeDefined();
    expect(typeof inputCall!.args.text).toBe("string");
    expect((inputCall!.args.text as string).length).toBeGreaterThan(0);
  });

  test("propagates a stubbed inputText failure with the production error wrapping", async () => {
    // Demonstrates `client.stub(name, response)` from `@trailblaze/scripting/testing` —
    // when `errorMessage` is non-empty, every call to the named tool throws with the same
    // wording the real daemon emits (see `unwrapCallbackResponse` in
    // `sdks/typescript/src/client.ts`). Stubs persist for every call to the named tool
    // until `client.reset()`.
    const client = createMockClient();
    client.stub("inputText", {
      textContent: "",
      errorMessage: "field not found",
    });
    const ctx = createMockContext({ platform: "ios" });

    // Match the production wrapper format (`tool failed: <errorMessage>`) so a future
    // tweak to the wrapping string in `client.ts` fails this assertion explicitly
    // instead of silently passing on the substring.
    await expect(
      contacts_ios_searchContacts({}, ctx, client),
    ).rejects.toThrow(/tool failed: field not found/);

    // The pre-input gesture chain still ran — the stub fires only at the inputText
    // dispatch. Useful for "regression: did the tool short-circuit before reaching the
    // search field?" assertions.
    expect(client.calls.map((c) => c.tool)).toEqual([
      "launchApp",
      "assertVisibleWithAccessibilityText",
      "swipe",
      "tapOnElementWithText",
      "inputText", // thrown by the stub
    ]);
  });
});
