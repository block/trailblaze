import type { TrailblazeClient, TrailblazeContext } from "@trailblaze/scripting";

import {
  nonEmptyString,
  PLAYWRIGHT_SAMPLE_HOME,
  requireSessionContext,
} from "./playwrightSample_shared";

interface SubmitContactFormArgs {
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
 * Registered as `playwrightSample_web_submitContactForm` by the
 * `playwrightsample` pack manifest.
 */
export async function playwrightSample_web_submitContactForm(
  args: SubmitContactFormArgs,
  ctx: TrailblazeContext | undefined,
  client: TrailblazeClient,
): Promise<string> {
  requireSessionContext(ctx);

  const relativePath = nonEmptyString(args.relativePath, PLAYWRIGHT_SAMPLE_HOME);
  const name = nonEmptyString(args.name, "Trailblaze Example");
  const email = nonEmptyString(args.email, "trailblaze@example.com");
  const category = nonEmptyString(args.category, "support");
  const message = nonEmptyString(args.message, "Hello from an inline scripted tool.");

  await client.tools.web_navigate({
    action: "GOTO",
    url: relativePath,
  });
  await client.tools.web_click({
    ref: "css=a[href='#form']",
  });
  await client.tools.web_type({
    ref: "css=#name-input",
    text: name,
  });
  await client.tools.web_type({
    ref: "css=#email-input",
    text: email,
  });
  await client.tools.web_select_option({
    ref: "css=#category-select",
    values: [category],
  });
  await client.tools.web_type({
    ref: "css=#message-input",
    text: message,
  });
  await client.tools.web_click({
    ref: "css=button[type='submit']",
  });
  await client.tools.web_verify_text_visible({ text: "Form submitted!" });

  return `Submitted the sample contact form for ${name} (${email}).`;
}
