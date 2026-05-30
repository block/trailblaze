import { selectors, trailblaze } from "@trailblaze/scripting";
import {
  SELECTORS,
  elementIsVisible,
} from "./wikipedia_shared";

// Number of scroll steps before giving up on References. Articles taller than
// (REFERENCES_MAX_SCROLLS * REFERENCES_SCROLL_PX) tail pixels won't reach
// the section — bump the cap if you start seeing false negatives.
//
// **Latency budget:** each `web_scroll` is bounded by Playwright's default
// action timeout (~30s) but in practice settles in well under 1s against
// Wikipedia. Worst-case 6 × ~30s = ~3 min if the page hangs, but a healthy
// Wikipedia + warm Playwright session completes the loop in under 5s. The
// loop is intentionally NOT wrapped in a per-iteration timeout — a single
// stuck scroll surfaces as a Playwright assertion failure, which is the
// correct signal to investigate rather than retry.
const REFERENCES_MAX_SCROLLS = 6;
const REFERENCES_SCROLL_PX = 1200;

// Probe selector for "is the References heading visible?". Wikipedia's
// Vector-2022 skin renders the section as `<h2 id="References">References</h2>`
// — matching by ARIA `heading` role + accessible-name regex covers the visible
// "References" heading regardless of whether the id lives on the `<h2>` or on a
// child `<span>` (older skins). The captured `cssSelector` field on web nodes
// is equality-matched against a single `#id` / `[data-testid="..."]`, so a
// comma-list CSS selector (the previous shape) wouldn't round-trip through
// `findMatches`.
//
// `ariaNameRegex` is case-sensitive by contract — Wikipedia consistently
// renders this heading as "References" (Title Case), so the pattern is the
// literal word. No `^...$` anchor per the repo's "no anchored regex
// selectors" convention; variants like "References and notes" or
// "References (selected)" are still legitimate matches. A purely
// lowercase "references" (a sub-heading inside another section) won't
// match — the case-sensitive contract is what makes that distinction.
const REFERENCES_PROBE = selectors.web({
  ariaRole: "heading",
  ariaNameRegex: "References",
});

export interface VerifyArticleStructureArgs {
  /** Visible text to assert in #firstHeading. */
  expectedHeading?: string;
  /** Scroll-to-bottom + assert References heading (default true). */
  requireReferences?: boolean;
}

/**
 * Verify the currently loaded page is a well-formed Wikipedia article. Use
 * this whenever the task is to verify an article's structure or layout —
 * confirm a Wikipedia article rendered, check that an article has body
 * content, confirm it has a References section, or sanity-check that
 * navigation actually landed on an article (not a search-results or
 * disambiguation page). Asserts #firstHeading is visible, the body wrapper
 * is visible, and optionally scrolls to confirm the "References" section
 * is present.
 */
// Checks are split across branches so individual failures map back to
// specific structural concerns:
//   1. First heading visible (#firstHeading) — every article has one.
//   2. Body content wrapper visible (#mw-content-text) — confirms the article
//      content actually rendered (not just the chrome).
//   3. Optional References section — present on most full-length articles.
// The References scan uses the fast `elementIsVisible` probe (a
// snapshot-cached `findMatches` over the captured ARIA tree) rather than
// `web_verifyTextVisible` so each iteration of the scroll-and-check loop costs
// ~milliseconds instead of the ~5 s default Playwright assertion timeout. The
// loop scrolls *first* and verifies *second* so the final scroll's
// freshly-rendered content gets checked before the throw.
export const wikipedia_web_verifyArticleStructure = trailblaze.tool<VerifyArticleStructureArgs>(
  { supportedPlatforms: ["web"], requiresContext: true },
  async (input, ctx) => {
    const expectedHeading = typeof input.expectedHeading === "string"
      ? input.expectedHeading
      : "";
    const requireReferences = input.requireReferences !== false;

    await ctx.tools.web_verifyElementVisible({
      ref: SELECTORS.firstHeading,
    });
    if (expectedHeading.length > 0) {
      await ctx.tools.web_verifyTextVisible({ text: expectedHeading });
    }
    await ctx.tools.web_verifyElementVisible({
      ref: SELECTORS.articleBody,
    });

    if (!requireReferences) {
      return "Article structure verified — heading and body present.";
    }

    // Quick pre-check before any scrolling — for short articles the References
    // heading may already be on screen.
    if (await elementIsVisible(ctx, REFERENCES_PROBE)) {
      return "Article structure verified — heading, body, and References section all present.";
    }

    // Scroll-then-check: each iteration scrolls down a viewport-and-change,
    // then probes for References. Scrolling first ensures the freshly-rendered
    // region gets inspected on the final iteration too — the previous shape
    // (verify, then scroll if not found, exit on iteration N) missed a section
    // that came into view on the last scroll.
    for (let step = 0; step < REFERENCES_MAX_SCROLLS; step += 1) {
      await ctx.tools.web_scroll({
        direction: "DOWN",
        amount: REFERENCES_SCROLL_PX,
      });
      if (await elementIsVisible(ctx, REFERENCES_PROBE)) {
        return "Article structure verified — heading, body, and References section all present.";
      }
    }

    throw new Error(
      "wikipedia_web_verifyArticleStructure: requireReferences=true but no 'References' heading found after scrolling.",
    );
  },
);
