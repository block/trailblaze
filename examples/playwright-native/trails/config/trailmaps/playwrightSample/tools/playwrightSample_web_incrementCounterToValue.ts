import { trailblaze } from "@trailblaze/scripting";

import {
  nonEmptyString,
  PLAYWRIGHT_SAMPLE_HOME,
  positiveInteger,
} from "./playwrightSample_shared";

const DEFAULT_INCREMENTS = 3;

export interface IncrementCounterToValueArgs {
  /** Sample-app fixture path (relative to repo root). */
  relativePath?: string;
  /** How many times to click the increment button. Positive integer. */
  increments?: number;
  /** Final value to assert. Defaults to String(increments). */
  expectedValue?: string;
}

/**
 * Open the counter demo, click increment repeatedly, and verify the final value.
 */
export const playwrightSample_web_incrementCounterToValue = trailblaze.tool<IncrementCounterToValueArgs>(
  { supportedPlatforms: ["web"], requiresContext: true },
  async (args, ctx) => {
    const relativePath = nonEmptyString(args.relativePath, PLAYWRIGHT_SAMPLE_HOME);
    const increments = positiveInteger(args.increments, DEFAULT_INCREMENTS);
    const expectedValue = nonEmptyString(args.expectedValue, String(increments));

    await ctx.tools.web_navigate({
      action: "GOTO",
      url: relativePath,
    });
    await ctx.tools.web_click({
      ref: "css=a[href='#counter']",
    });

    for (let index = 0; index < increments; index += 1) {
      await ctx.tools.web_click({
        ref: "css=#increment-btn",
      });
    }

    await ctx.tools.web_verifyValue({
      ref: "css=#counter-value",
      type: "TEXT",
      expected: expectedValue,
    });

    return `Incremented the counter ${increments} time(s) and verified value ${expectedValue}.`;
  },
);
