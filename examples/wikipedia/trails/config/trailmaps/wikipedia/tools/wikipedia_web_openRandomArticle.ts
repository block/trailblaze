import { trailblaze } from "@trailblaze/scripting";
import {
  SELECTORS,
  WIKIPEDIA_MAIN_PAGE,
  ensureOn,
  isWikipediaHostname,
} from "./wikipedia_shared";

export interface OpenRandomArticleArgs {
  /** Open the main page first if not on Wikipedia. Default true. */
  ensureOnWikipedia?: boolean;
}

/**
 * Open a random Wikipedia article via the "Random article" link and verify an
 * article heading is visible. The title is unpredictable, so don't assert
 * specific heading text afterwards.
 */
// Hostname check is anchored to `*.wikipedia.org` rather than a URL
// substring to satisfy the CodeQL alert against `includes("wikipedia.org")`.
export const wikipedia_web_openRandomArticle = trailblaze.tool<OpenRandomArticleArgs>(
  { supportedPlatforms: ["web"], requiresContext: true },
  async (input, ctx) => {
    if (input.ensureOnWikipedia !== false) {
      await ensureOn(ctx, isWikipediaHostname, WIKIPEDIA_MAIN_PAGE);
    }

    await ctx.tools.web_click({
      ref: SELECTORS.randomArticleLink,
    });
    await ctx.tools.web_verifyElementVisible({
      ref: SELECTORS.firstHeading,
    });

    return "Clicked Random article and verified a new article page loaded.";
  },
);
