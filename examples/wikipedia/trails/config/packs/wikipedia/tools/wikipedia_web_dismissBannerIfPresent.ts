import type { TrailblazeClient, TrailblazeContext } from "@trailblaze/scripting";
import {
  elementIsVisible,
  requireSessionContext,
  tryOrFalse,
} from "./wikipedia_shared";

// Probe selector (no `css=` prefix; this string is passed to
// document.querySelector inside the page, not to the Playwright tool).
// Mirror the selector list that the shared `bannerCloseButton` ref uses so
// the probe and the click stay aligned.
const BANNER_PROBE_SELECTOR = "button.frb-close, .frb-close, .cdx-button.frb-close";

// Click ref (with `css=` prefix; this one is for the Playwright tool dispatcher).
const BANNER_CLICK_REF = "css=button.frb-close, .frb-close, .cdx-button.frb-close";

export type DismissBannerIfPresentArgs = Record<string, never>;

/**
 * Conditional UI handler: if a fundraising banner is visible, click its close
 * control; otherwise no-op. Returning success in both branches lets callers
 * compose this tool unconditionally at the top of a workflow without
 * sprinkling try/catch at every call site.
 *
 * Probes for the banner with a sub-second `web_evaluate` check before calling
 * `web_click` — the click tool otherwise pays Playwright's default action
 * timeout (~30 s) on the common no-banner path.
 */
export async function wikipedia_web_dismissBannerIfPresent(
  _args: DismissBannerIfPresentArgs,
  ctx: TrailblazeContext | undefined,
  client: TrailblazeClient,
): Promise<string> {
  requireSessionContext(ctx);

  if (!(await elementIsVisible(client, BANNER_PROBE_SELECTOR))) {
    return "No Wikipedia banner visible — nothing to dismiss.";
  }

  // `tryOrFalse` still guards the click because the banner can disappear
  // between the probe and the click (e.g. an auto-dismiss script firing).
  const dismissed = await tryOrFalse(() =>
    client.tools.web_click({
      ref: BANNER_CLICK_REF,
    }),
  );

  return dismissed
    ? "Dismissed Wikipedia fundraising banner."
    : "Banner disappeared between probe and click — no action taken.";
}
