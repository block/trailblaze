package xyz.block.trailblaze.viewhierarchy

import maestro.DeviceInfo
import xyz.block.trailblaze.api.ViewHierarchyTreeNode
import xyz.block.trailblaze.exception.TrailblazeException
import xyz.block.trailblaze.viewhierarchy.ViewHierarchyFilter.Companion.collectAllClickableAndEnabledElements
import xyz.block.trailblaze.viewhierarchy.ViewHierarchyFilter.Companion.filterOutOfBounds

object ViewHierarchyTreeNodeUtils {
  /**
   * Create a list of view hierarchy nodes that are clickable and enabled,
   */
  fun from(
    viewHierarchyRoot: ViewHierarchyTreeNode,
    deviceInfo: DeviceInfo,
  ): List<ViewHierarchyTreeNode> {
    val deviceInfo = deviceInfo
    val treeNodesInBounds: ViewHierarchyTreeNode = viewHierarchyRoot.filterOutOfBounds(
      width = deviceInfo.widthPixels,
      height = deviceInfo.heightPixels,
    ) ?: throw TrailblazeException("Error filtering view hierarchy: no elements in bounds")

    // Use flat clickable+enabled extraction instead of optimizer
    val clickableNodes: List<ViewHierarchyTreeNode> = treeNodesInBounds.collectAllClickableAndEnabledElements()

    return clickableNodes
  }
}
