import type { TrailblazeClient, TrailblazeContext } from "@trailblaze/scripting";
import {
  LABELS,
  nonEmptyString,
  requireSessionContext,
  tryOrFalse,
} from "./contacts_ios_shared";

const DEFAULT_CONTACT_NAME = "Trailblaze Demo";

export interface DeleteContactArgs {
  /** Visible name of the contact to delete. */
  name?: string;
}

/**
 * Destructive flow: opens the named contact, enters edit mode, scrolls to the
 * destructive-action row, taps Delete Contact, and confirms in the action
 * sheet. Idempotent — if the contact doesn't exist, returns successfully
 * without doing anything destructive. The probe is cheap (one search + visible
 * check) so this can safely run as a teardown step at the top of any trail
 * that creates throwaway contacts.
 *
 * Two-step confirmation (Edit → Delete Contact row → "Delete Contact" sheet
 * button) is preserved verbatim — iOS surfaces the same string in both places,
 * but the second tap targets the action-sheet button specifically. Don't
 * shortcut to a swipe-to-delete: that affordance is locale-dependent and
 * doesn't work on the contact-detail screen, only on the list.
 */
export async function contacts_ios_deleteContact(
  args: DeleteContactArgs,
  ctx: TrailblazeContext | undefined,
  client: TrailblazeClient,
): Promise<string> {
  requireSessionContext(ctx);
  const name = nonEmptyString(args?.name, DEFAULT_CONTACT_NAME);

  const opened = await tryOrFalse(() =>
    client.tools.contacts_ios_openContact({ name, expectedHeading: name }),
  );
  if (!opened) {
    return `Contact "${name}" not found — nothing to delete.`;
  }

  await client.tools.tapOnElementWithText({
    text: LABELS.editButton,
  });

  // Delete Contact is at the bottom of the (long) edit form. `scrollUntilTextIsVisible`
  // is the safest way to surface it on devices with very small viewports.
  await client.tools.scrollUntilTextIsVisible({ text: LABELS.deleteContactRow });
  await client.tools.tapOnElementWithText({ text: LABELS.deleteContactRow });

  // The action sheet that confirms the deletion shows the same "Delete Contact"
  // string as the row that opened it — tap it again to commit. If any step in
  // the Edit → Delete → Confirm chain failed, an exception is already raised
  // above; we deliberately do NOT post-verify by probing for the deleted
  // name. After delete, iOS pops back to the search results screen with the
  // query still in the search field, so a visible-text probe would
  // false-positive on the residual search input. A negative-by-absence probe
  // would be similarly unreliable on long contact lists where the row may
  // simply be off-screen rather than absent.
  await client.tools.tapOnElementWithText({ text: LABELS.deleteContactRow });

  return `Deleted contact "${name}".`;
}
