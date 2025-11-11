package xyz.block.trailblaze.api

import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import xyz.block.trailblaze.viewhierarchy.ViewHierarchyFilter

/**
 * Allows a data model that isn't just straight xml for view hierarchy information.
 *
 * All UiAutomator attributes:
 * index
 * text
 * resource-id
 * class
 * package
 * content-desc
 * checkable
 * checked
 * clickable
 * enabled
 * focusable
 * focused
 * scrollable
 * long-clickable
 * password
 * selected
 * bounds
 */
@Serializable
data class ViewHierarchyTreeNode(
  val nodeId: Long = 1,
  val accessibilityText: String? = null,
  val centerPoint: String? = null,
  val checked: Boolean = false,
  val children: List<ViewHierarchyTreeNode> = emptyList(),
  val className: String? = null,
  val clickable: Boolean = false,
  val dimensions: String? = null,
  val enabled: Boolean = true,
  val focusable: Boolean = false,
  val focused: Boolean = false,
  val hintText: String? = null,
  val ignoreBoundsFiltering: Boolean = false,
  val password: Boolean = false,
  val resourceId: String? = null,
  val scrollable: Boolean = false,
  val selected: Boolean = false,
  val text: String? = null,
) {

  /**
   * See: https://github.com/mobile-dev-inc/Maestro/blob/42ae01049fc1e3466ad4ba45414b7bb25a19c899/maestro-orchestra/src/main/java/maestro/orchestra/Orchestra.kt#L1440-L1448
   */
  fun resolveMaestroText(): String? = text ?: hintText ?: accessibilityText

  fun aggregate(): List<ViewHierarchyTreeNode> = listOf(this) + children.flatMap { it.aggregate() }

  @Transient
  val bounds: ViewHierarchyFilter.Bounds? = run {
    val dimensionsPair: Pair<Int, Int>? = dimensions?.split("x")?.let { tokens ->
      if (tokens.size == 2) {
        Pair(tokens[0].toInt(), tokens[1].toInt())
      } else {
        null
      }
    }

    val centerPair: Pair<Int, Int>? = centerPoint?.split(",")?.let { tokens ->
      if (tokens.size == 2) {
        Pair(tokens[0].toInt(), tokens[1].toInt())
      } else {
        null
      }
    }

    if (dimensionsPair != null && centerPair != null) {
      val width = dimensionsPair.first
      val height = dimensionsPair.second
      val centerX = centerPair.first
      val centerY = centerPair.second
      ViewHierarchyFilter.Bounds(
        x1 = centerX - (width / 2),
        y1 = centerY - (height / 2),
        x2 = centerX + (width / 2),
        y2 = centerY + (height / 2),
      )
    } else {
      null
    }
  }

  companion object {

    /**
     * Search the tree for a node that matches the condition.
     */
    fun dfs(node: ViewHierarchyTreeNode, condition: (ViewHierarchyTreeNode) -> Boolean): ViewHierarchyTreeNode? {
      if (condition(node)) {
        return node
      }
      for (child in node.children) {
        val result = dfs(child, condition)
        if (result != null) {
          return result
        }
      }
      return null
    }

    /**
     * We use this to provide unique IDs for each node in the view hierarchy.
     */

    /**
     * Relabels the tree with new nodeIds using a shared atomic incrementer.
     * Returns a new tree with the same structure and data, but fresh nodeIds.
     */
    fun ViewHierarchyTreeNode.relabelWithFreshIds(): ViewHierarchyTreeNode {
      var viewIdCount = 1L
      fun relabel(node: ViewHierarchyTreeNode): ViewHierarchyTreeNode = node.copy(
        nodeId = viewIdCount++,
        children = node.children.map { relabel(it) },
      )
      return relabel(this)
    }
  }

  /**
   * Recursively compares equality with tolerance for dimension rounding errors.
   * Due to integer rounding when computing centerPoint from dimensions (divide by 2),
   * we allow a small tolerance (1-2 pixels) in dimension comparison.
   *
   * First tries exact equality (fast path), then falls back to lenient recursive comparison.
   */
  fun compareEqualityBasedOnBoundsNotDimensions(
    otherNode: ViewHierarchyTreeNode,
    boundsTolerance: Int = 2,
  ): Boolean {
    // Try exact equality first (fast path using data class ==)
    if (this == otherNode) {
      return true
    }

    // If exact equality fails, try lenient dimension comparison
    // Compare structure without dimensions
    val thisWithoutDims = this.copy(dimensions = null)
    val otherWithoutDims = otherNode.copy(dimensions = null)

    // Compare everything except dimensions and children (we'll check children recursively)
    if (thisWithoutDims.copy(children = emptyList()) != otherWithoutDims.copy(children = emptyList())) {
      return false
    }

    // Check dimensions are within tolerance
    val thisBounds = this.bounds
    val otherBounds = otherNode.bounds

    if (thisBounds != null && otherBounds != null) {
      // Both have bounds, check tolerance
      if (kotlin.math.abs(thisBounds.x1 - otherBounds.x1) > boundsTolerance ||
        kotlin.math.abs(thisBounds.y1 - otherBounds.y1) > boundsTolerance ||
        kotlin.math.abs(thisBounds.x2 - otherBounds.x2) > boundsTolerance ||
        kotlin.math.abs(thisBounds.y2 - otherBounds.y2) > boundsTolerance
      ) {
        return false
      }
    } else if ((thisBounds == null) != (otherBounds == null)) {
      // One has bounds, one doesn't
      return false
    }

    // Recursively compare children with same tolerance
    if (this.children.size != otherNode.children.size) {
      return false
    }

    return this.children.zip(otherNode.children).all { (child, otherChild) ->
      child.compareEqualityBasedOnBoundsNotDimensions(otherChild, boundsTolerance)
    }
  }

  /**
   * Applies a transformation function recursively to this node and all its children.
   * The transformation is applied depth-first, transforming children before parents.
   *
   * @param transform A function that takes a ViewHierarchyTreeNode and returns a transformed version
   * @return A new ViewHierarchyTreeNode with the transformation applied recursively
   */
  private fun deepTransform(transform: (ViewHierarchyTreeNode) -> ViewHierarchyTreeNode): ViewHierarchyTreeNode {
    val transformedChildren = children.map { it.deepTransform(transform) }
    val nodeWithTransformedChildren = this.copy(children = transformedChildren)
    return transform(nodeWithTransformedChildren)
  }

  /**
   * Returns a deep copy of this ViewHierarchyTreeNode without dimensions property.
   * This is applied recursively to all children as well.
   * Useful for comparisons where dimensions might have rounding errors.
   */
  fun deepCopyWithoutDimensions(): ViewHierarchyTreeNode = deepTransform { node -> node.copy(dimensions = null) }

  /**
   * Relabels the tree with new nodeIds using a shared atomic incrementer.
   * Returns a new tree with the same structure and data, but fresh nodeIds.
   */
  fun clearAllNodeIdsForThisAndAllChildren(): ViewHierarchyTreeNode {
    fun relabel(node: ViewHierarchyTreeNode): ViewHierarchyTreeNode = node.copy(
      nodeId = 1L,
      children = node.children.map { relabel(it) },
    )
    return relabel(this)
  }
}
