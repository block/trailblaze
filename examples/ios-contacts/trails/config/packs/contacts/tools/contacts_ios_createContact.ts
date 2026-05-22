import type { TrailblazeClient, TrailblazeContext } from "@trailblaze/scripting";
import {
  LABELS,
  ensureContactsRoot,
  nonEmptyString,
  requireSessionContext,
} from "./contacts_ios_shared";

const DEFAULT_FIRST_NAME = "Trailblaze";
const DEFAULT_LAST_NAME = "Demo";

export interface CreateContactArgs {
  /** First name to type. */
  firstName?: string;
  /** Last name to type. */
  lastName?: string;
  /**
   * Phone number to type. Formatting (spaces, dashes, parens) is preserved
   * verbatim — iOS does its own visual reformat after Save.
   */
  phoneNumber?: string;
}

/**
 * Multi-step form interaction: opens the new-contact draft from the list root,
 * fills first/last name + (optionally) phone number, and saves. Returns the
 * full display name the created contact will appear under in the list.
 *
 * Each field tap goes through `tapOnElementWithText` against the field's
 * placeholder label rather than a generic "first text field" descriptor — iOS
 * Contacts mounts the fields lazily as the user scrolls, and a positional
 * selector would otherwise tap the wrong row when the form is mid-scroll.
 *
 * Save asserts the contact's full name is visible on the next screen, which
 * confirms the iOS save round-trip completed before the tool returns.
 */
export async function contacts_ios_createContact(
  args: CreateContactArgs,
  ctx: TrailblazeContext | undefined,
  client: TrailblazeClient,
): Promise<string> {
  requireSessionContext(ctx);

  const firstName = nonEmptyString(args?.firstName, DEFAULT_FIRST_NAME);
  const lastName = nonEmptyString(args?.lastName, DEFAULT_LAST_NAME);
  const phoneNumber = typeof args?.phoneNumber === "string" ? args.phoneNumber : "";
  const fullName = `${firstName} ${lastName}`;

  await ensureContactsRoot(client);

  await client.tools.tapOnElementWithText({
    text: LABELS.addButton,
  });
  await client.tools.assertVisibleWithAccessibilityText({ accessibilityText: LABELS.firstNameField });

  await client.tools.tapOnElementWithText({ text: LABELS.firstNameField });
  await client.tools.inputText({ text: firstName });

  await client.tools.tapOnElementWithText({ text: LABELS.lastNameField });
  await client.tools.inputText({ text: lastName });

  if (phoneNumber.length > 0) {
    await client.tools.tapOnElementWithText({ text: LABELS.addPhoneField });
    await client.tools.inputText({ text: phoneNumber });
  }

  await client.tools.tapOnElementWithText({
    text: LABELS.doneButton,
  });
  await client.tools.assertVisibleWithAccessibilityText({ accessibilityText: fullName });

  return phoneNumber.length > 0
    ? `Created contact "${fullName}" with phone ${phoneNumber}.`
    : `Created contact "${fullName}".`;
}
