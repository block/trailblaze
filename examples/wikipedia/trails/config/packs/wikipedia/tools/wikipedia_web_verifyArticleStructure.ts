import type { TrailblazeClient, TrailblazeContext } from "@trailblaze/scripting";
import {
  SELECTORS,
  elementIsVisible,
  requireSessionContext,
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

// Probe selector for "is the References heading visible?". Vector-2022 wraps
// the heading in `<h2 id="References">` (or `id="references"` on some
// articles) — `[id^=References]` matches both without overfitting.
const REFERENCES_PROBE = "h2#References, h2#references, [id^='References']";

export interface VerifyArticleStructureArgs {
  /** Visible text to assert in #firstHeading. */
  expectedHeading?: string;
  /** Scroll-to-bottom + assert References heading (default true). */
  requireReferences?: boolean;
}

/**
 * Verifies the currently loaded page conforms to Wikipedia's article shape.
 * Used after a navigation step to assert the user landed on an article-shaped
 * page (rather than a search index, talk page, or fallback). Checks are split
 * across branches so individual failures map back to specific structural
 * concerns:
 *
 *   1. First heading visible (#firstHeading) — every article has one.
 *   2. Body content wrapper visible (#mw-content-text) — confirms the
 *      article content actually rendered (not just the chrome).
 *   3. Optional References section — present on most full-length articles.
 *
 * The References scan uses the fast `elementIsVisible` probe (`web_evaluate`
 * with a synchronous querySelector check) rather than
 * `web_verify_text_visible` so each iteration of the scroll-and-check loop
 * costs ~milliseconds instead of the ~5 s default Playwright assertion
 * timeout. The loop scrolls *first* and verifies *second* so the final
 * scroll's freshly-rendered content gets checked before the throw.
 */
export async function wikipedia_web_verifyArticleStructure(
  args: VerifyArticleStructureArgs,
  ctx: TrailblazeContext | undefined,
  client: TrailblazeClient,
): Promise<string> {
  requireSessionContext(ctx);
  const expectedHeading = typeof args?.expectedHeading === "string"
    ? args.expectedHeading
    : "";
  const requireReferences = args?.requireReferences !== false;

  await client.tools.web_verify_element_visible({
    ref: SELECTORS.firstHeading,
  });
  if (expectedHeading.length > 0) {
    await client.tools.web_verify_text_visible({ text: expectedHeading });
  }
  await client.tools.web_verify_element_visible({
    ref: SELECTORS.articleBody,
  });

  if (!requireReferences) {
    return "Article structure verified — heading and body present.";
  }

  // Quick pre-check before any scrolling — for short articles the References
  // heading may already be on screen.
  if (await elementIsVisible(client, REFERENCES_PROBE)) {
    return "Article structure verified — heading, body, and References section all present.";
  }

  // Scroll-then-check: each iteration scrolls down a viewport-and-change,
  // then probes for References. Scrolling first ensures the freshly-rendered
  // region gets inspected on the final iteration too — the previous shape
  // (verify, then scroll if not found, exit on iteration N) missed a section
  // that came into view on the last scroll.
  for (let step = 0; step < REFERENCES_MAX_SCROLLS; step += 1) {
    await client.tools.web_scroll({
      direction: "DOWN",
      amount: REFERENCES_SCROLL_PX,
    });
    if (await elementIsVisible(client, REFERENCES_PROBE)) {
      return "Article structure verified — heading, body, and References section all present.";
    }
  }

  throw new Error(
    "wikipedia_web_verifyArticleStructure: requireReferences=true but no 'References' heading found after scrolling.",
  );
}
