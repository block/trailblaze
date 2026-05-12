import {
  nonEmptyString,
  PLAYWRIGHT_SAMPLE_HOME,
  positiveInteger,
  requireSessionContext,
} from "./playwrightSample_shared.js";

const DEFAULT_INCREMENTS = 3;

/**
 * @typedef {object} IncrementCounterToValueArgs
 * @property {string} [relativePath]    Sample-app fixture path (relative to repo root).
 * @property {number} [increments]      How many times to click the increment button. Positive integer.
 * @property {string} [expectedValue]   Final value to assert. Defaults to String(increments).
 */

/**
 * Registered as `playwrightSample_web_incrementCounterToValue` by `targets/playwright-sample.yaml`.
 *
 * @param {IncrementCounterToValueArgs} args
 * @param {import("@trailblaze/scripting").TrailblazeContext | undefined} ctx
 * @param {import("@trailblaze/scripting").TrailblazeClient} client
 */
export async function playwrightSample_web_incrementCounterToValue(args, ctx, client) {
  requireSessionContext(ctx);

  const relativePath = nonEmptyString(args.relativePath, PLAYWRIGHT_SAMPLE_HOME);
  const increments = positiveInteger(args.increments, DEFAULT_INCREMENTS);
  const expectedValue = nonEmptyString(args.expectedValue, String(increments));

  await client.callTool("web_navigate", {
    action: "GOTO",
    url: relativePath,
  });
  await client.callTool("web_click", {
    ref: "css=a[href='#counter']",
    element: "Counter section link",
  });

  for (let index = 0; index < increments; index += 1) {
    await client.callTool("web_click", {
      ref: "css=#increment-btn",
      element: "Increment counter button",
    });
  }

  await client.callTool("web_verify_value", {
    ref: "css=#counter-value",
    type: "TEXT",
    expected: expectedValue,
    element: "Counter value",
  });

  return `Incremented the counter ${increments} time(s) and verified value ${expectedValue}.`;
}
