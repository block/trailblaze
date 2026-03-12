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
  /** Include all nodes (even structural), bounds, dimensions, and enabled state. */
  FULL_HIERARCHY,
}
