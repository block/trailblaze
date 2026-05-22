// Canonical reference for unit-testing a `.ts` scripted tool.
//
// The test imports the tool function directly (no daemon, no device) and drives it with
// the mock client + mock context from `@trailblaze/scripting/testing`. Run via:
//
//   ./trailblaze test playwrightsample
//
// — which discovers `*.test.ts` files in this directory and shells out to `bun test`.
// `bun test` also works as a drop-in if invoked from this directory.

import { describe, expect, test } from "bun:test";
import { createMockClient, createMockContext } from "@trailblaze/scripting/testing";

import { playwrightSample_web_openFixtureAndVerifyText } from "./playwrightSample_web_openFixtureAndVerifyText";

describe("playwrightSample_web_openFixtureAndVerifyText", () => {
  test("dispatches web_navigate then web_verify_text_visible with the args verbatim", async () => {
    const client = createMockClient();
    const ctx = createMockContext({ platform: "web" });

    const result = await playwrightSample_web_openFixtureAndVerifyText(
      { relativePath: "fixtures/text-snippet.html", text: "Hello" },
      ctx,
      client,
    );

    // Order matters — the tool's contract is "navigate, then verify text on the loaded
    // page." A future refactor that reorders the calls (or drops one) breaks this test
    // even though tsc would still be green.
    expect(client.calls.map((c) => c.tool)).toEqual([
      "web_navigate",
      "web_verify_text_visible",
    ]);
    expect(client.calls[0]?.args).toMatchObject({
      action: "GOTO",
      url: "fixtures/text-snippet.html",
    });
    expect(client.calls[1]?.args).toMatchObject({ text: "Hello" });
    expect(result).toContain("Hello");
  });

  test("applies module defaults when args fields are omitted", async () => {
    const client = createMockClient();
    const ctx = createMockContext({ platform: "web" });

    const result = await playwrightSample_web_openFixtureAndVerifyText(
      {},
      ctx,
      client,
    );

    // The tool's `nonEmptyString` helper falls back to its module-level defaults when
    // args.relativePath / args.text are absent. The exact default values are a tool-side
    // implementation detail — assert via substring rather than equality so a doc-only
    // tweak to the default string doesn't break the test.
    expect(client.calls).toHaveLength(2);
    expect(typeof client.calls[0]?.args.url).toBe("string");
    expect((client.calls[0]?.args.url as string).length).toBeGreaterThan(0);
    expect(typeof result).toBe("string");
  });
});
