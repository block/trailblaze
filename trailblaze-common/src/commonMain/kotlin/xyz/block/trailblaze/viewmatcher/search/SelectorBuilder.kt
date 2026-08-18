package xyz.block.trailblaze.viewmatcher.search

import xyz.block.trailblaze.api.TrailblazeElementSelector
import xyz.block.trailblaze.api.ViewHierarchyTreeNode

/**
 * Utilities for building element selectors from view hierarchy nodes.
 */
object SelectorBuilder {
  /**
   * Builds a leaf selector from a node's available properties.
   *
   * A "leaf" selector only includes the node's direct properties (text, id, state)
   * and doesn't include any relationship properties (childOf, containsChild, etc.).
   *
   * @param node The node to build a selector for
   * @return A selector with the node's available properties
   */
  fun buildLeafSelectorWithAvailableProperties(
    node: ViewHierarchyTreeNode,
  ): TrailblazeElementSelector = TrailblazeElementSelector(
    textRegex = node.resolveMaestroText()?.takeIf { it.isNotBlank() },
    idRegex = node.resourceId?.takeIf { it.isNotBlank() },
    enabled = node.enabled.takeIf { !it },
    selected = node.selected.takeIf { it },
    checked = node.checked.takeIf { it },
    focused = node.focused.takeIf { it },
  )
}
