import { trailblaze } from "@trailblaze/scripting";
import { LABELS, nonEmptyString } from "./contacts_ios_shared";

const DEFAULT_CONTACT_NAME = "John Appleseed";

export interface OpenContactArgs {
  /** Visible name of the contact to open (e.g. "Albert Einstein"). */
  name?: string;
  /**
   * Visible text to assert on the contact detail screen after opening. Defaults
   * to `name`, which works for the common case where the contact's display
   * name is what shows in the navbar.
   */
  expectedHeading?: string;
}

/**
 * Open a specific contact by name from the iOS Contacts list. Use this whenever
 * the task is to open a contact, view a contact, navigate to someone's
 * contact card, or look up a particular person — e.g. "open the John Appleseed
 * contact", "go to Albert Einstein's contact card". Verifies the contact's
 * detail screen rendered by asserting the contact name is visible in the
 * navbar. If the contact doesn't exist the underlying search throws — callers
 * who want a "create if missing" pattern should wrap this with a `tryOrFalse`.
 */
// Implementation note: trailhead tool that composes the search tool so the iOS
// Contacts pull-down-to-search interaction stays in one place — this tool just
// states the workflow ("get me to this contact") and delegates the how.
export const contacts_ios_openContact = trailblaze.tool<OpenContactArgs>(
  { supportedPlatforms: ["ios"], requiresContext: true },
  async (input, ctx) => {
    const name = nonEmptyString(input?.name, DEFAULT_CONTACT_NAME);
    const expectedHeading = nonEmptyString(input?.expectedHeading, name);

    // Search owns the search-and-tap dance; we own the post-open assertion. The
    // search tool deliberately doesn't assert on a destination heading so callers
    // can verify against whatever string they actually care about — `name` (the
    // tap target) and `expectedHeading` (the navbar text) are independently
    // configurable here for partial-query → full-name flows.
    await ctx.tools.contacts_ios_searchContacts({
      query: name,
      rowText: name,
      openFirstResult: true,
    });
    // Confirm we actually navigated to the contact DETAIL screen before trusting the
    // heading. A bare name assert is NOT sufficient: the contact name is also visible as
    // a search-result row and even as the typed query in the search field, so when the
    // contact doesn't exist the row tap lands on the search field (no navigation) and a
    // name-only check still passes — a false "opened". The detail screen's top-right
    // "Edit" button is the reliable detail-only anchor: the list/search screens surface
    // "Add"/"Search" there, never "Edit". Asserting it first means a missing contact
    // fails HERE, so callers wrapping this in `tryOrFalse` (e.g. the delete teardown)
    // correctly treat it as "not found" instead of proceeding against the wrong screen.
    await ctx.tools.assertVisibleWithAccessibilityText({
      accessibilityText: LABELS.editButton,
    });
    await ctx.tools.assertVisibleWithAccessibilityText({
      accessibilityText: expectedHeading,
    });

    return `Opened contact "${name}" and verified heading "${expectedHeading}".`;
  },
);
