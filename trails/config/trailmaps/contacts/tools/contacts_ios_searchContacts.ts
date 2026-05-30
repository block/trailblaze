import { trailblaze } from "@trailblaze/scripting";
import { ensureContactsRoot, nonEmptyString, textIsVisible } from "./contacts_ios_shared";

const DEFAULT_QUERY = "John";

export interface SearchContactsArgs {
  /** Query to type into the contacts list's pull-down search field. */
  query?: string;
  /**
   * Row text the tool taps after typing the query. Defaults to `query`. Pass
   * this explicitly when the query is a partial prefix of the row's visible
   * label (e.g. `query="alb"` + `rowText="Albert Einstein"`).
   */
  rowText?: string;
  /**
   * When true (default), taps the first visible matching row to open the
   * contact's detail screen. When false, leaves the search active so a caller
   * can verify the autocomplete-style suggestion list itself.
   */
  openFirstResult?: boolean;
}

/**
 * Search the iOS Contacts list for a name and (optionally) open the first
 * matching contact. Use this whenever the task is to search Contacts for a
 * person, look up a contact by name, find someone in Contacts, or jump to a
 * known contact's detail screen. Handles the iOS pull-down-to-reveal-search
 * gesture, types the query, and either taps the first match (default) or
 * leaves the search active so the caller can verify the suggestion list. When
 * `openFirstResult: true` and the query yields no matches, throws a
 * descriptive error so callers can distinguish "wrong query" from "wrong row
 * text".
 */
// Implementation notes:
// Two branches:
//   1. `openFirstResult` true (default) — types the query and taps the first
//      row matching `rowText`. Does NOT post-assert any specific heading; the
//      caller (e.g. `contacts_ios_openContact`) owns the destination check.
//   2. `openFirstResult` false — types the query but doesn't tap, so a caller
//      can subsequently verify the inline suggestion list contents.
//
// Force-restarts Contacts via `ensureContactsRoot` first, so the swipe-down
// gesture happens against a known list-root state rather than whatever
// sub-screen a previous step might have left visible. Pulling search into a
// separate "land on list, then search" primitive keeps the gesture sequence
// stable across composing flows.
export const contacts_ios_searchContacts = trailblaze.tool<SearchContactsArgs>(
  { supportedPlatforms: ["ios"], requiresContext: true },
  async (input, ctx) => {
    const query = nonEmptyString(input?.query, DEFAULT_QUERY);
    const rowText = nonEmptyString(input?.rowText, query);
    const openFirstResult = input?.openFirstResult !== false;

    await ensureContactsRoot(ctx);

    // iOS Contacts hides the search field above the list root. A swipe-down
    // gesture scrolls it into view; once visible, the input is tappable by its
    // "Search" accessibility label and accepts text.
    await ctx.tools.swipe({ direction: "DOWN" });
    await ctx.tools.tapOnElementWithText({ text: "Search" });
    await ctx.tools.inputText({ text: query });

    if (!openFirstResult) {
      return `Typed "${query}" into Contacts search and stopped (no result tapped).`;
    }

    // Pre-flight: if iOS rendered the "No Results" banner, the next tap will
    // fail with a generic "element not found". Surface the no-results state
    // directly so the error message reflects the real cause.
    if (await textIsVisible(ctx, "No Results")) {
      throw new Error(
        `contacts_ios_searchContacts: query "${query}" returned no results.`,
      );
    }

    await ctx.tools.tapOnElementWithText({ text: rowText });
    return `Searched for "${query}" and opened the row matching "${rowText}".`;
  },
);
