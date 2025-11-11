package xyz.block.trailblaze.viewhierarchy

import kotlinx.serialization.Serializable
import xyz.block.trailblaze.api.ViewHierarchyTreeNode
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform

/**
 * ViewHierarchyFilter provides functionality to filter view hierarchy elements
 * to only those that are visible and interactable, reducing the size of data
 * sent to the LLM.
 */
abstract class ViewHierarchyFilter(
  protected val screenWidth: Int,
  protected val screenHeight: Int,
) {

  /**
   * Bounds represents the rectangular bounds of a UI element.
   */
  @Serializable
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
  abstract fun filterInteractableViewHierarchyTreeNodes(viewHierarchy: ViewHierarchyTreeNode): ViewHierarchyTreeNode

  /**
   * Find elements that are visible on screen using platform-specific logic.
   */
  protected abstract fun findVisibleViewHierarchyTreeNodes(
    elements: List<ViewHierarchyTreeNode>,
    screenBounds: Bounds,
  ): List<ViewHierarchyTreeNode>

  /**
   * Common implementation to create the filtered view hierarchy root node.
   */
  protected fun createFilteredRoot(
    viewHierarchy: ViewHierarchyTreeNode,
    visibleElements: List<ViewHierarchyTreeNode>,
  ): ViewHierarchyTreeNode {
    val interactableViewHierarchyTreeNodes: List<ViewHierarchyTreeNode> =
      findInteractableViewHierarchyTreeNodes(visibleElements)

    return ViewHierarchyTreeNode(
      children = interactableViewHierarchyTreeNodes,
      centerPoint = "${screenWidth / 2},${screenHeight / 2}",
      dimensions = "${screenWidth}x$screenHeight",
    )
  }

  /**
   * Check if two bounds rectangles overlap.
   */
  protected fun boundsOverlap(
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

  companion object {

    /**
     * Factory method to create platform-specific ViewHierarchyFilter instances.
     */
    fun create(
      screenWidth: Int,
      screenHeight: Int,
      platform: TrailblazeDevicePlatform,
    ): ViewHierarchyFilter = when (platform) {
      TrailblazeDevicePlatform.ANDROID -> AndroidViewHierarchyFilter(screenWidth, screenHeight)
      TrailblazeDevicePlatform.IOS -> IosViewHierarchyFilter(screenWidth, screenHeight)
      TrailblazeDevicePlatform.WEB -> WebViewHierarchyFilter(screenWidth, screenHeight)
    }

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
      // 3. ViewHierarchyTreeNodes with hint text
      // 4. ViewHierarchyTreeNodes with accessibility text
      // 5. Everything else
      interactable.sortWith(
        compareBy(
          { it -> it.text?.isNotEmpty() != true }, // Text elements first
          { it -> it.resourceId?.isNotEmpty() != true }, // Resource ID elements second
          { it -> it.hintText?.isNotEmpty() != true }, // Hint text elements third
          { it -> it.accessibilityText?.isNotEmpty() != true }, // Accessibility text elements fourth
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
        // Include elements with hint text
        if (elem.hintText?.isNotEmpty() == true) {
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
    protected fun boundsOverlap(
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
        hintText,
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
      val isZeroSizeView = this.bounds?.let {
        it.width == 0 || it.height == 0
      } ?: false
      return isOkResourceId && !isZeroSizeView
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
     * Collects all elements with meaningful content for iOS platform.
     * Includes elements with text, hint text, accessibility text, clickable elements, resource IDs, or focusable elements.
     */
    fun ViewHierarchyTreeNode.collectIOSElements(): List<ViewHierarchyTreeNode> = this.aggregate().filter { node ->
      node.enabled &&
        node.bounds != null &&
        (
          // Include nodes with text content
          !node.text.isNullOrBlank() ||
            // Include nodes with hint text
            !node.hintText.isNullOrBlank() ||
            // Include nodes with accessibility text
            !node.accessibilityText.isNullOrBlank() ||
            // Include clickable nodes (even if not many on iOS)
            node.clickable ||
            // Include nodes with resource IDs
            !node.resourceId.isNullOrBlank() ||
            // Include focusable elements
            node.focusable
          )
    }
  }
}
