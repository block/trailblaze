// Shared helpers for the iOS Contacts example trailmap's scripted tools.
//
// Every tool in this directory imports from here. Keeping defaults + small
// validators centralized means each tool file stays focused on the workflow
// it actually exercises.

import type { ToolContext } from "@trailblaze/scripting";

export const CONTACTS_APP_ID = "com.apple.MobileAddressBook";

// LABELS below are the en-US accessibility labels Apple ships on iOS 17+. The
// system Contacts app drives most affordances through accessibility text rather
// than through stable identifiers, so the LLM and our scripted tools rely on
// these strings to find the right element. If the OS locale changes or Apple
// renames a label in a future release, you only need to change it in one place.
//
// Frozen so a careless `LABELS.add = "+"` from a downstream caller doesn't
// silently mutate the shared map for every other tool in the trailmap.
export const LABELS = Object.freeze({
  // Top-right "+" button on the contacts list — opens the new-contact draft.
  addButton: "Add",
  // "First name" / "Last name" placeholder text + accessibility label in the
  // edit form. Tap-target for entering text into each field.
  firstNameField: "First name",
  lastNameField: "Last name",
  // The "add phone" affordance on a new/edit-contact form expands into a phone
  // field row. Apple lowercases this one — keep it verbatim.
  addPhoneField: "add phone",
  // "Done" finalizes a new/edit-contact form.
  doneButton: "Done",
  // "Cancel" discards a new/edit-contact form.
  cancelButton: "Cancel",
  // "Edit" enters edit mode on a contact detail screen.
  editButton: "Edit",
  // "Delete Contact" — the destructive-action row at the bottom of the edit
  // screen. Confirms via a separate "Delete Contact" button in the action sheet.
  deleteContactRow: "Delete Contact",
  // Cancel chip that dismisses the keyboard / search affordance. Apple uses
  // capitalized "Cancel" both for the navbar Cancel and the search Cancel.
  searchCancel: "Cancel",
  // The visible "Contacts" navbar title on the contacts list root. Verified
  // against iOS 26.4 sim on 2026-05-22 — earlier iOS versions also surface
  // "Contacts" here, so this label is stable across the runtimes the trails
  // target. (On a sub-list filtered to a group, the title becomes "All
  // Contacts" instead — but we land on the unfiltered root.)
  contactsListTitle: "Contacts",
} as const);

/** Returns `value` if it's a non-empty string; otherwise the `fallback`. */
export function nonEmptyString(value: unknown, fallback: string): string {
  return typeof value === "string" && value.length > 0 ? value : fallback;
}

/**
 * Filters an optional `unknown` input down to a flat `string[]` of non-empty entries,
 * returning `[]` for non-arrays or empty inputs.
 *
 * Guards against bad inputs at the composition boundary so a downstream typo (e.g. a
 * stray empty string from a YAML template, a `null` slipped in by a partial JSON, or a
 * mixed-type array from a JS-only caller) doesn't reach the inner assert as a no-op
 * dispatch. Shared by every tool in this trailmap that accepts a list of field labels.
 */
export function filterNonEmptyStrings(input: unknown): string[] {
  return Array.isArray(input)
    ? input.filter(
        (value): value is string => typeof value === "string" && value.length > 0,
      )
    : [];
}

/**
 * Runs `attempt` and returns `true` on success, `false` if it throws. Used to
 * probe for conditional UI (keyboards, modals, banners) without the
 * surrounding tool failing when the element isn't there.
 *
 * The `ctx.tools.X(...)` dispatch contract throws on inner-tool failure, so
 * this is the lowest-common-denominator way to express "if X is visible, do Y"
 * without each call site sprouting its own try/catch.
 */
export async function tryOrFalse<T>(attempt: () => Promise<T>): Promise<boolean> {
  try {
    await attempt();
    return true;
  } catch {
    return false;
  }
}

/**
 * Sub-second presence probe via `assertVisibleWithAccessibilityText`. Returns
 * true if a view whose accessibility text matches `text` is currently on
 * screen, false otherwise. Avoids the default Maestro action timeout the
 * explicit assert pays when the element isn't there.
 *
 * Use sparingly — every call costs one round-trip to the device. For
 * unconditional flows, just call the action tool directly and let it throw.
 */
export async function textIsVisible(ctx: ToolContext, text: string): Promise<boolean> {
  return tryOrFalse(() =>
    ctx.tools.assertVisibleWithAccessibilityText({ accessibilityText: text }),
  );
}

/**
 * Re-launches the Contacts app from scratch and asserts the list root is
 * visible. Idempotent — safe to call from any prior state, including from
 * inside another scripted tool that needs a deterministic starting screen.
 *
 * Centralizes the (launchApp → assertVisible) pair so individual tools don't
 * re-implement the same boilerplate. Force-restart specifically: lets a trail
 * recover from a stale draft / leftover modal from a prior run that the OS
 * would otherwise restore.
 */
export async function ensureContactsRoot(ctx: ToolContext): Promise<void> {
  await ctx.tools.launchApp({
    appId: CONTACTS_APP_ID,
    launchMode: "FORCE_RESTART",
  });
  // Apple's contacts list root shows the "Contacts" navbar title. That
  // string is reused on a couple of sub-screens, but together with the
  // FORCE_RESTART above it's a reliable list-root anchor.
  await ctx.tools.assertVisibleWithAccessibilityText({
    accessibilityText: LABELS.contactsListTitle,
  });
}
