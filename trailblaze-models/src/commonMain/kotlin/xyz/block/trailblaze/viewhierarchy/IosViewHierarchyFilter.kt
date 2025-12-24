package xyz.block.trailblaze.viewhierarchy

import xyz.block.trailblaze.api.ViewHierarchyTreeNode
import xyz.block.trailblaze.viewhierarchy.ViewHierarchyFilter.Companion.isInteractable

/**
 * iOS-specific implementation of ViewHierarchyFilter.
 * Uses z-order based filtering: an element is only visible if tapping its center point
 * would actually interact with that element (not something on top of it).
 *
 * Key filtering behaviors:
 * - Sibling elements with higher z-index block elements behind them
 * - Opaque overlays (large, disabled UIViews) block content when UITextEffectsWindow exists
 * - Elements in UITextEffectsWindow are accessibility mirrors and are never blocked
 * - System windows don't block main app content
 */
class IosViewHierarchyFilter(
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
    // Filter to elements that are within screen bounds
    val inBoundsElements = elements.filter { elem ->
      elem.bounds != null && screenBounds.contains(elem.bounds)
    }

    // Compute z-indices based on tree traversal order (later elements are drawn on top)
    val elementZIndices = inBoundsElements.mapIndexed { index, elem ->
      elem.nodeId to index
    }.toMap()

    // Build a map to find ancestors from the FULL element list (not just inBoundsElements)
    // This is critical because parent elements may not have dimensions/bounds set,
    // but we still need to trace the parent chain to identify system windows
    val childToParent = mutableMapOf<Long, ViewHierarchyTreeNode>()
    fun buildParentMap(node: ViewHierarchyTreeNode) {
      node.children.forEach { child ->
        childToParent[child.nodeId] = node
        buildParentMap(child)
      }
    }
    elements.forEach { buildParentMap(it) }

    // Helper function to check if elem1 is an ancestor of elem2
    fun isAncestor(
      potentialAncestor: ViewHierarchyTreeNode,
      potentialDescendant: ViewHierarchyTreeNode,
    ): Boolean {
      var current: ViewHierarchyTreeNode? = potentialDescendant
      while (current != null) {
        val parent = childToParent[current.nodeId]
        if (parent?.nodeId == potentialAncestor.nodeId) {
          return true
        }
        current = parent
      }
      return false
    }

    // Helper function to check if an element is a scroll bar
    fun isScrollBar(elem: ViewHierarchyTreeNode): Boolean =
      elem.accessibilityText?.contains("scroll bar", ignoreCase = true) == true

    // Helper function to check if elem1 is a descendant of elem2
    fun isDescendant(
      potentialDescendant: ViewHierarchyTreeNode,
      potentialAncestor: ViewHierarchyTreeNode,
    ): Boolean = isAncestor(potentialAncestor, potentialDescendant)

    // Helper function to check if an element is in a system window (keyboard, text effects, etc.)
    // These windows shouldn't block main app content
    fun isInSystemWindow(elem: ViewHierarchyTreeNode): Boolean {
      val systemWindowClasses = setOf(
        "UITextEffectsWindow",
        "UIRemoteKeyboardWindow",
        "UIEditingOverlayGestureView",
      )
      if (elem.className in systemWindowClasses) return true
      var current: ViewHierarchyTreeNode? = elem
      while (current != null) {
        if (current.className in systemWindowClasses) return true
        current = childToParent[current.nodeId]
      }
      return false
    }

    // Check if there's a UITextEffectsWindow in the hierarchy (provides accessibility mirrors)
    val hasAccessibilityWindow = inBoundsElements.any { it.className == "UITextEffectsWindow" }

    // Content container classes that indicate an element is part of scrollable/list content
    val contentContainerClasses = setOf(
      "CollectionView",
      "ListView",
      "TableView",
      "ScrollView",
      "UICollectionView",
      "UITableView",
      "UIScrollView",
      "ItemCell",
    )

    // Helper function to find the nearest content container ancestor of an element
    fun findNearestContentContainer(elem: ViewHierarchyTreeNode): ViewHierarchyTreeNode? {
      var current: ViewHierarchyTreeNode? = childToParent[elem.nodeId]
      while (current != null) {
        val parentClass = current.className ?: ""
        if (contentContainerClasses.any { parentClass.contains(it) }) {
          return current
        }
        current = childToParent[current.nodeId]
      }
      return null
    }

    // Helper function to check if an element is an opaque overlay
    // These are large, childless, disabled container views used as modal backgrounds
    // Only consider them if there's an accessibility window to provide mirrors for blocked elements
    fun isOpaqueOverlay(elem: ViewHierarchyTreeNode): Boolean {
      if (!hasAccessibilityWindow) return false
      if (elem.children.isNotEmpty()) return false
      if (elem.enabled != false) return false
      val className = elem.className ?: return false
      if (className != "UIView") return false
      val bounds = elem.bounds ?: return false
      val minWidth = screenWidth * 0.8
      val minHeight = screenHeight * 0.5
      return bounds.width >= minWidth && bounds.height >= minHeight
    }

    // Helper function to get the ancestor chain of an element (for finding common ancestors)
    fun getAncestorChain(elem: ViewHierarchyTreeNode): List<ViewHierarchyTreeNode> {
      val ancestors = mutableListOf<ViewHierarchyTreeNode>()
      var current: ViewHierarchyTreeNode? = childToParent[elem.nodeId]
      while (current != null) {
        ancestors.add(current)
        current = childToParent[current.nodeId]
      }
      return ancestors
    }

    // Check if an opaque overlay should actually block a specific element
    // An overlay doesn't block an element if:
    // 1. They share the same content container ancestor (siblings in same scrollable list/grid)
    // 2. The overlay and target share a common ancestor that is a content container
    //    (meaning they're in the same scrollable area, not a modal overlay pattern)
    fun shouldOpaqueOverlayBlock(overlay: ViewHierarchyTreeNode, target: ViewHierarchyTreeNode): Boolean {
      val overlayContainer = findNearestContentContainer(overlay)
      val targetContainer = findNearestContentContainer(target)

      // If both are in the same content container, don't block (siblings in same scrollable area)
      if (overlayContainer != null && targetContainer != null &&
        overlayContainer.nodeId == targetContainer.nodeId
      ) {
        return false
      }

      // Check if they share a common ancestor that is a content container
      // This handles the case where the overlay is nested in a content area alongside the target
      val overlayAncestors = getAncestorChain(overlay).map { it.nodeId }.toSet()
      val targetAncestors = getAncestorChain(target)

      for (ancestor in targetAncestors) {
        if (overlayAncestors.contains(ancestor.nodeId)) {
          // Found common ancestor - check if it's a content container
          val ancestorClass = ancestor.className ?: ""
          if (contentContainerClasses.any { ancestorClass.contains(it) }) {
            // They share a content container ancestor - overlay shouldn't block
            return false
          }
        }
      }

      return true
    }

    val passed = mutableListOf<ViewHierarchyTreeNode>()

    // For each element, check if tapping its center point would actually hit it
    for (elem in inBoundsElements) {
      val bounds = elem.bounds ?: continue
      val centerX = bounds.centerX
      val centerY = bounds.centerY
      val elemZIndex = elementZIndices[elem.nodeId] ?: 0

      // Find all elements that contain this center point
      val elementsAtCenterPoint = inBoundsElements.filter { other ->
        val otherBounds = other.bounds ?: return@filter false
        centerX >= otherBounds.x1 &&
          centerX <= otherBounds.x2 &&
          centerY >= otherBounds.y1 &&
          centerY <= otherBounds.y2
      }

      // Filter out ancestors and scroll bars - they shouldn't block their children
      val nonAncestorElementsAtCenter = elementsAtCenterPoint.filter { other ->
        !isAncestor(other, elem) && !isScrollBar(other)
      }

      // Check if there's a sibling element with higher z-index that would block this element
      // Elements in system windows (UITextEffectsWindow) are accessibility mirrors
      // and should always be visible - they can't be blocked by overlays
      val elemInSystemWindow = isInSystemWindow(elem)

      // Helper to get depth of an element in the tree
      fun getTreeDepth(node: ViewHierarchyTreeNode): Int {
        var depth = 0
        var current: ViewHierarchyTreeNode? = node
        while (current != null) {
          depth++
          current = childToParent[current.nodeId]
        }
        return depth
      }

      // An opaque overlay should only block elements that are at a similar or shallower depth
      // If the target is much deeper than the overlay, the overlay is probably just a
      // background view at a higher level, not a modal that should block nested content
      val maxDepthDifferenceForBlocking = 35

      val blockingSibling = nonAncestorElementsAtCenter.find { other ->
        val otherZIndex = elementZIndices[other.nodeId] ?: 0
        val elemDepth = getTreeDepth(elem)
        val overlayDepth = getTreeDepth(other)
        val isBlockingOverlay = isOpaqueOverlay(other) &&
          (elemDepth - overlayDepth) <= maxDepthDifferenceForBlocking &&
          shouldOpaqueOverlayBlock(other, elem)
        otherZIndex > elemZIndex &&
          !isDescendant(other, elem) &&
          (other.isInteractable() || (!elemInSystemWindow && isBlockingOverlay)) &&
          !isInSystemWindow(other)
      }

      if (blockingSibling != null) {
        continue
      }

      // Determine candidates for tap target selection (only descendants, siblings handled above)
      val tapCandidates = nonAncestorElementsAtCenter.filter { other ->
        other.nodeId == elem.nodeId || isDescendant(other, elem)
      }

      // The element that would receive the tap is the smallest interactable one
      val topElement = tapCandidates
        .filter { it.isInteractable() }
        .sortedWith(
          compareBy<ViewHierarchyTreeNode> {
            it.bounds?.let { b -> b.width * b.height } ?: Int.MAX_VALUE
          }
            .then(compareByDescending { elementZIndices[it.nodeId] ?: 0 }),
        )
        .firstOrNull()

      // Element is visible if it would be the one to receive the tap
      if (topElement == null || topElement.nodeId == elem.nodeId) {
        passed.add(elem)
      }
    }

    return passed
  }
}
