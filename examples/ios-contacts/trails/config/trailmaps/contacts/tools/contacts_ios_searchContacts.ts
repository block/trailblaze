import { trailblaze, type TrailblazeNodeSelector } from "@trailblaze/scripting";
import { ensureContactsRoot, nonEmptyString, tryOrFalse } from "./contacts_ios_shared";

const DEFAULT_QUERY = "John";

/** Wait budget for the matching result row to render after the query is typed. */
const ROW_WAIT_MS = 5000;

/**
 * Accessibility label of the container iOS Contacts wraps the search-result rows in.
 * Used as the row selector's `childOf` scope so a substring `rowText` can only ever
 * resolve to a result row, never to the search field or its toolbar chrome.
 *
 * Deliberately a bare literal, not a contains pattern: node selectors full-match, and
 * this string is exact. Provenance is the captured Contacts hierarchy in
 * `trails/config/trailmaps/contacts/waypoints/ios/contacts_ios_search_results_with_clear.example.json`
 * — exactly one node carries this label, the result-row cell and its inner StaticText are
 * both inside its subtree, and the search field (with its magnifying-glass and "Clear text"
 * children) is a sibling outside it. Six waypoints in that directory already match the
 * same string as an exact literal.
 */
const RESULTS_LIST_LABEL = "Search results";

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

    // Pre-flight: surface the no-results state before the row tap below, so a
    // query that matches nothing fails with "wrong query" instead of falling
    // through to the row wait and reporting "wrong rowText". (Historically this
    // was also the only guard against the not-found tap resolving the query text
    // still showing in the *search field* — the label-scoped, results-list-scoped
    // row selector below now rules that out, but the distinct error stays
    // valuable.)
    //
    // iOS renders the banner as `No Results for "<query>"`, so we must match it
    // as a substring/regex. `assertVisibleWithAccessibilityText` is exact-match
    // only (an exact "No Results" needle never matches the real banner), so probe
    // with `assertNotVisibleWithText`, whose `text` is treated as a regex: it
    // throws when an element matching "No Results" IS present, which `tryOrFalse`
    // reports as `hasResults === false`.
    const hasResults = await tryOrFalse(() =>
      ctx.tools.assertNotVisibleWithText({ text: "No Results" }),
    );
    if (!hasResults) {
      throw new Error(
        `contacts_ios_searchContacts: query "${query}" returned no results.`,
      );
    }

    // Tap the result ROW via its accessibility label, scoped to the results list — never via
    // a bare text match.
    //
    // 1. LABEL, not text. A bare `tapOnElementWithText(rowText)` matches any node whose
    //    text / hintText / accessibilityText contains `rowText` — and on the host
    //    (Maestro/XCUITest) tree a text field's `text` attribute is its typed VALUE, so when
    //    `query == rowText` (e.g. `contacts_ios_openContact` passing the same full name to
    //    both) the tap resolved the search field's own typed text instead of the result row,
    //    no navigation happened, and the caller's detail-screen anchor never appeared.
    //    `accessibilityTextRegex` matches the AX *label* on both iOS drivers (host:
    //    `accessibilityText` = AXLabel; AXe: the iosMaestro→AXe bridge maps it to `.label`),
    //    and a search field's label is its placeholder ("Search") — never the typed value.
    //    The captured hierarchy cited on `RESULTS_LIST_LABEL` above shows this directly: the
    //    search field is one node carrying `accessibilityText: "Search"`, `hintText: "Search"`,
    //    and `text: "Kate"` (the query that had been typed when the capture was taken).
    //
    // 2. CONTAINS, not prefix. Node selectors full-match their regex, so a bare `rowText`
    //    would only match labels that equal (or, with a trailing `.*`, start with) it —
    //    breaking substring queries like a last name (`rowText: "Appleseed"` must still match
    //    the "John Appleseed" row) and labels that append detail text after the name. The
    //    surrounding `.*` restores the old `tapOnElementWithText` CONTAINS contract.
    //
    // 3. SCOPED to the results list. Contains-matching on the label is necessary but not
    //    sufficient: the search field's own label ("Search") contains plenty of substrings a
    //    caller may legitimately pass as `rowText` — `rowText: "ear"` opening a "Teddy Bear"
    //    row also matches "Search", and so do "Search results" and the "Clear text" button.
    //    Since `findMatches` and the tap share this selector with `index: 0`, the topmost of
    //    those could be the search field: the wait would succeed, the tap would only focus the
    //    field, and the tool would report success without ever opening the row. `childOf`
    //    fixes that structurally instead of narrowing the text match: on the real Contacts
    //    hierarchy every result row is a descendant of the "Search results" container, while
    //    the search field and all its chrome (the magnifying-glass image, "Clear text",
    //    "close") live under a sibling "Toolbar" branch — so scoping the search to that
    //    container's descendants removes the whole search-field/chrome family from the
    //    candidate set. `childOf` also excludes the anchor itself, so the full-screen
    //    "Search results" container can't be picked either.
    //
    //    Both iOS drivers evaluate this scope, and both via the same underlying AX attribute:
    //    on the host tree `accessibilityText` is the XCUIElement label; on an AXe tree the
    //    iosMaestro→AXe bridge routes `accessibilityTextRegex` to `.label` (AXLabel). Fields
    //    that only exist on one side were rejected for exactly this reason — `classNameRegex`
    //    is unusable here because the host iOS tree reports no `class` attribute for this app
    //    at all, so a class constraint would match nothing on the host driver.
    //
    // With the scope in place, `index: 0` is only disambiguating rows: it pins the topmost
    // match so the tap resolves a single node even when the row cell and its inner StaticText
    // both carry the label.
    //
    // The guarantee survives the Maestro fallback too. Under the default PREFER_NODE_SELECTOR
    // mode, if the node-selector tap returns no node (transient tree-fetch failure, row stops
    // resolving between the `findMatches` probe and the tap), `TapOnByElementSelector` falls
    // back to Maestro — whose lowering turns `accessibilityTextRegex` into legacy `textRegex`
    // (text | hintText | accessibilityText), which a typed query CAN satisfy. But the same
    // lowering also carries `childOf` through, so even there the match set stays inside the
    // results list and excludes the search field.
    const rowSelector: TrailblazeNodeSelector = {
      iosMaestro: { accessibilityTextRegex: `.*${escapeRegExp(rowText)}.*` },
      childOf: { iosMaestro: { accessibilityTextRegex: RESULTS_LIST_LABEL } },
      index: 0,
    };
    // Bounded wait for the row to render (`findMatches` re-polls the live hierarchy
    // until a match appears or the budget elapses — no fixed sleep),
    // plus a distinct error for "results exist but none is labeled `rowText`" — a different
    // failure from the no-results branch above (wrong rowText vs wrong query).
    const rows = await ctx.tools.findMatches({
      selector: rowSelector,
      timeoutMs: ROW_WAIT_MS,
    });
    if (rows.length === 0) {
      throw new Error(
        `contacts_ios_searchContacts: query "${query}" shows no "No Results" banner, ` +
          `but no row labeled "${rowText}" appeared in the "${RESULTS_LIST_LABEL}" list ` +
          `within ${ROW_WAIT_MS}ms.`,
      );
    }
    await ctx.tools.tapOnElementBySelector({
      reason: `Open the "${rowText}" search result row.`,
      nodeSelector: rowSelector,
    });
    return `Searched for "${query}" and opened the row matching "${rowText}".`;
  },
);

/** Escapes regex metacharacters so a contact name is matched literally. */
function escapeRegExp(value: string): string {
  return value.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
}
