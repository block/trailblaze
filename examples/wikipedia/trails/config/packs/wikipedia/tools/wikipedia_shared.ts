// Shared helpers for the Wikipedia example pack's scripted tools.
//
// Every tool in this directory imports from here. Keeping defaults + small
// validators centralized means each tool file stays focused on the workflow
// it actually exercises.

import type { TrailblazeClient, TrailblazeContext } from "@trailblaze/scripting";

export const WIKIPEDIA_ORIGIN = "https://en.wikipedia.org";
export const WIKIPEDIA_MAIN_PAGE = `${WIKIPEDIA_ORIGIN}/wiki/Main_Page`;

// Selectors below target Wikipedia's current Vector-2022 skin. Each value is a
// single `css=` ref whose payload is a standard CSS selector list — commas
// inside the payload are valid CSS, while a second `css=` prefix would not be
// (the driver parses the prefix exactly once at the start of the string).
export const SELECTORS = Object.freeze({
  // Header search box (Vector-2022 wraps it in a Codex input, but the
  // underlying input still has name="search").
  searchInput: "css=input[name='search']",
  // Header search submit. The visible button sits inside the search form.
  searchSubmit: "css=#searchform button[type='submit'], button[type='submit'][aria-label*='Search' i]",
  // Article first heading.
  firstHeading: "css=#firstHeading",
  // Article body wrapper.
  articleBody: "css=#mw-content-text",
  // "Random article" sidebar link.
  randomArticleLink: "css=#n-randompage a",
  // Fundraising banner close button (only present some of the year).
  bannerCloseButton: "css=button.frb-close, .frb-close, .cdx-button.frb-close",
  // Header language menu trigger.
  languageMenuButton: "css=#p-lang-btn, .mw-portlet-lang .vector-menu-heading",
  // Footer privacy policy link, used as a 'scrolled to footer?' sanity check.
  footerPrivacyLink: "css=#footer-places-privacy a, a[href*='Privacy_policy']",
} as const);

/**
 * Throws if no live Trailblaze session context was injected — every scripted
 * tool in this pack needs a real session to drive the browser.
 */
export function requireSessionContext(
  ctx: TrailblazeContext | undefined,
): asserts ctx is TrailblazeContext {
  if (!ctx) {
    throw new Error("This Wikipedia example tool requires a live Trailblaze session context.");
  }
}

/** Returns `value` if it's a non-empty string; otherwise the `fallback`. */
export function nonEmptyString(value: unknown, fallback: string): string {
  return typeof value === "string" && value.length > 0 ? value : fallback;
}

/**
 * Builds a Wikipedia article URL from an article title. Whitespace becomes
 * underscores (which is how Wikipedia canonicalizes article slugs). Slashes
 * are preserved so subpage titles like `User:Foo/bar` route correctly.
 */
export function articleUrl(title: string): string {
  return `${WIKIPEDIA_ORIGIN}/wiki/${title.replace(/\s+/g, "_")}`;
}

/** Builds a Wikipedia article URL for a non-English language code. */
export function articleUrlForLanguage(langCode: string, title: string): string {
  return `https://${langCode}.wikipedia.org/wiki/${title.replace(/\s+/g, "_")}`;
}

/**
 * Runs `attempt` and returns `true` on success, `false` if it throws. Used to
 * probe for conditional UI elements (banners, optional sections) without the
 * surrounding tool failing when the element isn't there.
 *
 * The `client.tools.X(...)` dispatch contract throws on inner-tool failure,
 * so this is the lowest-common-denominator way to express "if X is visible,
 * do Y" without each call site sprouting its own try/catch.
 */
export async function tryOrFalse<T>(attempt: () => Promise<T>): Promise<boolean> {
  try {
    await attempt();
    return true;
  } catch {
    return false;
  }
}

/**
 * Sub-second presence probe via `web_evaluate`. Returns true if the page has
 * at least one element matching the given CSS selector that's also rendered
 * (has a non-zero client rect). Avoids the default ~30s Playwright action
 * timeout that `web_verify_element_visible` would otherwise pay when the
 * element isn't there.
 *
 * @param cssSelector Plain CSS selector (no `css=` prefix).
 */
export async function elementIsVisible(
  client: TrailblazeClient,
  cssSelector: string,
): Promise<boolean> {
  // The JS expression is run inside the browser context. We render the
  // selector string with JSON.stringify so quotes and other characters don't
  // need escaping at the JS level. The IIFE returns "true"/"false" as the
  // string `result` value that `web_evaluate` surfaces.
  const script = `(() => {
    const el = document.querySelector(${JSON.stringify(cssSelector)});
    if (!el) return "false";
    const rect = el.getBoundingClientRect();
    return (rect.width > 0 && rect.height > 0) ? "true" : "false";
  })()`;
  try {
    const result = await client.tools.web_evaluate({ script });
    return result.textContent.trim() === "true";
  } catch {
    return false;
  }
}

/**
 * Returns the current page's URL hostname (e.g. `en.wikipedia.org`) — or
 * `null` if the URL can't be parsed or isn't available. Used by `ensureOn`
 * for hostname-based routing decisions; substring matching against the raw
 * URL would let `wikipedia.org` smuggle in via paths like
 * `https://example.com/?ref=wikipedia.org` (CodeQL flags this).
 */
export async function currentHostname(client: TrailblazeClient): Promise<string | null> {
  try {
    const url = (await client.tools.web_currentUrl({})).textContent;
    if (url.length === 0) return null;
    return new URL(url).hostname;
  } catch {
    return null;
  }
}

export type HostnamePredicate = (hostname: string | null) => boolean;

/**
 * Navigates to `fallbackUrl` only when the page's current hostname does NOT
 * satisfy `predicate`. Idempotent — when the page is already in the right
 * place, no navigation happens. Centralizes the
 * `web_currentUrl` → check → `web_navigate` block that several tools were
 * open-coding.
 */
export async function ensureOn(
  client: TrailblazeClient,
  predicate: HostnamePredicate,
  fallbackUrl: string,
): Promise<{ navigated: boolean; hostname: string | null }> {
  const hostname = await currentHostname(client);
  if (predicate(hostname)) {
    return { navigated: false, hostname };
  }
  await client.tools.web_navigate({
    action: "GOTO",
    url: fallbackUrl,
  });
  return { navigated: true, hostname: await currentHostname(client) };
}

/**
 * Predicate for `ensureOn` that matches any *.wikipedia.org hostname.
 * Anchored to the suffix so `notreally-wikipedia.org` doesn't slip through.
 */
export function isWikipediaHostname(hostname: string | null): boolean {
  return typeof hostname === "string"
    && (hostname === "wikipedia.org" || hostname.endsWith(".wikipedia.org"));
}

/** Predicate for `ensureOn` that matches the English Wikipedia hostname. */
export function isEnglishWikipediaHostname(hostname: string | null): boolean {
  return hostname === "en.wikipedia.org";
}
