package xyz.block.trailblaze.viewhierarchy

import xyz.block.trailblaze.api.ViewHierarchyTreeNode

/**
 * Web-specific implementation of ViewHierarchyFilter.
 *
 * Uses DOM order-based filtering for modal/dialog occlusion:
 * - Elements with higher nodeId values appear later in DOM tree and render on top
 * - Modal overlays are detected by finding elements covering >50% of screen area
 * - Elements behind modal overlays (lower nodeId, overlapping bounds) are filtered out
 */
class WebViewHierarchyFilter(
  screenWidth: Int,
  screenHeight: Int,
) : ViewHierarchyFilter(screenWidth, screenHeight) {

  companion object {
    /** Minimum screen coverage ratio to be considered a modal overlay */
    private const val MODAL_OVERLAY_THRESHOLD = 0.5
  }

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
  ): List<ViewHierarchyTreeNode> {
    // Filter to elements that are within screen bounds
    val inBoundsElements = elements.filter { elem ->
      elem.bounds != null && screenBounds.contains(elem.bounds)
    }

    // Detect modal overlays - large elements covering significant screen area
    val overlays = inBoundsElements.filter { isModalOverlay(it, screenBounds) }

    // If no overlays exist, return all in-bounds elements
    if (overlays.isEmpty()) {
      return inBoundsElements
    }

    // Find the topmost overlay (highest nodeId = latest in DOM = on top)
    val topOverlay = overlays.maxByOrNull { it.nodeId } ?: return inBoundsElements
    val topOverlayBounds = topOverlay.bounds ?: return inBoundsElements

    // Filter elements: keep those that are either:
    // 1. Part of the overlay or rendered after it (nodeId >= overlay's nodeId)
    // 2. Not spatially behind the overlay (bounds don't overlap)
    return inBoundsElements.filter { element ->
      val elementBounds = element.bounds ?: return@filter true

      // Element is part of or after the overlay in DOM order
      if (element.nodeId >= topOverlay.nodeId) {
        return@filter true
      }

      // Element doesn't overlap with the overlay (not occluded)
      !boundsOverlap(
        elementBounds.x1,
        elementBounds.y1,
        elementBounds.x2,
        elementBounds.y2,
        topOverlayBounds.x1,
        topOverlayBounds.y1,
        topOverlayBounds.x2,
        topOverlayBounds.y2,
      )
    }
  }

  /**
   * Determines if an element is likely a modal overlay (backdrop/dialog container).
   * A modal overlay is a large element covering significant screen area.
   */
  private fun isModalOverlay(element: ViewHierarchyTreeNode, screenBounds: Bounds): Boolean {
    val bounds = element.bounds ?: return false

    // Calculate area coverage
    val elementArea = bounds.width.toLong() * bounds.height.toLong()
    val screenArea = screenBounds.width.toLong() * screenBounds.height.toLong()

    if (screenArea == 0L) return false

    val coverage = elementArea.toDouble() / screenArea.toDouble()

    // Element must cover more than threshold of screen to be considered an overlay
    if (coverage <= MODAL_OVERLAY_THRESHOLD) {
      return false
    }

    // Additional heuristics for modal detection:
    // - Modal overlays typically don't have text content (they're backdrop/container elements)
    // - They're usually not directly clickable themselves (the content inside is)
    val isBackdropLike = element.text.isNullOrEmpty() &&
      element.resourceId?.lowercase()?.let { id ->
        id.contains("overlay") ||
          id.contains("backdrop") ||
          id.contains("modal") ||
          id.contains("dialog") ||
          id.contains("sheet")
      } == true

    // If it matches backdrop-like naming, it's definitely an overlay
    if (isBackdropLike) {
      return true
    }

    // For elements covering most of the screen (>80%), consider them overlays
    // even without explicit naming
    return coverage > 0.8
  }
}
