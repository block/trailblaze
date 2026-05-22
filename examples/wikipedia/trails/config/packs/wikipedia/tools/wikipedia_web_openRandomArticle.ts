import type { TrailblazeClient, TrailblazeContext } from "@trailblaze/scripting";
import {
  SELECTORS,
  WIKIPEDIA_MAIN_PAGE,
  ensureOn,
  isWikipediaHostname,
  requireSessionContext,
} from "./wikipedia_shared";

export interface OpenRandomArticleArgs {
  /** Open the main page first if not on Wikipedia. */
  ensureOnWikipedia?: boolean;
}

/**
 * Clicks Wikipedia's "Random article" sidebar link and verifies that an
 * article page (with #firstHeading visible) has loaded. The resulting
 * article title is non-deterministic, so the assertion targets the
 * structural anchor rather than any specific heading text.
 *
 * Hostname check is anchored to `*.wikipedia.org` rather than a URL
 * substring to satisfy the CodeQL alert against
 * `includes("wikipedia.org")`.
 */
export async function wikipedia_web_openRandomArticle(
  args: OpenRandomArticleArgs,
  ctx: TrailblazeContext | undefined,
  client: TrailblazeClient,
): Promise<string> {
  requireSessionContext(ctx);

  if (args?.ensureOnWikipedia !== false) {
    await ensureOn(client, isWikipediaHostname, WIKIPEDIA_MAIN_PAGE);
  }

  await client.tools.web_click({
    ref: SELECTORS.randomArticleLink,
  });
  await client.tools.web_verify_element_visible({
    ref: SELECTORS.firstHeading,
  });

  return "Clicked Random article and verified a new article page loaded.";
}
