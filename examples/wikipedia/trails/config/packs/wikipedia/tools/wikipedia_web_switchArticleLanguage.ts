import type { TrailblazeClient, TrailblazeContext } from "@trailblaze/scripting";
import {
  SELECTORS,
  articleUrlForLanguage,
  nonEmptyString,
  requireSessionContext,
} from "./wikipedia_shared";

// Wikipedia accepts a wide range of language codes — including two-letter
// ISO codes (`es`, `fr`), three-letter codes (`ceb`, `zho`), regional
// variants (`zh-min-nan`, `be-tarask`), and constructed-language codes
// (`eo`, `tlh`). We don't try to enumerate them; an invalid code surfaces
// as a 404 when the navigation happens, which is the same error condition
// the caller would see from any other unreachable URL.
//
// We DO enforce a permissive shape check to catch obvious typos
// (whitespace, path separators, etc.) at tool-entry time so the error
// message names the problem field rather than a navigation failure deep
// inside Playwright.
const LANG_CODE_PATTERN = /^[a-z]{2,3}(-[a-z0-9]+)*$/i;

export interface SwitchArticleLanguageArgs {
  /** English article title. */
  title?: string;
  /** Wikipedia language code (`es`, `fr`, `zh-min-nan`, …). */
  languageCode?: string;
  /** Visible heading text in the localized article. */
  expectedHeading?: string;
}

/**
 * Drives a same-article language switch by navigating to the matching
 * subdomain. The header language picker varies a lot between skins and
 * articles (some have a dropdown, some inline a list, some hide it behind a
 * chevron); jumping to the URL directly keeps the test focused on the
 * user-visible outcome rather than the picker's exact DOM.
 *
 * Verification asserts `#firstHeading` is visible AND that its text matches
 * `expectedHeading`. The element-visible check pins the assertion to the
 * actual article header — without it, the text check could match content
 * elsewhere on a non-article page (e.g. a sister-projects portal landing).
 */
export async function wikipedia_web_switchArticleLanguage(
  args: SwitchArticleLanguageArgs,
  ctx: TrailblazeContext | undefined,
  client: TrailblazeClient,
): Promise<string> {
  requireSessionContext(ctx);

  const title = nonEmptyString(args?.title, "Albert Einstein");
  const languageCode = nonEmptyString(args?.languageCode, "es").toLowerCase();
  const expectedHeading = nonEmptyString(args?.expectedHeading, title);

  if (!LANG_CODE_PATTERN.test(languageCode)) {
    throw new Error(
      `wikipedia_web_switchArticleLanguage: language code "${languageCode}" doesn't look like a Wikipedia language subdomain (expected letters, optionally with hyphen-separated regional suffixes, e.g. "es", "zh-min-nan").`,
    );
  }

  await client.tools.web_navigate({
    action: "GOTO",
    url: articleUrlForLanguage(languageCode, title),
  });
  await client.tools.web_verify_element_visible({
    ref: SELECTORS.firstHeading,
  });
  await client.tools.web_verify_text_visible({ text: expectedHeading });

  return `Switched "${title}" to ${languageCode}.wikipedia.org and verified heading "${expectedHeading}".`;
}
