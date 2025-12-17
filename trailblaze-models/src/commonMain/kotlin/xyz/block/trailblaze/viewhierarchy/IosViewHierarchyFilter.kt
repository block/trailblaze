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

    // Build a map to find ancestors
    val childToParent = mutableMapOf<Long, ViewHierarchyTreeNode>()
    fun buildParentMap(node: ViewHierarchyTreeNode) {
      node.children.forEach { child ->
        childToParent[child.nodeId] = node
        buildParentMap(child)
      }
    }
    inBoundsElements.forEach { buildParentMap(it) }

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

      val blockingSibling = nonAncestorElementsAtCenter.find { other ->
        val otherZIndex = elementZIndices[other.nodeId] ?: 0
        otherZIndex > elemZIndex &&
          !isDescendant(other, elem) &&
          (other.isInteractable() || (!elemInSystemWindow && isOpaqueOverlay(other))) &&
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
