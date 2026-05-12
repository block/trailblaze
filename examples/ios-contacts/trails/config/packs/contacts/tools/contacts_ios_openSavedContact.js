import { DEFAULT_APP_ID, nonEmptyString, requireSessionContext } from "./contacts_shared.js";

const DEFAULT_CONTACT_NAME = "Trailblaze Scripted";

/**
 * @typedef {object} OpenSavedContactArgs
 * @property {string} [appId]         iOS bundle id of the Contacts app.
 * @property {string} [contactName]   Visible name of the saved contact to open.
 */

/**
 * Registered as `contacts_ios_openSavedContact` by `targets/ios-contacts.yaml`.
 *
 * @param {OpenSavedContactArgs} args
 * @param {import("@trailblaze/scripting").TrailblazeContext | undefined} ctx
 * @param {import("@trailblaze/scripting").TrailblazeClient} client
 */
export async function contacts_ios_openSavedContact(args, ctx, client) {
  requireSessionContext(ctx);

  const appId = nonEmptyString(args.appId, DEFAULT_APP_ID);
  const contactName = nonEmptyString(args.contactName, DEFAULT_CONTACT_NAME);

  await client.callTool("launchApp", {
    appId,
    launchMode: "FORCE_RESTART",
  });
  await client.callTool("assertVisibleWithText", {
    text: contactName,
  });
  await client.callTool("tapOnElementWithText", {
    text: contactName,
  });
  await client.callTool("assertVisibleWithText", {
    text: contactName,
  });

  return `Opened the saved contact ${contactName}.`;
}
