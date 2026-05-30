import { trailblaze } from "@trailblaze/scripting";
import {
  SELECTORS,
  WIKIPEDIA_MAIN_PAGE,
  ensureOn,
  isWikipediaHostname,
} from "./wikipedia_shared";

export interface OpenRandomArticleArgs {
  /** Open the main page first if not on Wikipedia. */
  ensureOnWikipedia?: boolean;
}

/**
 * Open a random Wikipedia article via the sidebar's "Random article" link.
 * Use this whenever the task is to navigate to a random article, click
 * Random article, jump to a random page, or surf to an unpredictable
 * article. Verifies that a new article page rendered (its #firstHeading
 * is visible) — the resulting title is non-deterministic so don't try to
 * assert specific heading text afterwards.
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
