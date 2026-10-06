import { trailblaze } from "@trailblaze/scripting";
import { LABELS, nonEmptyString } from "./contacts_ios_shared";

const DEFAULT_PHONE = "5551234567";

export interface AddPhoneNumberArgs {
  /** Name of the contact to edit. */
  name?: string;
  /** Phone number to type, verbatim. */
  phoneNumber?: string;
}

/**
 * Add a phone number to an existing iOS contact and save it. Fails if the
 * contact doesn't exist.
 */
// Implementation notes (NOT in the TSDoc above — the LLM doesn't need them).
// Multi-step form interaction that crosses three screens (list → detail → edit)
// and exercises the standard `tapOnElementWithText` + `scrollUntilTextIsVisible`
// + `inputText` chain — `scrollUntilTextIsVisible` surfaces the "add phone" row
// when it's pushed off-screen by other fields, then a regex match against its
// label targets the tap.
//
// The save round-trip is verified by re-asserting the contact's name on the
// detail screen that iOS pops back to after Done.
export const contacts_ios_addPhoneNumber = trailblaze.tool<AddPhoneNumberArgs>(
  { supportedPlatforms: ["ios"], requiresContext: true },
  async (input, ctx) => {
    const name = nonEmptyString(input?.name, "Trailblaze Demo");
    const phoneNumber = nonEmptyString(input?.phoneNumber, DEFAULT_PHONE);

    await ctx.tools.contacts_ios_openContact({
      name,
      expectedHeading: name,
    });

    await ctx.tools.tapOnElementWithText({
      text: LABELS.editButton,
    });

    // The "add phone" row sits below the existing phone rows (if any). Scroll
    // it into view so the next tap targets the right affordance.
    await ctx.tools.scrollUntilTextIsVisible({ text: LABELS.addPhoneField });
    await ctx.tools.tapOnElementWithText({ text: LABELS.addPhoneField });
    await ctx.tools.inputText({ text: phoneNumber });

    await ctx.tools.tapOnElementWithText({
      text: LABELS.doneButton,
    });
    await ctx.tools.assertVisibleWithAccessibilityText({ accessibilityText: name });

    return `Added phone ${phoneNumber} to contact "${name}".`;
  },
);
