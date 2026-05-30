import { trailblaze } from "@trailblaze/scripting";
import { nonEmptyString } from "./contacts_ios_shared";

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
    await ctx.tools.assertVisibleWithAccessibilityText({
      accessibilityText: expectedHeading,
    });

    return `Opened contact "${name}" and verified heading "${expectedHeading}".`;
  },
);
