package xyz.block.trailblaze.viewhierarchy

import xyz.block.trailblaze.api.ViewHierarchyTreeNode

/**
 * ViewHierarchyFilter provides functionality to filter view hierarchy elements
 * to only those that are visible and interactable, reducing the size of data
 * sent to the LLM.
 */
class ViewHierarchyFilter(
  private val screenWidth: Int,
  private val screenHeight: Int,
) {

  /**
   * Bounds represents the rectangular bounds of a UI element.
   */
  data class Bounds(
    val x1: Int,
    val y1: Int,
    val x2: Int,
    val y2: Int,
  ) {
    val width: Int = x2 - x1
    val height: Int = y2 - y1

    val centerX: Int = x1 + width / 2
    val centerY: Int = y1 + height / 2

    /**
     * Check if this bounds fully contains another bounds.
     */
    fun contains(other: Bounds): Boolean = (
      x1 <= other.x1 &&
        y1 <= other.y1 &&
        x2 >= other.x2 &&
        y2 >= other.y2
      )
  }

  /**
   * Filter the view hierarchy to only include interactable elements.
   *
   * @param viewHierarchy The original view hierarchy
   * @return Filtered view hierarchy with only interactable elements
   */
  fun filterInteractableViewHierarchyTreeNodes(viewHierarchy: ViewHierarchyTreeNode): ViewHierarchyTreeNode {
    // Extract screen bounds from the root node or use provided dimensions
    val rootBounds = viewHierarchy.bounds ?: Bounds(
      x1 = 0,
      y1 = 0,
      x2 = screenWidth,
      y2 = screenHeight,
    )

    // Find visible elements
    val visibleViewHierarchyTreeNodes: List<ViewHierarchyTreeNode> =
      findVisibleViewHierarchyTreeNodes(viewHierarchy.aggregate(), rootBounds)

    // Find interactable elements among visible ones
    val interactableViewHierarchyTreeNodes: List<ViewHierarchyTreeNode> =
      findInteractableViewHierarchyTreeNodes(visibleViewHierarchyTreeNodes)

    return ViewHierarchyTreeNode(
      children = interactableViewHierarchyTreeNodes,
      centerPoint = "${screenWidth / 2},${screenHeight / 2}",
      dimensions = "${screenWidth}x$screenHeight",
    )
  }

  companion object {

    /**
     * Find elements that can be interacted with.
     */
    private fun findInteractableViewHierarchyTreeNodes(elements: List<ViewHierarchyTreeNode>): List<ViewHierarchyTreeNode> {
      val interactable = elements.filter { elem ->
        elem.isInteractable()
      }.toMutableList()

      // Process all elements that could be interactive

      // Sort interactable elements by priority:
      // 1. ViewHierarchyTreeNodes with text
      // 2. ViewHierarchyTreeNodes with resource ID
      // 3. ViewHierarchyTreeNodes with accessibility text
      // 4. Everything else
      interactable.sortWith(
        compareBy(
          { it -> it.text?.isNotEmpty() != true }, // Text elements first
          { it -> it.resourceId?.isNotEmpty() != true }, // Resource ID elements second
          { it -> it.accessibilityText?.isNotEmpty() != true }, // Accessibility text elements third
          { true }, // Everything else last
        ),
      )

      return interactable
    }

    /**
     * Check if a view hierarchy element is interactable.
     */
    fun ViewHierarchyTreeNode.isInteractable(): Boolean {
      val elem = this
      // Skip disabled elements
      if (elem.enabled) {
        // Include elements that are marked interactive
        if (elem.clickable || elem.selected || elem.focusable || elem.scrollable) {
          return true
        }
        // Include elements with any text content
        if (elem.text?.isNotEmpty() == true) {
          return true
        }
        // Include elements with accessibility text
        if (elem.accessibilityText?.isNotEmpty() == true) {
          return true
        }
      }

      return false
    }

    /**
     * Check if two bounds rectangles overlap.
     */
    private fun boundsOverlap(
      left1: Int,
      top1: Int,
      right1: Int,
      bottom1: Int,
      left2: Int,
      top2: Int,
      right2: Int,
      bottom2: Int,
    ): Boolean {
      // No overlap if one rectangle is to the left of the other
      if (right1 <= left2 || right2 <= left1) return false

      // No overlap if one rectangle is above the other
      if (bottom1 <= top2 || bottom2 <= top1) return false

      return true
    }

    /**
     * Find elements that are visible on screen.
     */
    private fun findVisibleViewHierarchyTreeNodes(
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
            if (elem.resourceId == aboveOverlay.resourceId ||
              elem.containerId == aboveOverlay.resourceId ||
              (
                elem.containerId != null &&
                  aboveOverlay.resourceId != null &&
                  aboveOverlay.resourceId.isNotEmpty() &&
                  elem.containerId!!.contains(aboveOverlay.resourceId)
                )
            ) {
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
            if (elem.containerId == overlay.resourceId ||
              elem.resourceId == overlay.resourceId
            ) {
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
     * Compute the percentage of the bounds that is visible on the screen.
     */
    fun Bounds.getVisiblePercentage(screenWidth: Int, screenHeight: Int): Double {
      val bounds = this
      if (bounds.width == 0 && bounds.height == 0) {
        return 0.0
      }

      val overflow =
        (bounds.x1 <= 0) && (bounds.y1 <= 0) && (bounds.x1 + bounds.width >= screenWidth) && (bounds.y1 + bounds.height >= screenHeight)
      if (overflow) {
        return 1.0
      }

      val visibleX = maxOf(0, minOf(bounds.x1 + bounds.width, screenWidth) - maxOf(bounds.x1, 0))
      val visibleY = maxOf(0, minOf(bounds.y1 + bounds.height, screenHeight) - maxOf(bounds.y1, 0))
      val visibleArea = visibleX * visibleY
      val totalArea = bounds.width * bounds.height

      return visibleArea.toDouble() / totalArea.toDouble()
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

    /**
     * Filter out elements that are outside the bounds of the screen.
     *
     * If ignoreBoundsFiltering is true, return the node as is.
     */
    fun ViewHierarchyTreeNode.filterOutOfBounds(width: Int, height: Int): ViewHierarchyTreeNode? {
      if (ignoreBoundsFiltering) {
        return this
      }

      val filtered = children.mapNotNull {
        it.filterOutOfBounds(width, height)
      }.toList()

      val visiblePercentage = this.bounds?.getVisiblePercentage(width, height) ?: 0.0

      return if (visiblePercentage < 0.1 && filtered.isEmpty()) {
        null
      } else {
        this
      }
    }

    data class OptimizationResult(
      val node: ViewHierarchyTreeNode?,
      val promotedChildren: List<ViewHierarchyTreeNode>,
    )

    private fun ViewHierarchyTreeNode.hasMeaningfulAttributes(): Boolean = with(this) {
      listOf(
        text,
        accessibilityText,
      ).any {
        it?.isNotBlank() == true
      }
    }

    /**
     * Check if a node should be included in the optimization process.
     * Exclude nodes that are part of the status bar or have zero size.
     */
    private fun ViewHierarchyTreeNode.shouldBeIncluded(): Boolean {
      val isOkResourceId = run {
        val resourceId = this.resourceId ?: return@run true
        val hasNotNeededId =
          resourceId.contains("status_bar_container") || resourceId.contains("status_bar_launch_animation_container")
        !hasNotNeededId
      }
      val isVisibleRectView = this.bounds?.let {
        it.width == 0 && it.height == 0
      } ?: false
      return isOkResourceId && isVisibleRectView
    }

    /**
     * Check if a node has meaningful attributes or if any of its children do.
     * This is a depth-first search to determine if the node should be included
     * in the optimization process.
     */
    private fun isMeaningfulViewDfs(node: ViewHierarchyTreeNode): Boolean {
      if (node.hasMeaningfulAttributes() || node.clickable) {
        return true
      }
      return node.children.any { isMeaningfulViewDfs(it) }
    }

    /**
     * Optimize the view hierarchy tree by removing nodes that do not have meaningful attributes
     * and promoting their children up the tree.
     *
     * This is a depth-first search optimization that reduces the tree size while preserving
     * meaningful content.
     */
    private fun ViewHierarchyTreeNode.optimizeTree(
      isRoot: Boolean = false,
      viewHierarchy: ViewHierarchyTreeNode,
    ): OptimizationResult {
      val childResults = children
        .filter { it.shouldBeIncluded() && isMeaningfulViewDfs(it) }
        .map { it.optimizeTree(false, viewHierarchy) }
      val optimizedChildren: List<ViewHierarchyTreeNode> = childResults.flatMap {
        it.node?.let { node -> listOf(node) } ?: it.promotedChildren
      }
      if (isRoot) {
        return OptimizationResult(
          node = this.copy(children = optimizedChildren),
          promotedChildren = emptyList(),
        )
      }
      val hasContentInThisNode = this.hasMeaningfulAttributes()
      if (hasContentInThisNode) {
        return OptimizationResult(
          node = this.copy(children = optimizedChildren),
          promotedChildren = emptyList(),
        )
      }
      if (optimizedChildren.isEmpty()) {
        return OptimizationResult(
          node = null,
          promotedChildren = emptyList(),
        )
      }
      val isSingleChild = optimizedChildren.size == 1
      return if (isSingleChild) {
        OptimizationResult(
          node = optimizedChildren.single(),
          promotedChildren = emptyList(),
        )
      } else {
        OptimizationResult(
          node = null,
          promotedChildren = optimizedChildren,
        )
      }
    }

    /**
     * Collect all clickable and enabled elements in the view hierarchy.
     * This is a flat extraction, not an optimization.
     */
    fun ViewHierarchyTreeNode.collectAllClickableAndEnabledElements(): List<ViewHierarchyTreeNode> {
      val result = mutableListOf<ViewHierarchyTreeNode>()
      if (this.clickable && this.enabled) {
        result.add(this)
      }
      for (child in children) {
        result.addAll(child.collectAllClickableAndEnabledElements())
      }
      return result
    }

    /**
     * Recursively collect all optimized nodes (including all promoted children at every level)
     */
    private fun collectAllOptimizedNodes(
      result: OptimizationResult,
      viewHierarchy: ViewHierarchyTreeNode,
    ): List<ViewHierarchyTreeNode> = result.node?.let { listOf(it) }
      ?: result.promotedChildren.flatMap { child ->
        collectAllOptimizedNodes(child.optimizeTree(false, viewHierarchy), viewHierarchy)
      }
  }
}
