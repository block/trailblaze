import type { TrailblazeClient, TrailblazeContext } from "@trailblaze/scripting";

import { nonEmptyString, requireSessionContext } from "./playwrightSample_shared";

const DEFAULT_RELATIVE_PATH = "./examples/playwright-native/sample-app/duplicate-list.html";
const DEFAULT_ITEM_ID = "office-1";
const DEFAULT_EXPECTED_CATEGORY = "Office Supplies";

interface OpenDuplicateProductDetailArgs {
  /** Path to the duplicate-list fixture. */
  relativePath?: string;
  /** data-id of the item whose View button to click. */
  itemId?: string;
  /** Visible category text to assert in detail view. */
  expectedCategory?: string;
}

/**
 * Registered as `playwrightSample_web_openDuplicateProductDetail` by the
 * `playwrightsample` pack manifest.
 */
export async function playwrightSample_web_openDuplicateProductDetail(
  args: OpenDuplicateProductDetailArgs,
  ctx: TrailblazeContext | undefined,
  client: TrailblazeClient,
): Promise<string> {
  requireSessionContext(ctx);

  const relativePath = nonEmptyString(args.relativePath, DEFAULT_RELATIVE_PATH);
  const itemId = nonEmptyString(args.itemId, DEFAULT_ITEM_ID);
  const expectedCategory = nonEmptyString(args.expectedCategory, DEFAULT_EXPECTED_CATEGORY);

  await client.tools.web_navigate({
    action: "GOTO",
    url: relativePath,
  });
  await client.tools.web_click({
    ref: `css=[data-id='${itemId}'] button`,
  });
  await client.tools.web_verify_text_visible({ text: expectedCategory });

  return `Opened duplicate-list item ${itemId} and verified category "${expectedCategory}".`;
}
