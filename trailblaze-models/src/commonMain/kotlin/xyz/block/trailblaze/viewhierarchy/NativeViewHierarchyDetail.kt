package xyz.block.trailblaze.viewhierarchy

import kotlinx.serialization.Serializable

/**
 * Detail levels that the LLM can request for native mobile view hierarchies
 * via the `request_view_hierarchy_details` tool.
 *
 * Kept separate from Playwright's `ViewHierarchyDetail` because native platforms
 * have different enrichment semantics (no CSS selectors, different structural rules).
 */
@Serializable
enum class NativeViewHierarchyDetail {
  /**
   * Include all nodes (even structural/decorative), bounds, dimensions, and enabled state.
   *
   * On the UiAutomator driver, this surfaces all structural containers alongside interactable
   * elements. On the accessibility driver, forwarding this detail to disable the
   * `importantForAccessibility` filter is not yet wired up — use it today for bounds and
   * dimensions on the UiAutomator path.
   */
  FULL_HIERARCHY,

  /**
   * Include all elements regardless of screen position.
   *
   * By default, elements outside the visible screen area are filtered out of the compact
   * element list to save tokens. When this detail type is requested, all elements are
   * included and offscreen ones are annotated with `(offscreen)`.
   */
  OFFSCREEN_ELEMENTS,
}
