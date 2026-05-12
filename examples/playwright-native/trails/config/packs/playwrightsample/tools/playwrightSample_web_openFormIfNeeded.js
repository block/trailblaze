import {
  nonEmptyString,
  PLAYWRIGHT_SAMPLE_HOME,
  requireSessionContext,
} from "./playwrightSample_shared.js";

const DEFAULT_FORM_HEADING = "Contact Form";
const DEFAULT_FORM_LINK_REF = "css=a[href='#form']";

/**
 * @typedef {object} OpenFormIfNeededArgs
 * @property {string} [relativePath]   Sample-app fixture path (relative to repo root).
 * @property {string} [formHeading]    Visible heading used to detect that the form is already open.
 */

/**
 * Registered as `playwrightSample_web_openFormIfNeeded` by `targets/playwright-sample.yaml`.
 *
 * @param {OpenFormIfNeededArgs} args
 * @param {import("@trailblaze/scripting").TrailblazeContext | undefined} ctx
 * @param {import("@trailblaze/scripting").TrailblazeClient} client
 */
export async function playwrightSample_web_openFormIfNeeded(args, ctx, client) {
  requireSessionContext(ctx);

  const relativePath = nonEmptyString(args.relativePath, PLAYWRIGHT_SAMPLE_HOME);
  const formHeading = nonEmptyString(args.formHeading, DEFAULT_FORM_HEADING);

  await client.callTool("web_navigate", {
    action: "GOTO",
    url: relativePath,
  });

  try {
    // The current callback contract throws when an inner tool fails, so probing state is
    // try/catch for now.
    await client.callTool("web_verify_text_visible", { text: formHeading });
    return `The form section was already visible at ${relativePath}.`;
  } catch {
    await client.callTool("web_click", {
      ref: DEFAULT_FORM_LINK_REF,
      element: "Form section link",
    });
    await client.callTool("web_verify_text_visible", { text: formHeading });
    return `Opened the form section from ${relativePath}.`;
  }
}
