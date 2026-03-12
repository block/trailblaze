package xyz.block.trailblaze.recording

import xyz.block.trailblaze.api.ViewHierarchyTreeNode
import xyz.block.trailblaze.viewhierarchy.ViewHierarchyFilter

/**
 * Pure function utility: given a point (x, y) and a view hierarchy tree,
 * return the deepest node whose bounds contain that point. Prefers clickable/interactive
 * nodes when multiple leaf nodes overlap.
 */
object ViewHierarchyHitTester {

  /**
   * Walk the view hierarchy depth-first. Return the deepest node whose bounds contain (x, y).
   * When multiple nodes overlap at the same depth, prefer clickable/interactive ones.
   */
  fun hitTest(root: ViewHierarchyTreeNode, x: Int, y: Int): ViewHierarchyTreeNode? {
    return hitTestRecursive(root, x, y)
  }

  private fun hitTestRecursive(
    node: ViewHierarchyTreeNode,
    x: Int,
    y: Int,
  ): ViewHierarchyTreeNode? {
    val bounds = node.bounds
    // If this node has bounds and doesn't contain the point, skip it and its children
    if (bounds != null && !containsPoint(bounds, x, y)) {
      return null
    }

    // Try children depth-first (last child drawn on top, so check in reverse)
    var bestChild: ViewHierarchyTreeNode? = null
    for (child in node.children.reversed()) {
      val hit = hitTestRecursive(child, x, y)
      if (hit != null) {
        // Prefer clickable/interactive nodes
        if (bestChild == null || isMoreInteractive(hit, bestChild)) {
          bestChild = hit
        }
        // If we found a clickable node, no need to check more siblings
        if (hit.clickable) break
      }
    }

    // If we found a matching child, return it (deeper = more specific)
    if (bestChild != null) return bestChild

    // Otherwise, if this node contains the point, return it
    if (bounds != null && containsPoint(bounds, x, y)) {
      return node
    }

    // Root node without bounds — only returned if nothing else matched
    return if (bounds == null && node.children.isEmpty()) node else null
  }

  private fun containsPoint(bounds: ViewHierarchyFilter.Bounds, x: Int, y: Int): Boolean =
    x in bounds.x1..bounds.x2 && y in bounds.y1..bounds.y2

  private fun isMoreInteractive(
    candidate: ViewHierarchyTreeNode,
    current: ViewHierarchyTreeNode,
  ): Boolean {
    if (candidate.clickable && !current.clickable) return true
    if (!candidate.clickable && current.clickable) return false
    if (candidate.focusable && !current.focusable) return true
    return false
  }

  /**
   * Resolve a hit-test result to a semantic identifier.
   * Priority:
   *   1. accessibilityText
   *   2. text
   *   3. hintText
   *   4. resourceId
   *   5. null (use coordinates as fallback)
   */
  fun resolveSemanticText(node: ViewHierarchyTreeNode): String? {
    return node.accessibilityText?.takeIf { it.isNotBlank() }
      ?: node.text?.takeIf { it.isNotBlank() }
      ?: node.hintText?.takeIf { it.isNotBlank() }
      ?: node.resourceId?.takeIf { it.isNotBlank() }
  }

  /** Returns true if the node looks like a text input field. */
  fun isInputField(node: ViewHierarchyTreeNode): Boolean {
    val className = node.className?.lowercase() ?: ""
    return node.focusable && (
      className.contains("edittext") ||
        className.contains("textinput") ||
        className.contains("textfield") ||
        className.contains("textbox") ||
        className.contains("input") ||
        className.contains("textarea") ||
        node.hintText != null
      )
  }
}
