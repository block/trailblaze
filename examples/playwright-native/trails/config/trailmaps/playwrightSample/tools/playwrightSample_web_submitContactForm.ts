import { trailblaze } from "@trailblaze/scripting";

import {
  nonEmptyString,
  PLAYWRIGHT_SAMPLE_HOME,
} from "./playwrightSample_shared";

export interface SubmitContactFormArgs {
  /** Sample-app fixture path (relative to repo root). */
  relativePath?: string;
  /** Name to type into the contact form. */
  name?: string;
  /** Email to type into the contact form. */
  email?: string;
  /** Category option value to select. Default: "support". */
  category?: string;
  /** Message body to type into the contact form. */
  message?: string;
}

/**
 * Fill out the sample contact form, submit it, and verify the success banner.
 */
export const playwrightSample_web_submitContactForm = trailblaze.tool<SubmitContactFormArgs>(
  { supportedPlatforms: ["web"], requiresContext: true },
  async (args, ctx) => {
    const relativePath = nonEmptyString(args.relativePath, PLAYWRIGHT_SAMPLE_HOME);
    const name = nonEmptyString(args.name, "Trailblaze Example");
    const email = nonEmptyString(args.email, "trailblaze@example.com");
    const category = nonEmptyString(args.category, "support");
    const message = nonEmptyString(args.message, "Hello from an inline scripted tool.");

    await ctx.tools.web_navigate({
      action: "GOTO",
      url: relativePath,
    });
    await ctx.tools.web_click({
      ref: "css=a[href='#form']",
    });
    await ctx.tools.web_type({
      ref: "css=#name-input",
      text: name,
    });
    await ctx.tools.web_type({
      ref: "css=#email-input",
      text: email,
    });
    await ctx.tools.web_selectOption({
      ref: "css=#category-select",
      values: [category],
    });
    await ctx.tools.web_type({
      ref: "css=#message-input",
      text: message,
    });
    await ctx.tools.web_click({
      ref: "css=button[type='submit']",
    });
    await ctx.tools.web_verifyTextVisible({ text: "Form submitted!" });

    return `Submitted the sample contact form for ${name} (${email}).`;
  },
);
