package xyz.block.trailblaze.viewhierarchy

import maestro.DeviceInfo
import maestro.Platform
import xyz.block.trailblaze.api.ViewHierarchyTreeNode
import xyz.block.trailblaze.exception.TrailblazeException
import xyz.block.trailblaze.viewhierarchy.ViewHierarchyFilter.Companion.collectAllClickableAndEnabledElements
import xyz.block.trailblaze.viewhierarchy.ViewHierarchyFilter.Companion.collectIOSElements
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

    // Filter based on platform
    val clickableNodes = if (deviceInfo.platform == Platform.IOS) {
      treeNodesInBounds.collectIOSElements()
    } else {
      treeNodesInBounds.collectAllClickableAndEnabledElements()
    }

    return clickableNodes
  }
}
