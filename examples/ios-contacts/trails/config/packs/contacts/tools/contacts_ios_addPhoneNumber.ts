import type { TrailblazeClient, TrailblazeContext } from "@trailblaze/scripting";
import {
  LABELS,
  nonEmptyString,
  requireSessionContext,
} from "./contacts_ios_shared";

const DEFAULT_PHONE = "5551234567";

export interface AddPhoneNumberArgs {
  /** Contact whose detail screen we edit. */
  name?: string;
  /** Phone number to type. Formatting preserved verbatim. */
  phoneNumber?: string;
}

/**
 * Multi-step form interaction: opens the named contact, enters edit mode,
 * taps "add phone", types the number, saves. Demonstrates a longer multi-step
 * iOS flow that crosses three screens (list → detail → edit) and exercises
 * the standard `tapOnElementWithText` + `scrollUntilTextIsVisible` + `inputText`
 * chain — `scrollUntilTextIsVisible` surfaces the "add phone" row when it's
 * pushed off-screen by other fields, then a regex match against its label
 * targets the tap.
 *
 * The save round-trip is verified by re-asserting the contact's name on the
 * detail screen that iOS pops back to after Done.
 */
export async function contacts_ios_addPhoneNumber(
  args: AddPhoneNumberArgs,
  ctx: TrailblazeContext | undefined,
  client: TrailblazeClient,
): Promise<string> {
  requireSessionContext(ctx);

  const name = nonEmptyString(args?.name, "Trailblaze Demo");
  const phoneNumber = nonEmptyString(args?.phoneNumber, DEFAULT_PHONE);

  await client.tools.contacts_ios_openContact({
    name,
    expectedHeading: name,
  });

  await client.tools.tapOnElementWithText({
    text: LABELS.editButton,
  });

  // The "add phone" row sits below the existing phone rows (if any). Scroll
  // it into view so the next tap targets the right affordance.
  await client.tools.scrollUntilTextIsVisible({ text: LABELS.addPhoneField });
  await client.tools.tapOnElementWithText({ text: LABELS.addPhoneField });
  await client.tools.inputText({ text: phoneNumber });

  await client.tools.tapOnElementWithText({
    text: LABELS.doneButton,
  });
  await client.tools.assertVisibleWithAccessibilityText({ accessibilityText: name });

  return `Added phone ${phoneNumber} to contact "${name}".`;
}
