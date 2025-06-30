package xyz.block.trailblaze.api

import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import xyz.block.trailblaze.viewhierarchy.ViewHierarchyFilter
import java.util.concurrent.atomic.AtomicLong

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
  val enabled: Boolean = false,
  val focusable: Boolean = false,
  val focused: Boolean = false,
  val ignoreBoundsFiltering: Boolean = false,
  val password: Boolean = false,
  val resourceId: String? = null,
  val scrollable: Boolean = false,
  val selected: Boolean = false,
  val text: String? = null,
  val depth: Int = 0,
  var containerId: String? = null,
) {

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
    private val viewIdCount = AtomicLong(1)

    /**
     * Relabels the tree with new nodeIds using a shared atomic incrementer.
     * Returns a new tree with the same structure and data, but fresh nodeIds.
     */
    fun ViewHierarchyTreeNode.relabelWithFreshIds(): ViewHierarchyTreeNode {
      viewIdCount.set(1)
      fun relabel(node: ViewHierarchyTreeNode): ViewHierarchyTreeNode = node.copy(
        nodeId = viewIdCount.getAndIncrement(),
        children = node.children.map { relabel(it) },
      )
      return relabel(this)
    }
  }
}
