package xyz.block.trailblaze.api

import xyz.block.trailblaze.devices.TrailblazeDevicePlatform

interface ScreenState {
  /**
   * Returns the clean screenshot bytes without any debugging annotations.
   * This is the primary screenshot stored in memory and is always unmarked.
   * 
   * **Memory Optimization**: All implementations store only this clean screenshot.
   * Annotated versions are generated on-demand via annotatedScreenshotBytes.
   * 
   * - Always returns the clean, unmarked screenshot
   * - Used for logging, snapshots, and compliance documentation
   * - May be null if screenshot capture failed
   */
  val screenshotBytes: ByteArray?

  /**
   * Returns screenshot bytes with set-of-mark annotations applied.
   * 
   * **Memory Optimization**: This property generates annotated screenshots on-demand
   * without caching. Annotated screenshots are only used for LLM requests and then
   * discarded to minimize memory usage.
   * 
   * - Generates set-of-mark annotations lazily when accessed
   * - If set-of-mark is disabled: Returns the clean screenshot (same as screenshotBytes)
   * - May be null if screenshot capture failed
   * 
   * Defaults to screenshotBytes for backward compatibility with existing implementations.
   */
  val annotatedScreenshotBytes: ByteArray?
    get() = screenshotBytes

  val deviceWidth: Int

  val deviceHeight: Int

  val viewHierarchyOriginal: ViewHierarchyTreeNode

  val viewHierarchy: ViewHierarchyTreeNode

  val trailblazeDevicePlatform: TrailblazeDevicePlatform
}
