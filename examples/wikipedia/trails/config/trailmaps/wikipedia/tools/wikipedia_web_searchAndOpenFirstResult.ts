import { trailblaze } from "@trailblaze/scripting";
import {
  SELECTORS,
  WIKIPEDIA_MAIN_PAGE,
  ensureOn,
  isWikipediaHostname,
  nonEmptyString,
} from "./wikipedia_shared";

export interface SearchAndOpenFirstResultArgs {
  /** Query to type into the search box. */
  query?: string;
  /** Heading text to assert on the opened article. Defaults to `query`. */
  expectedHeading?: string;
  /** Submit the search and verify the article. Default true; false only types the query, e.g. to check suggestions. */
  openFirstResult?: boolean;
}

/**
 * Search Wikipedia from the header search box, submit, and verify the
 * resulting article's heading.
 */
// Two branches:
//   1. `openFirstResult` true (default) — types the query, clicks the search
//      submit button, then asserts `#firstHeading` is visible on the destination
//      article (not just the heading text, which could match a search-results
//      page snippet) and confirms the heading text matches `expectedHeading`.
//   2. `openFirstResult` false — types the query but does not submit, so a
//      caller can subsequently verify autocomplete suggestions are visible.
// If the page isn't currently on any Wikipedia host, opens the main page first
// so the header search input is reachable.
export const wikipedia_web_searchAndOpenFirstResult = trailblaze.tool<SearchAndOpenFirstResultArgs>(
  { supportedPlatforms: ["web"], requiresContext: true },
  async (input, ctx) => {
    const query = nonEmptyString(input.query, "Trailblazer");
    const expectedHeading = nonEmptyString(input.expectedHeading, query);
    const openFirstResult = input.openFirstResult !== false;

    // Hostname-anchored check (not URL substring). Substring matching of
    // `wikipedia.org` against the raw URL is unsafe — arbitrary attacker-
    // controlled hosts can embed the string (e.g.
    // `https://evil.example/?u=wikipedia.org`). Always parse the URL and
    // compare hostname suffix.
    await ensureOn(ctx, isWikipediaHostname, WIKIPEDIA_MAIN_PAGE);

    await ctx.tools.web_type({
      ref: SELECTORS.searchInput,
      text: query,
    });

    if (!openFirstResult) {
      return `Typed "${query}" into Wikipedia search and stopped (no submit).`;
    }

    await ctx.tools.web_click({
      ref: SELECTORS.searchSubmit,
    });
    // Anchor the assertion to the article header element; the text check
    // afterwards confirms the article matches what the caller asked for.
    // Without the element-visible check, the text assertion could pass on
    // Wikipedia's search-results page (the query appears in result snippets).
    await ctx.tools.web_verifyElementVisible({
      ref: SELECTORS.firstHeading,
    });
    await ctx.tools.web_verifyTextVisible({
      text: expectedHeading,
    });

    return `Searched for "${query}" and verified result heading "${expectedHeading}".`;
  },
);
