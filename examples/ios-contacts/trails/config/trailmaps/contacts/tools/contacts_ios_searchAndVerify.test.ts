// Sample test for the canonical composition pattern. `contacts_ios_searchAndVerify`
// dispatches no iOS primitives directly — it delegates to two sibling scripted tools in
// the same trailmap via `client.tools.<toolName>(args)`. The test asserts that delegation
// happens with the correct args, *without* unrolling the sub-tools themselves (those have
// their own dedicated test files).
//
// This is the agent's specifically-called-out composition demo — every scripted tool
// registered on the trailmap is reachable through `client.tools.<toolName>(args)` from any
// other scripted tool in the same trailmap, so authors build small focused primitives once
// and assemble higher-level workflows by name. The mock client records the cross-tool
// dispatch the same way it records primitive dispatches, which is what makes this a
// single-unit assertion rather than an integration test.
//
// Run via:
//
//   ./trailblaze check contacts

import { describe, expect, test } from "bun:test";
import { createMockClient, createMockContext } from "@trailblaze/scripting/testing";

import { contacts_ios_searchAndVerify } from "./contacts_ios_searchAndVerify";

describe("contacts_ios_searchAndVerify", () => {
  test("dispatches searchContacts then verifyContactStructure with the forwarded args", async () => {
    const client = createMockClient();
    const ctx = createMockContext({ platform: "ios" });

    const result = await contacts_ios_searchAndVerify(
      {
        query: "Apple Inc.",
        expectedName: "Apple Inc.",
        requireFields: ["phone", "email"],
      },
      ctx,
      client,
    );

    // Two cross-tool dispatches, in order. Neither sub-tool is unrolled here — that's
    // the point of the composition pattern, and asserting on the sub-tools' inner calls
    // would couple this test to the sub-tools' implementations rather than to the
    // composition contract itself.
    expect(client.calls.map((c) => c.tool)).toEqual([
      "contacts_ios_searchContacts",
      "contacts_ios_verifyContactStructure",
    ]);

    // The composition's contract is "forward `query` + `expectedName` into the search
    // step, `expectedName` + `requireFields` into the verify step." Assert on each
    // forwarded arg explicitly — a refactor that swaps the args silently still type-
    // checks, so this is the test that catches it.
    expect(client.calls[0]?.args).toMatchObject({
      query: "Apple Inc.",
      rowText: "Apple Inc.",
      openFirstResult: true,
    });
    expect(client.calls[1]?.args).toMatchObject({
      name: "Apple Inc.",
      requireFields: ["phone", "email"],
    });

    expect(result).toContain("Searched");
    expect(result).toContain("verified fields");
  });

  test("defaults expectedName to query and skips requireFields when omitted", async () => {
    const client = createMockClient();
    const ctx = createMockContext({ platform: "ios" });

    const result = await contacts_ios_searchAndVerify(
      { query: "Albert Einstein" },
      ctx,
      client,
    );

    expect(client.calls.map((c) => c.tool)).toEqual([
      "contacts_ios_searchContacts",
      "contacts_ios_verifyContactStructure",
    ]);
    // expectedName falls back to query — same value flows into both sub-tools.
    expect(client.calls[0]?.args).toMatchObject({
      query: "Albert Einstein",
      rowText: "Albert Einstein",
    });
    expect(client.calls[1]?.args).toMatchObject({
      name: "Albert Einstein",
      requireFields: [],
    });
    // The "no fields required" return string is distinguishable from the requireFields
    // variant — asserting on the substring keeps both branches honest.
    expect(result).toContain("verified detail screen");
  });

  test("filters out non-string and empty entries from requireFields before forwarding", async () => {
    // The tool guards against bad inputs at the composition boundary so a downstream
    // typo (e.g. a stray empty string from a YAML template) doesn't reach
    // verifyContactStructure as a no-op assertion. Assert on the cleaned-up shape.
    const client = createMockClient();
    const ctx = createMockContext({ platform: "ios" });

    await contacts_ios_searchAndVerify(
      {
        query: "Apple Inc.",
        // @ts-expect-error — intentionally exercise the runtime filter with mixed types.
        requireFields: ["phone", "", null, undefined, 42, "email"],
      },
      ctx,
      client,
    );

    expect(client.calls[1]?.args).toMatchObject({
      requireFields: ["phone", "email"],
    });
  });
});
