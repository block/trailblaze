// Sample test exercising a longer scripted-tool sequence. Where the sibling
// `playwrightSample_web_openFixtureAndVerifyText` test covers two calls, this one drives
// a five-call workflow — navigate, type the query, click the search button, click the
// result row, then verify the detail text — so authors see how to assert on a non-trivial
// dispatch order and on per-step arg shapes.
//
// Run via:
//
//   ./trailblaze check playwrightSample

import { describe, expect, test } from "bun:test";
import { createMockClient, createMockContext } from "@trailblaze/scripting/testing";

import { playwrightSample_web_searchProductsAndOpenResult } from "./playwrightSample_web_searchProductsAndOpenResult";

describe("playwrightSample_web_searchProductsAndOpenResult", () => {
  test("dispatches navigate → type → click → click → verify with the args forwarded", async () => {
    const client = createMockClient();
    const ctx = createMockContext({ platform: "web" });

    const result = await playwrightSample_web_searchProductsAndOpenResult(
      {
        relativePath: "fixtures/search-duplicates.html",
        query: "wireless",
        resultId: "2",
        expectedDetailText: "Bluetooth, Black",
      },
      ctx,
      client,
    );

    expect(client.calls.map((c) => c.tool)).toEqual([
      "web_navigate",
      "web_type",
      "web_click",
      "web_click",
      "web_verifyTextVisible",
    ]);
    expect(client.calls[0]?.args).toMatchObject({
      action: "GOTO",
      url: "fixtures/search-duplicates.html",
    });
    expect(client.calls[1]?.args).toMatchObject({
      ref: "css=#search-input",
      text: "wireless",
    });
    expect(client.calls[2]?.args).toMatchObject({ ref: "css=#search-btn" });
    // The interpolated data-id is a contract between the tool and the sample-app's HTML;
    // a regression that drops the `resultId` arg into a different selector form is the
    // exact bug this assertion is here to catch.
    expect(client.calls[3]?.args).toMatchObject({ ref: "css=[data-id='2']" });
    expect(client.calls[4]?.args).toMatchObject({ text: "Bluetooth, Black" });
    expect(result).toContain("Bluetooth, Black");
  });

  test("falls back to the module-level defaults when args are omitted", async () => {
    const client = createMockClient();
    const ctx = createMockContext({ platform: "web" });

    await playwrightSample_web_searchProductsAndOpenResult({}, ctx, client);

    // Default values are an implementation detail of the tool, so assert on the
    // *shape* of what flowed through rather than equality with the literal strings.
    // A doc-only tweak to `DEFAULT_QUERY` shouldn't break this test.
    expect(client.calls).toHaveLength(5);
    expect(typeof client.calls[1]?.args.text).toBe("string");
    expect((client.calls[1]?.args.text as string).length).toBeGreaterThan(0);
    const resultRef = client.calls[3]?.args.ref as string | undefined;
    expect(resultRef).toMatch(/^css=\[data-id='\d+'\]$/);
  });
});
