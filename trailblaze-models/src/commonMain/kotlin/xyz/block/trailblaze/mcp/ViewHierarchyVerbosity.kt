package xyz.block.trailblaze.mcp

import kotlinx.serialization.Serializable

/**
 * Verbosity level for view hierarchy responses.
 */
@Serializable
enum class ViewHierarchyVerbosity {
  /**
   * Only interactable elements with coordinates.
   * Minimal token usage, best for most agent interactions.
   */
  MINIMAL,

  /**
   * Interactable elements with descriptions and hierarchy.
   * Good balance of information and token efficiency.
   */
  STANDARD,

  /**
   * Complete view hierarchy with all elements.
   * Use when debugging or need full UI structure.
   */
  FULL,
}
