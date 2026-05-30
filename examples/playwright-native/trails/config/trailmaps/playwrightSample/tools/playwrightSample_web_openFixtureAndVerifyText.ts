import { trailblaze } from "@trailblaze/scripting";

import {
  nonEmptyString,
  PLAYWRIGHT_SAMPLE_HOME,
} from "./playwrightSample_shared";

const DEFAULT_TEXT = "Trailblaze Test Fixture";

export interface OpenFixtureAndVerifyTextArgs {
  /** Sample-app fixture path (relative to repo root). */
  relativePath?: string;
  /** Visible text to assert on the loaded page. */
  text?: string;
}

/**
 * Open the Playwright sample page and verify visible text.
 */
export const playwrightSample_web_openFixtureAndVerifyText = trailblaze.tool<OpenFixtureAndVerifyTextArgs>(
  { supportedPlatforms: ["web"], requiresContext: true },
  async (args, ctx) => {
    const relativePath = nonEmptyString(args.relativePath, PLAYWRIGHT_SAMPLE_HOME);
    const text = nonEmptyString(args.text, DEFAULT_TEXT);

    await ctx.tools.web_navigate({
      action: "GOTO",
      url: relativePath,
    });
    await ctx.tools.web_verifyTextVisible({ text });

    return `Opened ${relativePath} and verified "${text}".`;
  },
);
