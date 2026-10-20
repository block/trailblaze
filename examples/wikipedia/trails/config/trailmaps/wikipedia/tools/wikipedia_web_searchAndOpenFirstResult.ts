import { trailblaze } from "@trailblaze/scripting";
import {
  SELECTORS,
  WIKIPEDIA_MAIN_PAGE,
  ensureOn,
  isWikipediaHostname,
  nonEmptyString,
  articleHeadingRef,
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
    // The article header element proves an article opened rather than the
    // search-results page; the heading check confirms it is the one the caller
    // asked for.
    await ctx.tools.web_verifyElementVisible({
      ref: SELECTORS.firstHeading,
    });
    await ctx.tools.web_verifyElementVisible({
      ref: articleHeadingRef(expectedHeading),
    });

    return `Searched for "${query}" and verified result heading "${expectedHeading}".`;
  },
);
