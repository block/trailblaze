package xyz.block.trailblaze.playwright

import kotlinx.serialization.Serializable

/**
 * Detail types that can be requested for the next view hierarchy snapshot.
 *
 * When the LLM needs more information than the compact ARIA element list provides,
 * it can call [PlaywrightNativeRequestDetailsTool] with one or more of these detail
 * types. The next screen state capture will then enrich the view hierarchy text with
 * the requested information for ALL elements.
 *
 * This is a progressive disclosure pattern: the default compact list is token-efficient
 * for most turns, and the LLM can upgrade fidelity when it needs spatial or structural info.
 */
@Serializable
enum class ViewHierarchyDetail {
  /**
   * Include bounding box coordinates for each element.
   * Adds `{x,y,w,h}` after each element's ARIA descriptor.
   *
   * Example:
   * ```
   * [e1] button "Submit" {x:120,y:450,w:200,h:40}
   * [e2] link "Home" {x:50,y:10,w:80,h:24}
   * ```
   */
  BOUNDS,

  /**
   * Include CSS selectors for elements, especially those without good ARIA semantics.
   *
   * When requested, the view hierarchy is enriched in two ways:
   * 1. Existing ARIA elements get a `css=` annotation when they have a useful HTML `id`
   *    or `data-testid` attribute (providing an alternative, more stable selector).
   * 2. Elements that are normally invisible in the compact list (e.g., unnamed `<div>`s
   *    with click handlers) are surfaced if they have a targetable CSS selector
   *    (`id`, `data-testid`, or distinctive class).
   *
   * The LLM can then use the `css=` prefix in ref fields to target these elements:
   * ```
   * [e1] button "Submit" [css=#submit-btn]
   * [e2] generic [css=#interactive-panel]
   * [e3] generic [css=[data-testid="card-widget"]]
   * ```
   */
  CSS_SELECTORS,

  /**
   * Include all elements regardless of viewport position.
   *
   * By default, elements outside the current viewport are filtered out of the compact
   * element list to save tokens. When this detail type is requested, all elements are
   * included and offscreen ones are annotated with `(offscreen)`.
   */
  OFFSCREEN_ELEMENTS,

  /**
   * Include elements that are in the viewport but covered by another element
   * (modal, popup, toast, autocomplete dropdown, etc.) such that a click would
   * not reach them.
   *
   * Occlusion is decided by CLICK hit-testing, not visual paint order: the check
   * is a 1:1 port of Playwright's `expectHitTarget` — the same actionability rule
   * that produces `<el> intercepts pointer events` errors during `locator.click()`.
   * An element counts as occluded exactly when Playwright would refuse to click it.
   * See `BATCH_VIEWPORT_CHECK_JS` in [PlaywrightScreenState] for the ported algorithm.
   *
   * A consequence: a `pointer-events: none` overlay does NOT occlude what it covers,
   * because the click passes through to the element beneath. A visual paint-order
   * rule (`elementsFromPoint` with pointer-events forced on) was tried and reverted in
   * PR #2917 — it produced systematic false positives for every transparent
   * full-viewport wrapper (focus traps, drawer roots, route transitions, ambient
   * layers), hiding large parts of a perfectly interactive page from the LLM. Matching
   * Playwright's own decision is what keeps this signal trustworthy.
   *
   * By default occluded elements are filtered out of the compact element list. When
   * this detail is requested, they are included with `(occluded)` annotations so the
   * LLM can see what's behind the overlay and decide whether to dismiss it first.
   */
  OCCLUDED_ELEMENTS,
}
