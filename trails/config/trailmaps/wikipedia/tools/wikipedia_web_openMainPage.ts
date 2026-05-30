import { trailblaze } from "@trailblaze/scripting";
import { WIKIPEDIA_MAIN_PAGE } from "./wikipedia_shared";

export interface OpenMainPageArgs {
  /** When true (default), tries to close any visible fundraising banner. */
  dismissBanner?: boolean;
}

/**
 * Open Wikipedia's Main_Page. Use this when the task asks to navigate to
 * Wikipedia, open Wikipedia, go to the Wikipedia home page, or start from
 * the main Wikipedia page. Loads en.wikipedia.org/wiki/Main_Page and waits
 * for it to render. Optionally dismisses any fundraising banner that's
 * currently showing so subsequent steps don't have to reason about it.
 */
// (spec, handler) overload: the inline spec object carries `supportedPlatforms`
// + `requiresContext` so the runtime `_meta` gates are populated entirely from
// the `.ts` source — no need for the YAML descriptor's `_meta:` block. The
// analyzer extracts the literal at the call site.
export const wikipedia_web_openMainPage = trailblaze.tool<OpenMainPageArgs>(
  { supportedPlatforms: ["web"], requiresContext: true },
  async (input, ctx) => {
    const dismissBanner = input.dismissBanner !== false;

    await ctx.tools.web_navigate({
      action: "GOTO",
      url: WIKIPEDIA_MAIN_PAGE,
    });

    // The phrase "Welcome to Wikipedia" appears in the main page's introductory
    // header — a stable visible-text anchor that the verify tool can match.
    await ctx.tools.web_verifyTextVisible({
      text: "Welcome to Wikipedia",
    });

    // Build the "did we handle the banner?" suffix by INSPECTING the dismiss
    // tool's return string rather than wrapping the call in `tryOrFalse`. The
    // dismiss tool is documented to never throw — it returns a string in both
    // the banner-present and no-banner cases — so `tryOrFalse` would always
    // resolve `true` and the "(no banner shown)" branch would be dead code.
    // The no-banner return is the only one that begins with the literal "No
    // Wikipedia banner visible"; both other branches start with "Dismissed"
    // or "Banner disappeared", which we treat as "banner was handled".
    let bannerSuffix = "";
    if (dismissBanner) {
      const dismissResult = await ctx.tools.wikipedia_web_dismissBannerIfPresent({});
      bannerSuffix = dismissResult.startsWith("No Wikipedia banner")
        ? " (no banner shown)"
        : " (banner handled)";
    }
    return `Opened Wikipedia main page and verified "Welcome to Wikipedia"${bannerSuffix}.`;
  },
);
