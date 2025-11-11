package xyz.block.trailblaze.viewhierarchy

import xyz.block.trailblaze.api.ViewHierarchyTreeNode
import xyz.block.trailblaze.viewhierarchy.ViewHierarchyFilter.Companion.isInteractable

/**
 * iOS-specific implementation of ViewHierarchyFilter.
 * Uses z-order based filtering: an element is only visible if tapping its center point
 * would actually interact with that element (not something on top of it).
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
    fun isScrollBar(elem: ViewHierarchyTreeNode): Boolean = elem.accessibilityText?.contains("scroll bar", ignoreCase = true) == true

    // For each element, check if tapping its center point would actually hit it
    return inBoundsElements.filter { elem ->
      val bounds = elem.bounds ?: return@filter false
      val centerX = bounds.centerX
      val centerY = bounds.centerY

      // Find all elements that contain this center point
      val elementsAtCenterPoint = inBoundsElements.filter { other ->
        val otherBounds = other.bounds ?: return@filter false
        centerX >= otherBounds.x1 &&
          centerX <= otherBounds.x2 &&
          centerY >= otherBounds.y1 &&
          centerY <= otherBounds.y2
      }

      // Filter out ancestors - parents shouldn't block their children
      // Also filter out scroll bars - they shouldn't block other elements
      val nonAncestorElementsAtCenter = elementsAtCenterPoint.filter { other ->
        !isAncestor(other, elem) && !isScrollBar(other)
      }

      // The element that would receive the tap is the one with:
      // 1. Is interactable (not just a container)
      // 2. Smallest size (most specific, not a container)
      // 3. Highest z-index (latest in tree traversal)
      val topElement = nonAncestorElementsAtCenter
        .filter { it.isInteractable() } // Only consider interactable elements
        .sortedWith(
          compareBy<ViewHierarchyTreeNode> {
            it.bounds?.let { b -> b.width * b.height } ?: Int.MAX_VALUE
          }
            .then(compareByDescending { elementZIndices[it.nodeId] ?: 0 }),
        )
        .firstOrNull()

      // Element is visible if it would be the one to receive the tap
      // If no interactable element at this point, keep this element (it might become interactable later)
      topElement == null || topElement.nodeId == elem.nodeId
    }
  }
}
