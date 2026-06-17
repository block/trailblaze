package xyz.block.trailblaze.viewmatcher.models

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

/**
 * The node Maestro would actually tap for this match, or null if the tap is not deterministic.
 *
 * Maestro resolves a tap as `filter(nodes).first()`, and every selector's filter ends with
 * `clickableFirst()` — a stable sort that orders clickable nodes ahead of non-clickable ones. So:
 *  - a [ElementMatches.SingleMatch] always taps its one node, and
 *  - a [ElementMatches.MultipleMatches] taps deterministically only when exactly one match is
 *    clickable: `clickableFirst()` then sorts that node first regardless of document order, so
 *    `first()` is always it.
 *
 * This matters under Maestro 2.6.1, whose `containsChild` matches *every* parent holding a matching
 * child (2.3.0 collapsed to the first match). A clickable wrapper — e.g. a dropdown trigger whose
 * label text also appears on a non-clickable list row — is then one of several `containsChild`
 * matches but still the sole clickable one, so a semantic `containsChild` selector taps it reliably.
 */
fun ElementMatches.deterministicTapTarget(): TreeNode? = when (this) {
  is ElementMatches.SingleMatch -> node
  is ElementMatches.MultipleMatches -> nodes.filter { it.clickable == true }.singleOrNull()
  is ElementMatches.NoMatches -> null
}
