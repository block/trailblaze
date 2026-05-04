import {
  nonEmptyString,
  PLAYWRIGHT_SAMPLE_HOME,
  requireSessionContext,
} from "./playwrightSample_shared.js";

const DEFAULT_TEXT = "Trailblaze Test Fixture";

/**
 * @typedef {object} OpenFixtureAndVerifyTextArgs
 * @property {string} [relativePath]   Sample-app fixture path (relative to repo root).
 * @property {string} [text]           Visible text to assert on the loaded page.
 */

/**
 * Registered as `playwrightSample_web_openFixtureAndVerifyText` by `targets/playwright-sample.yaml`.
 *
 * @param {OpenFixtureAndVerifyTextArgs} args
 * @param {import("@trailblaze/scripting").TrailblazeContext | undefined} ctx
 * @param {import("@trailblaze/scripting").TrailblazeClient} client
 */
export async function playwrightSample_web_openFixtureAndVerifyText(args, ctx, client) {
  requireSessionContext(ctx);

  const relativePath = nonEmptyString(args.relativePath, PLAYWRIGHT_SAMPLE_HOME);
  const text = nonEmptyString(args.text, DEFAULT_TEXT);

  await client.callTool("web_navigate", {
    action: "GOTO",
    url: relativePath,
  });
  await client.callTool("web_verify_text_visible", { text });

  return `Opened ${relativePath} and verified "${text}" on ${ctx.device.platform}.`;
}
