package xyz.block.trailblaze.mcp.newtools

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import io.ktor.util.encodeBase64
import xyz.block.trailblaze.api.ViewHierarchyTreeNode
import xyz.block.trailblaze.mcp.TrailblazeMcpBridge
import xyz.block.trailblaze.viewhierarchy.ViewHierarchyFilter
import xyz.block.trailblaze.viewhierarchy.ViewHierarchyFilter.Companion.asTrailblazeElementSelector
import xyz.block.trailblaze.viewhierarchy.ViewHierarchyFilter.Companion.isInteractable

/**
 * Debugging tools that are disabled from the main MCP server intentionally.
 *
 * These tools have been moved here to keep them available for potential future debugging workflows,
 * but they are not currently wired into the MCP server. The MCP is focused on test author/executor
 * workflows rather than agent device control, so these debugging capabilities are preserved but
 * not actively exposed.
 */
@Suppress("unused")
class DebuggingToolSet(
  private val mcpBridge: TrailblazeMcpBridge,
) : ToolSet {

  @LLMDescription(
    """Get Current Screen State (View Hierarchy) of the Device"""
  )
  @Tool
  suspend fun viewHierarchy(): String {
    val screenState = mcpBridge.getCurrentScreenState()
    return screenState?.let { screenState ->
      val vhFilter = ViewHierarchyFilter.create(
        screenWidth = screenState.deviceWidth,
        screenHeight = screenState.deviceHeight,
        platform = screenState.trailblazeDevicePlatform
      )
      val filtered = vhFilter.filterInteractableViewHierarchyTreeNodes(screenState.viewHierarchyOriginal)
      buildViewHierarchyDescription(filtered)
    } ?: "No screen state available."
  }

  /**
   * Walks the view hierarchy tree and builds a human-readable description of each element.
   * Filters out elements with null descriptions but preserves their children.
   */
  private fun buildViewHierarchyDescription(node: ViewHierarchyTreeNode, depth: Int = 0): String {
    val indent = "  ".repeat(depth)
    val selectorDescription = node.asTrailblazeElementSelector()?.description()
    val centerPoint = node.centerPoint

    // Build this node's description line (only if selector description exists)
    val thisNodeLine = if (selectorDescription != null) {
      val positionSuffix = centerPoint?.let { " @$it" } ?: ""
      "$indent$selectorDescription$positionSuffix"
    } else {
      null
    }

    // Recursively build child descriptions
    // If this node has no description, children stay at the same depth (don't increase indent)
    val childDepth = if (selectorDescription != null) depth + 1 else depth
    val childDescriptions = node.children
      .map { child -> buildViewHierarchyDescription(child, childDepth) }
      .filter { it.isNotBlank() }

    return listOfNotNull(thisNodeLine)
      .plus(childDescriptions)
      .joinToString("\n")
  }

  /**
   * Collapses the view hierarchy to only keep interactable elements.
   * Non-interactable nodes are removed, but their interactable descendants are promoted up.
   *
   * @param node The root node to collapse
   * @return A new tree with only interactable elements preserved
   */
  private fun collapseViewHierarchy(node: ViewHierarchyTreeNode): ViewHierarchyTreeNode {
    val collapsedChildren = node.children.flatMap { child ->
      collapseChildHierarchy(child)
    }
    return node.copy(children = collapsedChildren)
  }

  /**
   * Helper function that collapses a child subtree.
   * If the node is interactable, it's kept with its own collapsed children.
   * If not interactable, the node is removed and its interactable descendants are promoted up.
   */
  private fun collapseChildHierarchy(node: ViewHierarchyTreeNode): List<ViewHierarchyTreeNode> {
    val collapsedDescendants = node.children.flatMap { child ->
      collapseChildHierarchy(child)
    }

    return if (node.isInteractable()) {
      // Keep this node with its collapsed descendants
      listOf(node.copy(children = collapsedDescendants))
    } else {
      // Node is not interactable, promote its interactable descendants up
      collapsedDescendants
    }
  }

  @LLMDescription(
    """Get Current Screen State (Base64 Encoded ByteArray)"""
  )
  @Tool
  suspend fun screenshotBytes(): String {
    val screenState = mcpBridge.getCurrentScreenState()
    return screenState?.screenshotBytes?.encodeBase64() ?: "No screen state available."
  }
}
