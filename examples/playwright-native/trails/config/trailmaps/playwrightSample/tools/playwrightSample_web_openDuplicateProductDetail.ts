import { trailblaze } from "@trailblaze/scripting";

import { nonEmptyString } from "./playwrightSample_shared";

const DEFAULT_RELATIVE_PATH = "./examples/playwright-native/sample-app/duplicate-list.html";
const DEFAULT_ITEM_ID = "office-1";
const DEFAULT_EXPECTED_CATEGORY = "Office Supplies";

export interface OpenDuplicateProductDetailArgs {
  /** Path to the duplicate-list fixture. */
  relativePath?: string;
  /** data-id of the item whose View button to click. */
  itemId?: string;
  /** Category the detail panel must show. */
  expectedCategory?: string;
}

/**
 * Open the duplicate-list demo, click a specific product row, and verify its detail panel.
 */
export const playwrightSample_web_openDuplicateProductDetail = trailblaze.tool<OpenDuplicateProductDetailArgs>(
  { supportedPlatforms: ["web"], requiresContext: true },
  async (args, ctx) => {
    const relativePath = nonEmptyString(args.relativePath, DEFAULT_RELATIVE_PATH);
    const itemId = nonEmptyString(args.itemId, DEFAULT_ITEM_ID);
    const expectedCategory = nonEmptyString(args.expectedCategory, DEFAULT_EXPECTED_CATEGORY);

    await ctx.tools.web_navigate({
      action: "GOTO",
      url: relativePath,
    });
    await ctx.tools.web_click({
      ref: `css=[data-id='${itemId}'] button`,
    });
    await ctx.tools.web_verifyElementVisible({
      ref: `css=#detail-category:text-is(${JSON.stringify(expectedCategory)})`,
    });

    return `Opened duplicate-list item ${itemId} and verified category "${expectedCategory}".`;
  },
);
