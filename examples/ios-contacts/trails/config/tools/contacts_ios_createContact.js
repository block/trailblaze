import { DEFAULT_APP_ID, nonEmptyString, requireSessionContext } from "./contacts_shared.js";

const DEFAULT_FIRST_NAME = "Trailblaze";
const DEFAULT_LAST_NAME = "Scripted";
const DEFAULT_PHONE_NUMBER = "5551234567";
const DEFAULT_ADD_BUTTON_LABEL = "Add";
const DEFAULT_FIRST_NAME_LABEL = "First name";
const DEFAULT_LAST_NAME_LABEL = "Last name";
const DEFAULT_ADD_PHONE_LABEL = "add phone";
const DEFAULT_DONE_BUTTON_LABEL = "Done";

/**
 * @typedef {object} CreateContactArgs
 * @property {string} [appId]              iOS bundle id of the Contacts app.
 * @property {string} [firstName]          First name to type.
 * @property {string} [lastName]           Last name to type.
 * @property {string} [phoneNumber]        Phone number to type. Formatting preserved verbatim.
 * @property {string} [addButtonLabel]     Accessibility label of the "+" / Add button.
 * @property {string} [firstNameLabel]     Accessibility label of the First Name field.
 * @property {string} [lastNameLabel]      Accessibility label of the Last Name field.
 * @property {string} [addPhoneLabel]      Accessibility label of the Add Phone affordance.
 * @property {string} [doneButtonLabel]    Accessibility label of the Done / Save button.
 */

/**
 * Registered as `contacts_ios_createContact` by `targets/ios-contacts.yaml`.
 *
 * @param {CreateContactArgs} args
 * @param {import("@trailblaze/scripting").TrailblazeContext | undefined} ctx
 * @param {import("@trailblaze/scripting").TrailblazeClient} client
 */
export async function contacts_ios_createContact(args, ctx, client) {
  requireSessionContext(ctx);

  const appId = nonEmptyString(args.appId, DEFAULT_APP_ID);
  const firstName = nonEmptyString(args.firstName, DEFAULT_FIRST_NAME);
  const lastName = nonEmptyString(args.lastName, DEFAULT_LAST_NAME);
  const phoneNumber = nonEmptyString(args.phoneNumber, DEFAULT_PHONE_NUMBER);
  const addButtonLabel = nonEmptyString(
    args.addButtonLabel,
    DEFAULT_ADD_BUTTON_LABEL,
  );
  const firstNameLabel = nonEmptyString(
    args.firstNameLabel,
    DEFAULT_FIRST_NAME_LABEL,
  );
  const lastNameLabel = nonEmptyString(
    args.lastNameLabel,
    DEFAULT_LAST_NAME_LABEL,
  );
  const addPhoneLabel = nonEmptyString(
    args.addPhoneLabel,
    DEFAULT_ADD_PHONE_LABEL,
  );
  const doneButtonLabel = nonEmptyString(
    args.doneButtonLabel,
    DEFAULT_DONE_BUTTON_LABEL,
  );
  const fullName = [firstName, lastName].filter(Boolean).join(" ");

  await client.callTool("launchApp", {
    appId,
    launchMode: "FORCE_RESTART",
  });
  await client.callTool("tapOnElementWithAccessibilityText", {
    accessibilityText: addButtonLabel,
  });
  await client.callTool("tapOnElementWithText", {
    text: firstNameLabel,
  });
  await client.callTool("inputText", {
    text: firstName,
  });
  await client.callTool("tapOnElementWithText", {
    text: lastNameLabel,
  });
  await client.callTool("inputText", {
    text: lastName,
  });
  await client.callTool("tapOnElementWithText", {
    text: addPhoneLabel,
  });
  await client.callTool("inputText", {
    text: phoneNumber,
  });
  await client.callTool("tapOnElementWithAccessibilityText", {
    accessibilityText: doneButtonLabel,
  });
  await client.callTool("assertVisibleWithText", {
    text: fullName,
  });

  return `Created the contact ${fullName} with phone number ${phoneNumber}.`;
}
