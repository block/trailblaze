import { nonEmptyString, requireSessionContext } from "./playwrightSample_shared.js";

const DEFAULT_RELATIVE_PATH = "./examples/playwright-native/sample-app/search-duplicates.html";
const DEFAULT_QUERY = "wireless";
const DEFAULT_RESULT_ID = "1";
const DEFAULT_EXPECTED_DETAIL_TEXT = "Bluetooth, Black";

/**
 * @typedef {object} SearchProductsAndOpenResultArgs
 * @property {string} [relativePath]         Path to the search-duplicates fixture.
 * @property {string} [query]                Search query to type.
 * @property {string} [resultId]             data-id of the result to click after searching.
 * @property {string} [expectedDetailText]   Visible text to assert on the result's detail view.
 */

/**
 * Registered as `playwrightSample_web_searchProductsAndOpenResult` by `targets/playwright-sample.yaml`.
 *
 * @param {SearchProductsAndOpenResultArgs} args
 * @param {import("@trailblaze/scripting").TrailblazeContext | undefined} ctx
 * @param {import("@trailblaze/scripting").TrailblazeClient} client
 */
export async function playwrightSample_web_searchProductsAndOpenResult(args, ctx, client) {
  requireSessionContext(ctx);

  const relativePath = nonEmptyString(args.relativePath, DEFAULT_RELATIVE_PATH);
  const query = nonEmptyString(args.query, DEFAULT_QUERY);
  const resultId = nonEmptyString(args.resultId, DEFAULT_RESULT_ID);
  const expectedDetailText = nonEmptyString(
    args.expectedDetailText,
    DEFAULT_EXPECTED_DETAIL_TEXT,
  );

  await client.callTool("web_navigate", {
    action: "GOTO",
    url: relativePath,
  });
  await client.callTool("web_type", {
    ref: "css=#search-input",
    element: "Search products input",
    text: query,
  });
  await client.callTool("web_click", {
    ref: "css=#search-btn",
    element: "Search button",
  });
  await client.callTool("web_click", {
    ref: `css=[data-id='${resultId}']`,
    element: `Search result ${resultId}`,
  });
  await client.callTool("web_verify_text_visible", { text: expectedDetailText });

  return `Searched for "${query}", opened result ${resultId}, and verified "${expectedDetailText}".`;
}
