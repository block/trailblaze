import type { TrailblazeClient, TrailblazeContext } from "@trailblaze/scripting";
import {
  SELECTORS,
  articleUrl,
  nonEmptyString,
  requireSessionContext,
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
 * Trailhead tool: opens a specific Wikipedia article by title and verifies
 * its heading. Reaching a known article gives downstream steps a
 * deterministic starting point that doesn't depend on which article the
 * day's Main Page is promoting.
 *
 * Verification asserts both that `#firstHeading` is visible AND that its
 * text matches `expectedHeading`. The `web_verify_element_visible` check
 * pins the assertion to the actual article header element, so the text
 * check can't false-positive on a search-results snippet or sidebar listing
 * that happens to repeat the title elsewhere on the page.
 */
export async function wikipedia_web_openArticle(
  args: OpenArticleArgs,
  ctx: TrailblazeContext | undefined,
  client: TrailblazeClient,
): Promise<string> {
  requireSessionContext(ctx);

  const title = nonEmptyString(args?.title, DEFAULT_TITLE);
  const expectedHeading = nonEmptyString(args?.expectedHeading, title);
  const dismissBanner = args?.dismissBanner !== false;

  await client.tools.web_navigate({
    action: "GOTO",
    url: articleUrl(title),
  });
  await client.tools.web_verify_element_visible({
    ref: SELECTORS.firstHeading,
  });
  await client.tools.web_verify_text_visible({
    text: expectedHeading,
  });

  if (dismissBanner) {
    await tryOrFalse(() =>
      client.tools.wikipedia_web_dismissBannerIfPresent({}),
    );
  }

  return `Opened article "${title}" and verified heading "${expectedHeading}".`;
}
