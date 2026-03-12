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
}
