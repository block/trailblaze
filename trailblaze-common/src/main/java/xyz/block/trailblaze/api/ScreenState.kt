package xyz.block.trailblaze.api

interface ScreenState {
  // Returns a screenshot of the device as a ByteArray
  val screenshotBytes: ByteArray?

  val deviceWidth: Int

  val deviceHeight: Int

  val viewHierarchyOriginal: ViewHierarchyTreeNode

  val viewHierarchy: ViewHierarchyTreeNode
}
