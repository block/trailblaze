// Shared helpers for the Wikipedia example trailmap's scripted tools.
//
// Every tool in this directory imports from here. Keeping defaults + small
// validators centralized means each tool file stays focused on the workflow
// it actually exercises.

import type { ToolContext, TrailblazeNodeSelector } from "@trailblaze/scripting";

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
 * The `ctx.tools.X(...)` dispatch contract throws on inner-tool failure,
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
 * Sub-second presence probe via `findMatches`. Returns true if the captured
 * ARIA tree contains at least one node matching the given selector whose
 * bounds are populated and non-zero (i.e. the element actually rendered).
 *
 * Avoids the default ~30s Playwright action timeout that
 * `web_verifyElementVisible` would otherwise pay when the element isn't
 * there: `findMatches` is read-only and snapshot-cached, so a "is this on
 * screen?" probe costs at most one view-hierarchy capture per tool invocation
 * regardless of how many times it's called.
 *
 * The selector matches against the captured Web ARIA tree — use the
 * `selectors.web({ ... })` factory to construct one. For elements that have a
 * stable `id` or `data-testid`, `cssSelector` is the most direct fit (the
 * captured node carries `#${id}` or `[data-testid="..."]` as its canonical
 * selector and the resolver matches by equality). For elements whose identity
 * is class-based or attribute-pattern-based, prefer the ARIA shape
 * (`ariaRole` + `ariaNameRegex`) since `cssSelector` is equality-matched, not
 * query-selector-matched.
 *
 * **SDK alternative:** `captureViewHierarchy(client, [...selectors])` from
 * `@trailblaze/scripting` returns a sync [ViewHierarchy] whose `visible(...)`
 * predicate matches the same one-shot semantics this helper provides, with the
 * added benefit that multiple selectors share one capture-and-evaluate pass.
 * New tools that probe multiple selectors per tool body should reach for the
 * ViewHierarchy snapshot directly; this helper remains for single-selector
 * sites that pre-date the snapshot primitive and don't benefit from the
 * shared capture.
 */
export async function elementIsVisible(
  ctx: ToolContext,
  selector: TrailblazeNodeSelector,
): Promise<boolean> {
  try {
    const matches = await ctx.tools.findMatches({ selector });
    // `Bounds` carries only `left`/`top`/`right`/`bottom` over the wire — the
    // Kotlin `width`/`height` are computed getters with no backing fields, so
    // they're not in the JSON payload. Compute the rect dimensions here to
    // avoid the silent-`undefined > 0` bug a `.width` / `.height` read would
    // produce (returning false unconditionally and disabling every probe).
    return matches.some(
      (m) =>
        m.bounds != null &&
        m.bounds.right - m.bounds.left > 0 &&
        m.bounds.bottom - m.bounds.top > 0,
    );
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
export async function currentHostname(ctx: ToolContext): Promise<string | null> {
  try {
    const url = await ctx.tools.web_currentUrl({});
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
  ctx: ToolContext,
  predicate: HostnamePredicate,
  fallbackUrl: string,
): Promise<{ navigated: boolean; hostname: string | null }> {
  const hostname = await currentHostname(ctx);
  if (predicate(hostname)) {
    return { navigated: false, hostname };
  }
  await ctx.tools.web_navigate({
    action: "GOTO",
    url: fallbackUrl,
  });
  return { navigated: true, hostname: await currentHostname(ctx) };
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
