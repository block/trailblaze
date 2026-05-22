import type { TrailblazeClient, TrailblazeContext } from "@trailblaze/scripting";
import {
  WIKIPEDIA_MAIN_PAGE,
  ensureOn,
  requireSessionContext,
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
 * Verifies one of the four main-page sections is present. Picking the
 * section by short code lets a single tool cover four near-identical
 * workflows; each main-page test trail just picks its section.
 *
 * Anchors verification on the section's stable wrapper id (`#mp-tfa` /
 * `#mp-itn` / `#mp-dyk` / `#mp-otd`) — those have outlasted multiple skin
 * revisions on Main_Page. The visible heading text used to be the primary
 * assertion but drifts day to day ("Did you know ..." vs "Did you know"),
 * which broke recorded trails — now it's only a sanity belt-and-suspenders
 * applied when the anchor is reachable.
 */
export async function wikipedia_web_openMainPageSection(
  args: OpenMainPageSectionArgs,
  ctx: TrailblazeContext | undefined,
  client: TrailblazeClient,
): Promise<string> {
  requireSessionContext(ctx);

  const sectionKey = (typeof args?.section === "string" ? args.section.toLowerCase() : "") as SectionKey;
  const info = SECTION_INFO[sectionKey];
  if (!info) {
    throw new Error(
      `wikipedia_web_openMainPageSection: unknown section "${args?.section}". Expected one of: ${Object.keys(SECTION_INFO).join(", ")}.`,
    );
  }

  if (args?.ensureOnMainPage !== false) {
    // `ensureOn`'s predicate runs on hostname only; we want a fuller "are
    // we exactly on Main_Page?" check here, so we use the lower-level
    // `web_currentUrl` directly and reuse the same fallback URL.
    let onMainPage = false;
    await tryOrFalse(async () => {
      onMainPage = isOnMainPage((await client.tools.web_currentUrl({})).textContent);
    });
    if (!onMainPage) {
      await ensureOn(client, () => false, WIKIPEDIA_MAIN_PAGE);
    }
  }

  // Primary assertion: structural anchor. `web_verify_element_visible` fails
  // loudly if the element isn't there, which is the behavior we want — the
  // four main-page section wrappers are always present on Main_Page.
  await client.tools.web_verify_element_visible({
    ref: `css=${info.anchorCss}`,
  });

  // Secondary check: heading text. We probe with `tryOrFalse` rather than
  // letting the assertion family throw so a stale heading-text revision is
  // recoverable on next-day reruns (the anchor already passed; the section
  // exists). The recording captures both calls so a future day's text
  // shift can't break the test.
  const textVisible = await tryOrFalse(() =>
    client.tools.web_verify_text_visible({ text: info.headingText }),
  );

  return textVisible
    ? `Verified main page section "${sectionKey}" — anchor ${info.anchorCss} visible, heading "${info.headingText}" present.`
    : `Verified main page section "${sectionKey}" — anchor ${info.anchorCss} visible (heading text "${info.headingText}" not currently matched; section copy may have shifted).`;
}
