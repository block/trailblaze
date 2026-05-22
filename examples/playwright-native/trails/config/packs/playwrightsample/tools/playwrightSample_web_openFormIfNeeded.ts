import type { TrailblazeClient, TrailblazeContext } from "@trailblaze/scripting";

import {
  nonEmptyString,
  PLAYWRIGHT_SAMPLE_HOME,
  requireSessionContext,
} from "./playwrightSample_shared";

const DEFAULT_FORM_HEADING = "Contact Form";
const DEFAULT_FORM_LINK_REF = "css=a[href='#form']";

interface OpenFormIfNeededArgs {
  /** Sample-app fixture path (relative to repo root). */
  relativePath?: string;
  /** Visible heading used to detect that the form is already open. */
  formHeading?: string;
}

/**
 * Registered as `playwrightSample_web_openFormIfNeeded` by the
 * `playwrightsample` pack manifest.
 */
export async function playwrightSample_web_openFormIfNeeded(
  args: OpenFormIfNeededArgs,
  ctx: TrailblazeContext | undefined,
  client: TrailblazeClient,
): Promise<string> {
  requireSessionContext(ctx);

  const relativePath = nonEmptyString(args.relativePath, PLAYWRIGHT_SAMPLE_HOME);
  const formHeading = nonEmptyString(args.formHeading, DEFAULT_FORM_HEADING);

  await client.tools.web_navigate({
    action: "GOTO",
    url: relativePath,
  });

  try {
    // The current callback contract throws when an inner tool fails, so probing state is
    // try/catch for now.
    await client.tools.web_verify_text_visible({ text: formHeading });
    return `The form section was already visible at ${relativePath}.`;
  } catch {
    await client.tools.web_click({
      ref: DEFAULT_FORM_LINK_REF,
    });
    await client.tools.web_verify_text_visible({ text: formHeading });
    return `Opened the form section from ${relativePath}.`;
  }
}
