import { DEFAULT_APP_ID, nonEmptyString, requireSessionContext } from "./contacts_shared.js";

const DEFAULT_ADD_BUTTON_LABEL = "Add";
const DEFAULT_FIRST_NAME_LABEL = "First name";

/**
 * @typedef {object} OpenNewContactArgs
 * @property {string} [appId]              iOS bundle id of the Contacts app.
 * @property {string} [addButtonLabel]     Accessibility label of the "+" / Add button.
 * @property {string} [firstNameLabel]     Accessibility label of the First Name field
 *                                         used to confirm the new-contact draft is open.
 */

/**
 * Registered as `contacts_ios_openNewContact` by `targets/ios-contacts.yaml`.
 *
 * @param {OpenNewContactArgs} args
 * @param {import("@trailblaze/scripting").TrailblazeContext | undefined} ctx
 * @param {import("@trailblaze/scripting").TrailblazeClient} client
 */
export async function contacts_ios_openNewContact(args, ctx, client) {
  requireSessionContext(ctx);

  const appId = nonEmptyString(args.appId, DEFAULT_APP_ID);
  const addButtonLabel = nonEmptyString(
    args.addButtonLabel,
    DEFAULT_ADD_BUTTON_LABEL,
  );
  const firstNameLabel = nonEmptyString(
    args.firstNameLabel,
    DEFAULT_FIRST_NAME_LABEL,
  );

  await client.callTool("launchApp", {
    appId,
    launchMode: "FORCE_RESTART",
  });
  await client.callTool("tapOnElementWithAccessibilityText", {
    accessibilityText: addButtonLabel,
  });
  await client.callTool("assertVisibleWithText", {
    text: firstNameLabel,
  });

  return `Opened a new contact draft in ${appId}.`;
}
