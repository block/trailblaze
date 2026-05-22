import type { TrailblazeClient, TrailblazeContext } from "@trailblaze/scripting";
import {
  WIKIPEDIA_MAIN_PAGE,
  requireSessionContext,
  tryOrFalse,
} from "./wikipedia_shared";

export interface OpenMainPageArgs {
  /** When true (default), tries to close any visible fundraising banner. */
  dismissBanner?: boolean;
}

/**
 * Trailhead tool: navigates to Wikipedia's main page from any prior state and
 * verifies it loaded by asserting the "Welcome to Wikipedia" heading text is
 * visible.
 */
export async function wikipedia_web_openMainPage(
  args: OpenMainPageArgs,
  ctx: TrailblazeContext | undefined,
  client: TrailblazeClient,
): Promise<string> {
  requireSessionContext(ctx);
  const dismissBanner = args?.dismissBanner !== false;

  await client.tools.web_navigate({
    action: "GOTO",
    url: WIKIPEDIA_MAIN_PAGE,
  });

  // The phrase "Welcome to Wikipedia" appears in the main page's introductory
  // header — a stable visible-text anchor that the verify tool can match.
  await client.tools.web_verify_text_visible({
    text: "Welcome to Wikipedia",
  });

  let bannerHandled = false;
  if (dismissBanner) {
    bannerHandled = await tryOrFalse(() =>
      client.tools.wikipedia_web_dismissBannerIfPresent({}),
    );
  }

  const bannerSuffix = !dismissBanner
    ? ""
    : bannerHandled
      ? " (banner handled)"
      : " (no banner shown)";
  return `Opened Wikipedia main page and verified "Welcome to Wikipedia"${bannerSuffix}.`;
}
