import { nonEmptyString, requireSessionContext } from "./playwrightSample_shared.js";

const DEFAULT_RELATIVE_PATH = "./examples/playwright-native/sample-app/duplicate-list.html";
const DEFAULT_ITEM_ID = "office-1";
const DEFAULT_EXPECTED_CATEGORY = "Office Supplies";

/**
 * @typedef {object} OpenDuplicateProductDetailArgs
 * @property {string} [relativePath]      Path to the duplicate-list fixture.
 * @property {string} [itemId]            data-id of the item whose View button to click.
 * @property {string} [expectedCategory]  Visible category text to assert in detail view.
 */

/**
 * Registered as `playwrightSample_web_openDuplicateProductDetail` by `targets/playwright-sample.yaml`.
 *
 * @param {OpenDuplicateProductDetailArgs} args
 * @param {import("@trailblaze/scripting").TrailblazeContext | undefined} ctx
 * @param {import("@trailblaze/scripting").TrailblazeClient} client
 */
export async function playwrightSample_web_openDuplicateProductDetail(args, ctx, client) {
  requireSessionContext(ctx);

  const relativePath = nonEmptyString(args.relativePath, DEFAULT_RELATIVE_PATH);
  const itemId = nonEmptyString(args.itemId, DEFAULT_ITEM_ID);
  const expectedCategory = nonEmptyString(args.expectedCategory, DEFAULT_EXPECTED_CATEGORY);

  await client.callTool("web_navigate", {
    action: "GOTO",
    url: relativePath,
  });
  await client.callTool("web_click", {
    ref: `css=[data-id='${itemId}'] button`,
    element: `View button for ${itemId}`,
  });
  await client.callTool("web_verify_text_visible", { text: expectedCategory });

  return `Opened duplicate-list item ${itemId} and verified category "${expectedCategory}".`;
}
