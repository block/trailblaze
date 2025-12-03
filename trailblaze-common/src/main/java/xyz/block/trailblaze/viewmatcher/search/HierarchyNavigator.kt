package xyz.block.trailblaze.viewmatcher.search

import xyz.block.trailblaze.api.ViewHierarchyTreeNode

/**
 * Utilities for navigating the view hierarchy tree.
 */
object HierarchyNavigator {
  /**
   * Finds the path from root to target node (excluding the target itself).
   *
   * Performs a depth-first search to find the target node and returns all ancestors
   * from root to the target's parent. The returned list is in root-first order
   * (e.g., [root, grandparent, parent]).
   *
   * @param target The target node to find a path to
   * @param root The root of the view hierarchy to search from
   * @return List of nodes from root to target's parent (empty if target is root)
   */
  fun findPathToTarget(
    target: ViewHierarchyTreeNode,
    root: ViewHierarchyTreeNode,
  ): List<ViewHierarchyTreeNode> {
    fun findPath(
      current: ViewHierarchyTreeNode,
      targetId: Long,
      path: MutableList<ViewHierarchyTreeNode>,
    ): Boolean {
      // If we found the target, return true (don't include target in the path)
      if (current.nodeId == targetId) {
        return true
      }

      // Add current node to path and search children
      path.add(current)

      for (child in current.children) {
        if (findPath(child, targetId, path)) {
          return true
        }
      }

      // If target not found in this subtree, remove current node from path
      path.removeAt(path.size - 1)
      return false
    }

    val parentPath = mutableListOf<ViewHierarchyTreeNode>()
    findPath(
      current = root,
      targetId = target.nodeId,
      path = parentPath,
    )

    return parentPath
  }
}
