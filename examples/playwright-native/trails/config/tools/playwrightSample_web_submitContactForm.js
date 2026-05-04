import {
  nonEmptyString,
  PLAYWRIGHT_SAMPLE_HOME,
  requireSessionContext,
} from "./playwrightSample_shared.js";

/**
 * @typedef {object} SubmitContactFormArgs
 * @property {string} [relativePath]   Sample-app fixture path (relative to repo root).
 * @property {string} [name]           Name to type into the contact form.
 * @property {string} [email]          Email to type into the contact form.
 * @property {string} [category]       Category option value to select. Default: "support".
 * @property {string} [message]        Message body to type into the contact form.
 */

/**
 * Registered as `playwrightSample_web_submitContactForm` by `targets/playwright-sample.yaml`.
 *
 * @param {SubmitContactFormArgs} args
 * @param {import("@trailblaze/scripting").TrailblazeContext | undefined} ctx
 * @param {import("@trailblaze/scripting").TrailblazeClient} client
 */
export async function playwrightSample_web_submitContactForm(args, ctx, client) {
  requireSessionContext(ctx);

  const relativePath = nonEmptyString(args.relativePath, PLAYWRIGHT_SAMPLE_HOME);
  const name = nonEmptyString(args.name, "Trailblaze Example");
  const email = nonEmptyString(args.email, "trailblaze@example.com");
  const category = nonEmptyString(args.category, "support");
  const message = nonEmptyString(args.message, "Hello from an inline scripted tool.");

  await client.callTool("web_navigate", {
    action: "GOTO",
    url: relativePath,
  });
  await client.callTool("web_click", {
    ref: "css=a[href='#form']",
    element: "Form section link",
  });
  await client.callTool("web_type", {
    ref: "css=#name-input",
    element: "Name input",
    text: name,
  });
  await client.callTool("web_type", {
    ref: "css=#email-input",
    element: "Email input",
    text: email,
  });
  await client.callTool("web_select_option", {
    ref: "css=#category-select",
    element: "Category select",
    values: [category],
  });
  await client.callTool("web_type", {
    ref: "css=#message-input",
    element: "Message input",
    text: message,
  });
  await client.callTool("web_click", {
    ref: "css=button[type='submit']",
    element: "Submit button",
  });
  await client.callTool("web_verify_text_visible", { text: "Form submitted!" });

  return `Submitted the sample contact form for ${name} (${email}).`;
}
