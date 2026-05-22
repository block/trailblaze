import type { TrailblazeClient, TrailblazeContext } from "@trailblaze/scripting";
import {
  SELECTORS,
  WIKIPEDIA_MAIN_PAGE,
  ensureOn,
  isWikipediaHostname,
  nonEmptyString,
  requireSessionContext,
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
 * Drives Wikipedia's header search. Two branches:
 *   1. `openFirstResult` true (default) — types the query, clicks the search
 *      submit button, then asserts `#firstHeading` is visible on the
 *      destination article (not just the heading text, which could match a
 *      search-results page snippet) and confirms the heading text matches
 *      `expectedHeading`.
 *   2. `openFirstResult` false — types the query but does not submit, so a
 *      caller can subsequently verify autocomplete suggestions are visible.
 *
 * If the page isn't currently on any Wikipedia host, opens the main page
 * first so the header search input is reachable.
 */
export async function wikipedia_web_searchAndOpenFirstResult(
  args: SearchAndOpenFirstResultArgs,
  ctx: TrailblazeContext | undefined,
  client: TrailblazeClient,
): Promise<string> {
  requireSessionContext(ctx);

  const query = nonEmptyString(args?.query, "Trailblazer");
  const expectedHeading = nonEmptyString(args?.expectedHeading, query);
  const openFirstResult = args?.openFirstResult !== false;

  // Hostname-anchored check (not URL substring). Substring matching of
  // `wikipedia.org` against the raw URL is unsafe — arbitrary attacker-
  // controlled hosts can embed the string (e.g.
  // `https://evil.example/?u=wikipedia.org`). Always parse the URL and
  // compare hostname suffix.
  await ensureOn(client, isWikipediaHostname, WIKIPEDIA_MAIN_PAGE);

  await client.tools.web_type({
    ref: SELECTORS.searchInput,
    text: query,
  });

  if (!openFirstResult) {
    return `Typed "${query}" into Wikipedia search and stopped (no submit).`;
  }

  await client.tools.web_click({
    ref: SELECTORS.searchSubmit,
  });
  // Anchor the assertion to the article header element; the text check
  // afterwards confirms the article matches what the caller asked for.
  // Without the element-visible check, the text assertion could pass on
  // Wikipedia's search-results page (the query appears in result snippets).
  await client.tools.web_verify_element_visible({
    ref: SELECTORS.firstHeading,
  });
  await client.tools.web_verify_text_visible({
    text: expectedHeading,
  });

  return `Searched for "${query}" and verified result heading "${expectedHeading}".`;
}
