import { trailblaze } from "@trailblaze/scripting";
import {
  SELECTORS,
  articleUrl,
  nonEmptyString,
  tryOrFalse,
} from "./wikipedia_shared";

const DEFAULT_TITLE = "Wikipedia";

export interface OpenArticleArgs {
  /** Article title to open (spaces → underscores in URL). */
  title?: string;
  /** Visible heading text to assert. Defaults to `title`. */
  expectedHeading?: string;
  /** When true (default), tries to close any visible fundraising banner. */
  dismissBanner?: boolean;
}

/**
 * Open a specific Wikipedia article by its title. Use this whenever the task
 * is to read, open, navigate to, or view a particular Wikipedia article —
 * e.g. "open the Albert Einstein article", "go to the Python (programming
 * language) page", "navigate to the Shakespeare article". Spaces in the
 * title are normalized to underscores (Wikipedia's URL convention) so you
 * can pass the title verbatim. Verifies the article rendered by checking
 * the #firstHeading element is visible.
 */
// Verification asserts both that `#firstHeading` is visible AND that its text
// matches `expectedHeading`. The element-visible check pins the assertion to
// the actual article header element, so the text check can't false-positive
// on a search-results snippet or sidebar listing that happens to repeat the
// title elsewhere on the page.
export const wikipedia_web_openArticle = trailblaze.tool<OpenArticleArgs>(
  { supportedPlatforms: ["web"], requiresContext: true },
  async (input, ctx) => {
    const title = nonEmptyString(input.title, DEFAULT_TITLE);
    const expectedHeading = nonEmptyString(input.expectedHeading, title);
    const dismissBanner = input.dismissBanner !== false;

    await ctx.tools.web_navigate({
      action: "GOTO",
      url: articleUrl(title),
    });
    await ctx.tools.web_verifyElementVisible({
      ref: SELECTORS.firstHeading,
    });
    await ctx.tools.web_verifyTextVisible({
      text: expectedHeading,
    });

    if (dismissBanner) {
      await tryOrFalse(() =>
        ctx.tools.wikipedia_web_dismissBannerIfPresent({}),
      );
    }

    return `Opened article "${title}" and verified heading "${expectedHeading}".`;
  },
);
