import { trailblaze } from "@trailblaze/scripting";

import { nonEmptyString } from "./playwrightSample_shared";

const DEFAULT_RELATIVE_PATH = "./examples/playwright-native/sample-app/search-duplicates.html";
const DEFAULT_QUERY = "wireless";
const DEFAULT_RESULT_ID = "1";
const DEFAULT_EXPECTED_DETAIL_TEXT = "Bluetooth, Black";

export interface SearchProductsAndOpenResultArgs {
  /** Path to the search-duplicates fixture. */
  relativePath?: string;
  /** Search query to type. */
  query?: string;
  /** data-id of the result to click after searching. */
  resultId?: string;
  /** Visible text to assert on the result's detail view. */
  expectedDetailText?: string;
}

/**
 * Search the products demo, open one result, and verify its detail text.
 */
export const playwrightSample_web_searchProductsAndOpenResult = trailblaze.tool<SearchProductsAndOpenResultArgs>(
  { supportedPlatforms: ["web"], requiresContext: true },
  async (args, ctx) => {
    const relativePath = nonEmptyString(args.relativePath, DEFAULT_RELATIVE_PATH);
    const query = nonEmptyString(args.query, DEFAULT_QUERY);
    const resultId = nonEmptyString(args.resultId, DEFAULT_RESULT_ID);
    const expectedDetailText = nonEmptyString(
      args.expectedDetailText,
      DEFAULT_EXPECTED_DETAIL_TEXT,
    );

    await ctx.tools.web_navigate({
      action: "GOTO",
      url: relativePath,
    });
    await ctx.tools.web_type({
      ref: "css=#search-input",
      text: query,
    });
    await ctx.tools.web_click({
      ref: "css=#search-btn",
    });
    await ctx.tools.web_click({
      ref: `css=[data-id='${resultId}']`,
    });
    await ctx.tools.web_verifyTextVisible({ text: expectedDetailText });

    return `Searched for "${query}", opened result ${resultId}, and verified "${expectedDetailText}".`;
  },
);
