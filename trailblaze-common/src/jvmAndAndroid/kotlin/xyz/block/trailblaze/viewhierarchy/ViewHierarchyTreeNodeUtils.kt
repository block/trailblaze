package xyz.block.trailblaze.viewhierarchy

import maestro.DeviceInfo
import maestro.Platform
import xyz.block.trailblaze.api.ViewHierarchyTreeNode
import xyz.block.trailblaze.exception.TrailblazeException
import xyz.block.trailblaze.viewhierarchy.ViewHierarchyFilter.Companion.collectAllClickableAndEnabledElements
import xyz.block.trailblaze.viewhierarchy.ViewHierarchyFilter.Companion.collectIOSElements
import xyz.block.trailblaze.viewhierarchy.ViewHierarchyFilter.Companion.filterOutOfBounds
import xyz.block.trailblaze.viewhierarchy.ViewHierarchyFilter.Companion.hasMaestroProperties

object ViewHierarchyTreeNodeUtils {
  /**
   * Create a list of view hierarchy nodes suitable for set-of-mark annotation.
   *
   * Only includes elements that will also appear in the LLM text hierarchy
   * (i.e. have a non-blank [TrailblazeElementSelector]). This ensures marks on
   * the annotated screenshot match exactly the `[nodeId:X]` entries the LLM
   * receives, preventing phantom marks that the LLM cannot reference.
   */
  fun from(
    viewHierarchyRoot: ViewHierarchyTreeNode,
    deviceInfo: DeviceInfo,
  ): List<ViewHierarchyTreeNode> {
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

    // Only mark elements that have a non-blank selector (text, resourceId, etc.)
    // so the annotated screenshot and the LLM text hierarchy stay in sync.
    return clickableNodes.filter { it.hasMaestroProperties() }
  }
}
