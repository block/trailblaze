import { trailblaze } from "@trailblaze/scripting";
import { LABELS, ensureContactsRoot, nonEmptyString } from "./contacts_ios_shared";

const DEFAULT_FIRST_NAME = "Trailblaze";
const DEFAULT_LAST_NAME = "Demo";

export interface CreateContactArgs {
  /** First name to type. */
  firstName?: string;
  /** Last name to type. */
  lastName?: string;
  /** Phone number to type, verbatim. iOS reformats it for display after Save. */
  phoneNumber?: string;
}

/**
 * Create and save a new iOS contact, then verify its full name shows on the
 * detail screen.
 */
// Implementation note: each field tap goes through `tapOnElementWithText` against
// the field's placeholder label rather than a generic "first text field"
// descriptor — iOS Contacts mounts the fields lazily as the user scrolls, and a
// positional selector would otherwise tap the wrong row when the form is mid-scroll.
export const contacts_ios_createContact = trailblaze.tool<CreateContactArgs>(
  { supportedPlatforms: ["ios"], requiresContext: true },
  async (input, ctx) => {
    const firstName = nonEmptyString(input?.firstName, DEFAULT_FIRST_NAME);
    const lastName = nonEmptyString(input?.lastName, DEFAULT_LAST_NAME);
    const phoneNumber = typeof input?.phoneNumber === "string" ? input.phoneNumber : "";
    const fullName = `${firstName} ${lastName}`;

    await ensureContactsRoot(ctx);

    await ctx.tools.tapOnElementWithText({
      text: LABELS.addButton,
    });
    await ctx.tools.assertVisibleWithAccessibilityText({
      accessibilityText: LABELS.firstNameField,
    });

    await ctx.tools.tapOnElementWithText({ text: LABELS.firstNameField });
    await ctx.tools.inputText({ text: firstName });

    await ctx.tools.tapOnElementWithText({ text: LABELS.lastNameField });
    await ctx.tools.inputText({ text: lastName });

    if (phoneNumber.length > 0) {
      await ctx.tools.tapOnElementWithText({ text: LABELS.addPhoneField });
      await ctx.tools.inputText({ text: phoneNumber });
    }

    await ctx.tools.tapOnElementWithText({
      text: LABELS.doneButton,
    });
    await ctx.tools.assertVisibleWithAccessibilityText({ accessibilityText: fullName });

    return phoneNumber.length > 0
      ? `Created contact "${fullName}" with phone ${phoneNumber}.`
      : `Created contact "${fullName}".`;
  },
);
