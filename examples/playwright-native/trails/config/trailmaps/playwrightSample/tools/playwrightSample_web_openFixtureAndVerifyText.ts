import { trailblaze } from "@trailblaze/scripting";

import {
  nonEmptyString,
  PLAYWRIGHT_SAMPLE_HOME,
} from "./playwrightSample_shared";

const DEFAULT_HEADING = "Trailblaze Test Fixture";

export interface OpenFixtureAndVerifyTextArgs {
  /** Sample-app fixture path (relative to repo root). */
  relativePath?: string;
  /** Heading to assert on the loaded page. */
  heading?: string;
}

/**
 * Open the Playwright sample page and verify its heading.
 */
export const playwrightSample_web_openFixtureAndVerifyText = trailblaze.tool<OpenFixtureAndVerifyTextArgs>(
  { supportedPlatforms: ["web"] },
  async (args, ctx) => {
    const relativePath = nonEmptyString(args.relativePath, PLAYWRIGHT_SAMPLE_HOME);
    const heading = nonEmptyString(args.heading, DEFAULT_HEADING);

    await ctx.tools.web_navigate({
      action: "GOTO",
      url: relativePath,
    });
    await ctx.tools.web_verifyElementVisible({ ref: `heading "${heading}"` });

    return `Opened ${relativePath} and verified heading "${heading}".`;
  },
);
