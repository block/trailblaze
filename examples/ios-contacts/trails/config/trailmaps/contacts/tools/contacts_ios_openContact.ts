import { trailblaze } from "@trailblaze/scripting";
import { LABELS, nonEmptyString } from "./contacts_ios_shared";

const DEFAULT_CONTACT_NAME = "John Appleseed";

export interface OpenContactArgs {
  /** Visible name of the contact to open, e.g. "Albert Einstein". */
  name?: string;
  /** Text to assert on the detail screen after opening. Defaults to `name`. */
  expectedHeading?: string;
}

/**
 * Open a contact by name (restarting Contacts first) and verify its detail
 * screen shows the name. Fails if the contact doesn't exist.
 */
// Scripted callers wanting "create if missing" can wrap this in `tryOrFalse`.
//
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
    // Confirm we actually reached the contact DETAIL screen before trusting the
    // heading. A name-only check is NOT sufficient: the typed query keeps `name`
    // visible in the search field, so if navigation silently failed a heading
    // assert would still pass — a false "opened". (The search tool's row tap is
    // label-scoped so its node-selector path can no longer resolve the search
    // field; the framework's Maestro fallback lowers it to a legacy text match
    // that still could, which is why this anchor stays as the independent
    // destination check.) The detail
    // screen's top-right "Edit" button is the reliable detail-only anchor (the
    // list/search screens surface "Add"/"Cancel" there, never "Edit"). We resolve it
    // via `findSelectorMatches` against the iOS accessibility tree — `assertVisibleWith-
    // AccessibilityText` matches *visible* text only and can't see the accessibility-
    // labeled "Edit" control — and throw when it's absent, so callers wrapping this in
    // `tryOrFalse` (e.g. the delete teardown) correctly treat a missing contact as
    // "not found" instead of proceeding against the search screen.
    const [editAnchors] = await ctx.tools.findSelectorMatches({
      selectors: [{ iosMaestro: { accessibilityTextRegex: LABELS.editButton } }],
      timeoutMs: 5000,
    });
    if (editAnchors.length === 0) {
      throw new Error(
        `Did not reach the contact detail screen for "${name}" — the ` +
          `"${LABELS.editButton}" anchor never appeared (the contact likely ` +
          `does not exist).`,
      );
    }
    await ctx.tools.assertVisibleWithAccessibilityText({
      accessibilityText: expectedHeading,
    });

    return `Opened contact "${name}" and verified heading "${expectedHeading}".`;
  },
);
