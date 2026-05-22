import type { TrailblazeClient, TrailblazeContext } from "@trailblaze/scripting";

import { nonEmptyString, requireSessionContext } from "./playwrightSample_shared";

const DEFAULT_RELATIVE_PATH = "./examples/playwright-native/sample-app/search-duplicates.html";
const DEFAULT_QUERY = "wireless";
const DEFAULT_RESULT_ID = "1";
const DEFAULT_EXPECTED_DETAIL_TEXT = "Bluetooth, Black";

interface SearchProductsAndOpenResultArgs {
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
 * Registered as `playwrightSample_web_searchProductsAndOpenResult` by the
 * `playwrightsample` pack manifest.
 */
export async function playwrightSample_web_searchProductsAndOpenResult(
  args: SearchProductsAndOpenResultArgs,
  ctx: TrailblazeContext | undefined,
  client: TrailblazeClient,
): Promise<string> {
  requireSessionContext(ctx);

  const relativePath = nonEmptyString(args.relativePath, DEFAULT_RELATIVE_PATH);
  const query = nonEmptyString(args.query, DEFAULT_QUERY);
  const resultId = nonEmptyString(args.resultId, DEFAULT_RESULT_ID);
  const expectedDetailText = nonEmptyString(
    args.expectedDetailText,
    DEFAULT_EXPECTED_DETAIL_TEXT,
  );

  await client.tools.web_navigate({
    action: "GOTO",
    url: relativePath,
  });
  await client.tools.web_type({
    ref: "css=#search-input",
    text: query,
  });
  await client.tools.web_click({
    ref: "css=#search-btn",
  });
  await client.tools.web_click({
    ref: `css=[data-id='${resultId}']`,
  });
  await client.tools.web_verify_text_visible({ text: expectedDetailText });

  return `Searched for "${query}", opened result ${resultId}, and verified "${expectedDetailText}".`;
}
