package xyz.block.trailblaze.ui.tabs.trails

import xyz.block.trailblaze.ui.recordings.ExistingTrail

/**
 * Represents a node in the trail directory tree.
 * Can be either a directory or a trail file.
 */
sealed class TrailNode {
  abstract val name: String
  abstract val path: String
  
  /**
   * A directory in the trail tree that can contain other directories or files.
   *
   * @param name The directory name (e.g., "suite_84031")
   * @param path The full path to this directory
   * @param children The child nodes (directories and files)
   * @param isExpanded Whether this directory is expanded in the UI
   * @param depth The depth level in the tree (0 = root)
   */
  data class Directory(
    override val name: String,
    override val path: String,
    val children: List<TrailNode> = emptyList(),
    val isExpanded: Boolean = false,
    val depth: Int = 0,
  ) : TrailNode() {
    /**
     * Returns a copy with the expanded state toggled.
     */
    fun toggleExpanded(): Directory = copy(isExpanded = !isExpanded)
    
    /**
     * Returns a copy with children updated.
     */
    fun withChildren(newChildren: List<TrailNode>): Directory = copy(children = newChildren)
  }
  
  /**
   * A trail YAML file in the tree.
   *
   * @param name The file name (e.g., "ios-iphone.trail.yaml")
   * @param path The full path to this file
   * @param existingTrail The trail information
   * @param depth The depth level in the tree
   */
  data class TrailFile(
    override val name: String,
    override val path: String,
    val existingTrail: ExistingTrail,
    val depth: Int = 0,
  ) : TrailNode()
}
