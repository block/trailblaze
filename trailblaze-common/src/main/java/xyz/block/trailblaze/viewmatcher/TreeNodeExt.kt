package xyz.block.trailblaze.viewmatcher

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

  // Reconstruct bounds from dimensions and centerPoint
  if (dimensions != null && centerPoint != null) {
    val dimensionsParts = dimensions!!.split("x")
    val centerParts = centerPoint!!.split(",")

    if (dimensionsParts.size == 2 && centerParts.size == 2) {
      val width = dimensionsParts[0].toIntOrNull()
      val height = dimensionsParts[1].toIntOrNull()
      val centerX = centerParts[0].toIntOrNull()
      val centerY = centerParts[1].toIntOrNull()

      if (width != null && height != null && centerX != null && centerY != null) {
        val left = centerX - (width / 2)
        val top = centerY - (height / 2)
        val right = centerX + (width / 2)
        val bottom = centerY + (height / 2)

        attributes["bounds"] = "[$left,$top][$right,$bottom]"
      }
    }
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
