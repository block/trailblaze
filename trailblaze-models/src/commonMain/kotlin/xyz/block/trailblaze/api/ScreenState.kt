package xyz.block.trailblaze.api

import xyz.block.trailblaze.devices.TrailblazeDevicePlatform

interface ScreenState {
  // Returns a screenshot of the device as a ByteArray
  val screenshotBytes: ByteArray?

  val deviceWidth: Int

  val deviceHeight: Int

  val viewHierarchyOriginal: ViewHierarchyTreeNode

  val viewHierarchy: ViewHierarchyTreeNode

  val trailblazeDevicePlatform: TrailblazeDevicePlatform
}
