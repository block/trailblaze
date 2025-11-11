package xyz.block.trailblaze.viewmatcher

import maestro.TreeNode
import xyz.block.trailblaze.api.TrailblazeElementSelector
import xyz.block.trailblaze.api.ViewHierarchyTreeNode
import xyz.block.trailblaze.utils.Ext.toViewHierarchyTreeNode

sealed interface ElementMatches {

  val trailblazeElementSelector: TrailblazeElementSelector

  data class NoMatches(
    override val trailblazeElementSelector: TrailblazeElementSelector,
  ) : ElementMatches

  data class SingleMatch(
    val node: TreeNode,
    override val trailblazeElementSelector: TrailblazeElementSelector,
  ) : ElementMatches {
    val viewHierarchyTreeNode = node.toViewHierarchyTreeNode()!!
  }

  data class MultipleMatches(
    val nodes: List<TreeNode>,
    override val trailblazeElementSelector: TrailblazeElementSelector,
  ) : ElementMatches {
    val matchingNodeCount: Int = nodes.size
    val viewHierarchyTreeNodes: List<ViewHierarchyTreeNode> = nodes.mapNotNull { it.toViewHierarchyTreeNode() }
  }
}
