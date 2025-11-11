package xyz.block.trailblaze.viewhierarchy

import xyz.block.trailblaze.api.ViewHierarchyTreeNode

/**
 * Android-specific implementation of ViewHierarchyFilter.
 * Uses overlay-based filtering: detects FrameLayout overlays and filters elements behind them.
 */
class AndroidViewHierarchyFilter(
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
  ): List<ViewHierarchyTreeNode> {
    // First pass: find all overlays and sort them by z-index (top to bottom)
    val overlays = elements
      .filter { elem ->
        val bool = elem.isOverlay()
        bool
      }.sortedBy { it.bounds?.y1 }

    // Start with all elements that are in bounds
    var candidates = elements
      .filter { elem ->
        elem.bounds != null && screenBounds.contains(elem.bounds)
      }.toMutableList()

    // For each overlay, process elements
    for (i in overlays.indices) {
      val overlay = overlays[i]
      val remaining = mutableListOf<ViewHierarchyTreeNode>()

      for (elem in candidates) {
        // Skip processing if element is part of system UI
        if (elem.resourceId?.lowercase()?.contains("systemui") == true) {
          remaining.add(elem)
          continue
        }

        // Keep elements that are part of this overlay or any overlay above it
        var isOverlayViewHierarchyTreeNode = false
        for (aboveOverlay in overlays.subList(i, overlays.size)) {
          if (elem.resourceId == aboveOverlay.resourceId || aboveOverlay.resourceId == null) {
            isOverlayViewHierarchyTreeNode = true
            break
          }
        }

        if (isOverlayViewHierarchyTreeNode) {
          remaining.add(elem)
          continue
        }

        // If element is above all overlays, keep it
        if (elem.bounds != null &&
          overlays.subList(i, overlays.size).all { o ->
            elem.bounds.y2 == (o.bounds?.y1 ?: 0)
          }
        ) {
          remaining.add(elem)
          continue
        }

        // For sheet containers, be strict - if element overlaps, remove it
        if ((overlay.resourceId?.lowercase()?.contains("sheet_container") == true) &&
          !overlay.resourceId.lowercase().contains("root")
        ) {
          // Check if element is part of the overlay
          if (elem.resourceId == overlay.resourceId) {
            remaining.add(elem)
            continue
          }

          if (elem.bounds != null &&
            overlay.bounds != null &&
            boundsOverlap(
              elem.bounds.x1,
              elem.bounds.y1,
              elem.bounds.x2,
              elem.bounds.y2,
              overlay.bounds.x1,
              overlay.bounds.y1,
              overlay.bounds.x2,
              overlay.bounds.y2,
            )
          ) {
            continue
          }
        }

        // If we get here, keep the element
        remaining.add(elem)
      }

      candidates = remaining
    }

    return candidates
  }

  /**
   * Check if an element is an overlay like sheet, modal, drawer, etc.
   * Simplified to rely entirely on the FrameLayout class.
   */
  private fun ViewHierarchyTreeNode.isOverlay(): Boolean {
    val element = this
    // Skip system UI elements or root/content containers
    val isRootOrSystemOrContent = listOf("root", "system", "content").any { keyword ->
      element.resourceId?.lowercase()?.contains(keyword) ?: false
    }
    if (isRootOrSystemOrContent) {
      return false
    }
    // Consider FrameLayout as an overlay
    return element.className?.contains("FrameLayout") ?: false
  }
}
