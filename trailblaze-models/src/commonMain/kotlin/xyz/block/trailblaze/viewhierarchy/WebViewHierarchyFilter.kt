package xyz.block.trailblaze.viewhierarchy

import xyz.block.trailblaze.api.ViewHierarchyTreeNode

/**
 * Web-specific implementation of ViewHierarchyFilter.
 * Simply returns all elements, no filtering.
 */
class WebViewHierarchyFilter(
  screenWidth: Int,
  screenHeight: Int,
) : ViewHierarchyFilter(screenWidth, screenHeight) {

  override fun filterInteractableViewHierarchyTreeNodes(viewHierarchy: ViewHierarchyTreeNode): ViewHierarchyTreeNode {
    val rootBounds =
      viewHierarchy.bounds?.takeIf { it.width > 0 && it.height > 0 } ?: Bounds(
        x1 = 0,
        y1 = 0,
        x2 = screenWidth,
        y2 = screenHeight,
      )

    val visibleViewHierarchyTreeNodes: List<ViewHierarchyTreeNode> =
      findVisibleViewHierarchyTreeNodes(
        viewHierarchy.aggregate(),
        rootBounds,
      )

    return createFilteredRoot(viewHierarchy, visibleViewHierarchyTreeNodes)
  }

  override fun findVisibleViewHierarchyTreeNodes(
    elements: List<ViewHierarchyTreeNode>,
    screenBounds: Bounds,
  ): List<ViewHierarchyTreeNode> = elements
}
