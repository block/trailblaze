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
  /** Submit the search form (default true). */
  openFirstResult?: boolean;
}

/**
 * Search Wikipedia from the header search box. Use this whenever the task
 * is to search Wikipedia for something — e.g. "search for Albert Einstein",
 * "look up Python on Wikipedia", "find articles about Mount Everest". Types
 * the query into the header search input, submits the form, and verifies
 * the resulting article's first heading. Pass `openFirstResult=false` to
 * only type the query without submitting (useful for autocomplete-suggestion
 * tests).
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
