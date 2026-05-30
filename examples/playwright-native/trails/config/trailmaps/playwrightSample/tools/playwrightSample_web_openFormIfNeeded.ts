import { trailblaze } from "@trailblaze/scripting";

import {
  nonEmptyString,
  PLAYWRIGHT_SAMPLE_HOME,
} from "./playwrightSample_shared";

const DEFAULT_FORM_HEADING = "Contact Form";
const DEFAULT_FORM_LINK_REF = "css=a[href='#form']";

export interface OpenFormIfNeededArgs {
  /** Sample-app fixture path (relative to repo root). */
  relativePath?: string;
  /** Visible heading used to detect that the form is already open. */
  formHeading?: string;
}

/**
 * Open the sample form section only when it is not already visible.
 */
export const playwrightSample_web_openFormIfNeeded = trailblaze.tool<OpenFormIfNeededArgs>(
  { supportedPlatforms: ["web"], requiresContext: true },
  async (args, ctx) => {
    const relativePath = nonEmptyString(args.relativePath, PLAYWRIGHT_SAMPLE_HOME);
    const formHeading = nonEmptyString(args.formHeading, DEFAULT_FORM_HEADING);

    await ctx.tools.web_navigate({
      action: "GOTO",
      url: relativePath,
    });

    try {
      // The current callback contract throws when an inner tool fails, so probing state is
      // try/catch for now.
      await ctx.tools.web_verifyTextVisible({ text: formHeading });
      return `The form section was already visible at ${relativePath}.`;
    } catch {
      await ctx.tools.web_click({
        ref: DEFAULT_FORM_LINK_REF,
      });
      await ctx.tools.web_verifyTextVisible({ text: formHeading });
      return `Opened the form section from ${relativePath}.`;
    }
  },
);
