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
   * Include elements that are in the viewport but visually covered (painted under)
   * another element (modal, popup, toast, autocomplete dropdown, etc.).
   *
   * Uses VISUAL paint order (`document.elementsFromPoint`), not click hit-testing
   * (`document.elementFromPoint`). The two differ for `pointer-events: none`
   * overlays — common for non-modal toasts that visually float on top while leaving
   * the page beneath interactive. The visual signal is the right one for SoM
   * because the LLM reasons from the screenshot: if it can't see an element, it
   * shouldn't be told the element is actionable in the prompt.
   *
   * By default these are filtered out of the compact element list. When this
   * detail is requested, occluded elements are included with `(occluded)`
   * annotations so the LLM can see what's hidden under the overlay and decide
   * whether to dismiss it first.
   */
  OCCLUDED_ELEMENTS,
}
