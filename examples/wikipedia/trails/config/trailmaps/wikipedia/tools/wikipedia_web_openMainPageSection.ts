import { trailblaze } from "@trailblaze/scripting";
import {
  WIKIPEDIA_MAIN_PAGE,
  tryOrFalse,
} from "./wikipedia_shared";

type SectionKey = "tfa" | "itn" | "dyk" | "otd";

interface SectionInfo {
  readonly anchorCss: string;
  readonly headingText: string;
}

// Each section gets a structural anchor (`#mp-<id>`) AND a heading-text
// fallback. The anchors are baked into Wikipedia's Main_Page template and
// have outlived multiple skin revisions; the visible heading text drifts
// more (e.g. "Did you know..." → "Did you know" → "Did you know ...") and is
// what makes pure text-based recordings break across days. We verify the
// anchor as the primary assertion and only fall back to text when the
// anchor isn't there.
const SECTION_INFO: Readonly<Record<SectionKey, SectionInfo>> = Object.freeze({
  tfa: { anchorCss: "#mp-tfa", headingText: "From today's featured article" },
  itn: { anchorCss: "#mp-itn", headingText: "In the news" },
  dyk: { anchorCss: "#mp-dyk", headingText: "Did you know" },
  otd: { anchorCss: "#mp-otd", headingText: "On this day" },
});

// Predicate: we're on the Main_Page only when the current URL's path is
// exactly `/wiki/Main_Page` on en.wikipedia.org. Substring matching against
// the raw URL would let arbitrary hosts containing `wikipedia.org` slip
// through (CodeQL incomplete-substring-sanitization).
function isOnMainPage(currentUrl: string | null): boolean {
  if (!currentUrl) return false;
  try {
    const u = new URL(currentUrl);
    return u.hostname === "en.wikipedia.org" && u.pathname === "/wiki/Main_Page";
  } catch {
    return false;
  }
}

export interface OpenMainPageSectionArgs {
  /** Section short code: tfa | itn | dyk | otd. */
  section?: string;
  /** Open Main_Page first if not there (default true). */
  ensureOnMainPage?: boolean;
}

/**
 * Verify one of Wikipedia's four Main_Page sections is present and visible.
 * Use this whenever the task is to verify a main-page section: "Did you
 * know", "In the news", "On this day", or "From today's featured article"
 * (also known as DYK, ITN, OTD, TFA). Pass the section short code to pick
 * which one. The tool anchors verification on the section's stable wrapper
 * element id (`#mp-dyk` etc.) — which survives Wikipedia's day-to-day copy
 * drift — rather than the visible heading text (which rotates daily).
 */
// Anchors verification on the section's stable wrapper id (`#mp-tfa` /
// `#mp-itn` / `#mp-dyk` / `#mp-otd`) — those have outlasted multiple skin
// revisions on Main_Page. The visible heading text used to be the primary
// assertion but drifts day to day ("Did you know ..." vs "Did you know"),
// which broke recorded trails — now it's only a sanity belt-and-suspenders
// applied when the anchor is reachable.
export const wikipedia_web_openMainPageSection = trailblaze.tool<OpenMainPageSectionArgs>(
  { supportedPlatforms: ["web"], requiresContext: true },
  async (input, ctx) => {
    // Normalize to a lowercase short code if the caller supplied a string;
    // anything else (number, missing, etc.) collapses to "" which fails the
    // lookup below. Capture the original `input.section` separately so the
    // error message prints what the caller actually passed instead of the
    // normalized form (which would be the unhelpful empty string in the
    // non-string case).
    const rawSection = input.section;
    const sectionKey = (typeof rawSection === "string" ? rawSection.toLowerCase() : "") as SectionKey;
    const info = SECTION_INFO[sectionKey];
    if (!info) {
      // Show the raw input for type/format diagnosis ("got 123" vs "got
      // 'tfa '" vs "got undefined") and the normalized key for lookup
      // diagnosis (catches casing mistakes — "TFA" → lookup miss on "tfa").
      throw new Error(
        `wikipedia_web_openMainPageSection: unknown section "${String(rawSection)}" (normalized: "${sectionKey}"). Expected one of: ${Object.keys(SECTION_INFO).join(", ")}.`,
      );
    }

    if (input.ensureOnMainPage !== false) {
      // `ensureOn`'s predicate runs on hostname only; we want a fuller "are we
      // exactly on Main_Page?" check here (path matters), so we use the lower-
      // level `web_currentUrl` directly. Once we know we're NOT on Main_Page
      // we navigate directly — wrapping in `ensureOn(ctx, () => false, …)`
      // would force `ensureOn` to repeat the `web_currentUrl` lookup we just
      // performed and pay a second post-nav lookup for the hostname it returns,
      // both of which are wasted work here.
      let onMainPage = false;
      await tryOrFalse(async () => {
        const url = await ctx.tools.web_currentUrl({});
        onMainPage = isOnMainPage(url);
      });
      if (!onMainPage) {
        await ctx.tools.web_navigate({
          action: "GOTO",
          url: WIKIPEDIA_MAIN_PAGE,
        });
      }
    }

    // Primary assertion: structural anchor. `web_verifyElementVisible` fails
    // loudly if the element isn't there, which is the behavior we want — the
    // four main-page section wrappers are always present on Main_Page.
    await ctx.tools.web_verifyElementVisible({
      ref: `css=${info.anchorCss}`,
    });

    // Secondary check: heading text. We probe with `tryOrFalse` rather than
    // letting the assertion family throw so a stale heading-text revision is
    // recoverable on next-day reruns (the anchor already passed; the section
    // exists). The recording captures both calls so a future day's text
    // shift can't break the test.
    const textVisible = await tryOrFalse(() =>
      ctx.tools.web_verifyTextVisible({ text: info.headingText }),
    );

    return textVisible
      ? `Verified main page section "${sectionKey}" — anchor ${info.anchorCss} visible, heading "${info.headingText}" present.`
      : `Verified main page section "${sectionKey}" — anchor ${info.anchorCss} visible (heading text "${info.headingText}" not currently matched; section copy may have shifted).`;
  },
);
