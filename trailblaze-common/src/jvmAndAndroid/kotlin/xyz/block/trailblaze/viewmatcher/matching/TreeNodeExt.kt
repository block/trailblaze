package xyz.block.trailblaze.viewmatcher.matching

import maestro.TreeNode
import xyz.block.trailblaze.api.ViewHierarchyTreeNode

/**
 * Transforms our [ViewHierarchyTreeNode] back into a Maestro [TreeNode]
 *
 * This is based on looking at Maestro's internal parsing implementation.
 *
 * It's fragile, but allows us to do matching with their implementation to ensure uniqueness.
 */
fun ViewHierarchyTreeNode.asTreeNode(): TreeNode {
  val attributes = mutableMapOf<String, String>()

  // Add basic attributes
  text?.let { attributes["text"] = it }
  resourceId?.let { attributes["resource-id"] = it }
  accessibilityText?.let { attributes["accessibilityText"] = it }
  className?.let { attributes["class"] = it }
  hintText?.let { attributes["hintText"] = it }

  // Add boolean attributes as strings
  if (ignoreBoundsFiltering) attributes["ignoreBoundsFiltering"] = "true"
  if (scrollable) attributes["scrollable"] = "true"
  if (focusable) attributes["focusable"] = "true"
  if (password) attributes["password"] = "true"

  // Use integer bounds (preferred) or fall back to centerPoint + dimensions for legacy data.
  val b = bounds
  if (b != null) {
    attributes["bounds"] = "[${b.x1},${b.y1}][${b.x2},${b.y2}]"
  }

  return TreeNode(
    attributes = attributes,
    children = children.map { it.asTreeNode() },
    clickable = if (clickable) true else null,
    enabled = if (enabled) true else null,
    focused = if (focused) true else null,
    checked = if (checked) true else null,
    selected = if (selected) true else null,
  )
}
