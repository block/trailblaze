package xyz.block.trailblaze.host.screenstate

import maestro.DeviceInfo
import maestro.Driver
import maestro.filterOutOfBounds
import okio.Buffer
import xyz.block.trailblaze.api.ScreenState
import xyz.block.trailblaze.api.ScreenshotScalingConfig
import xyz.block.trailblaze.api.ViewHierarchyTreeNode
import xyz.block.trailblaze.api.ViewHierarchyTreeNode.Companion.relabelWithFreshIds
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.host.setofmark.HostCanvasSetOfMark
import xyz.block.trailblaze.host.util.BufferedImageUtils.scale
import xyz.block.trailblaze.host.util.BufferedImageUtils.toByteArray
import xyz.block.trailblaze.utils.Ext.toViewHierarchyTreeNode
import xyz.block.trailblaze.viewhierarchy.ViewHierarchyFilter
import xyz.block.trailblaze.viewhierarchy.ViewHierarchyTreeNodeUtils
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO

/**
 * Pulls screen state from the Maestro [maestro.Driver]
 */
class HostMaestroDriverScreenState(
  maestroDriver: Driver,
  private val setOfMarkEnabled: Boolean,
  private val filterViewHierarchy: Boolean = true,
  private val screenshotScalingConfig: ScreenshotScalingConfig? = ScreenshotScalingConfig.DEFAULT,
  maxAttempts: Int = 10,
) : ScreenState {

  private val deviceInfo: DeviceInfo = maestroDriver.deviceInfo()
  override val deviceWidth: Int = deviceInfo.widthGrid
  override val deviceHeight: Int = deviceInfo.heightGrid

  private var matched = false
  private var attempts = 0
  private var stableRelabeledViewHierarchy: ViewHierarchyTreeNode? = null
  private var stableRawScreenshotBytes: ByteArray? = null
  private var stableBufferedImage: BufferedImage? = null

  init {
    while (!matched && attempts < maxAttempts) {
      // Grab the first raw hierarchy (do NOT relabel)
      val vh1 = maestroDriver.contentDescriptor(false)
        .filterOutOfBounds(width = deviceWidth, height = deviceHeight)
        ?.toViewHierarchyTreeNode()

      // Relabel for drawing and returning
      stableRelabeledViewHierarchy = vh1?.relabelWithFreshIds()

      // Take the screenshot (raw, without set of mark)
      val sink = Buffer()
      maestroDriver.takeScreenshot(sink, compressed = false)
      val screenshotBytes = sink.readByteArray()
      val bufferedImage = ByteArrayInputStream(screenshotBytes).use { bis ->
        ImageIO.read(bis)
      }

      // Grab the second raw hierarchy (do NOT relabel)
      val vh2 = maestroDriver.contentDescriptor(false)
        .filterOutOfBounds(width = deviceWidth, height = deviceHeight)
        ?.toViewHierarchyTreeNode()

      stableRawScreenshotBytes = screenshotBytes
      stableBufferedImage = bufferedImage

      if (vh1 == vh2) {
        matched = true
      } else {
        attempts++
        if (attempts < maxAttempts) {
          Thread.sleep((attempts * 100).toLong())
        }
      }
    }
  }

  override val viewHierarchyOriginal: ViewHierarchyTreeNode = stableRelabeledViewHierarchy
    ?: throw IllegalStateException("Failed to get stable view hierarchy from Maestro driver after $maxAttempts attempts.")

  /**
   * Returns the filtered view hierarchy.
   * Generates filtered hierarchy on-demand without caching - used for LLM requests.
   */
  override val viewHierarchy: ViewHierarchyTreeNode
    get() {
      if (!filterViewHierarchy) {
        return viewHierarchyOriginal
      }

      val viewHierarchyFilter = ViewHierarchyFilter.create(
        screenWidth = deviceWidth,
        screenHeight = deviceHeight,
        platform = deviceInfo.platform.toTrailblazeDevicePlatform(),
      )
      return viewHierarchyFilter.filterInteractableViewHierarchyTreeNodes(viewHierarchyOriginal)
    }

  override val trailblazeDevicePlatform: TrailblazeDevicePlatform = deviceInfo.platform.toTrailblazeDevicePlatform()

  /**
   * Returns the clean screenshot bytes without any annotations.
   * This is the raw screenshot suitable for compliance documentation and is always stored.
   */
  override val screenshotBytes: ByteArray? = stableRawScreenshotBytes

  /**
   * Returns screenshot bytes with set-of-mark annotations applied if enabled.
   * Generates annotations on-demand without caching - used only for LLM requests.
   * Uses the filtered view hierarchy for set-of-mark annotations.
   * Applies scaling if screenshotScalingConfig is specified.
   */
  override val annotatedScreenshotBytes: ByteArray?
    get() {
      val bufferedImage = stableBufferedImage ?: return null

      // If set-of-mark is disabled, return the clean screenshot
      if (!setOfMarkEnabled) {
        return screenshotBytes
      }

      // Create a copy of the buffered image for annotation (don't modify original)
      val imageForAnnotation = BufferedImage(
        bufferedImage.width,
        bufferedImage.height,
        bufferedImage.type
      )
      val graphics = imageForAnnotation.createGraphics()
      graphics.drawImage(bufferedImage, 0, 0, null)
      graphics.dispose()

      // Apply set of mark annotations
      val elementList = ViewHierarchyTreeNodeUtils.from(
        viewHierarchy, // Use filtered hierarchy for set of mark
        deviceInfo,
      )

      val canvas = HostCanvasSetOfMark(imageForAnnotation, deviceInfo)
      canvas.draw(elementList)

      // Apply scaling if config is specified
      val scaledImage = if (screenshotScalingConfig != null) {
        imageForAnnotation.scale(
          maxDim1 = screenshotScalingConfig.maxDimension1,
          maxDim2 = screenshotScalingConfig.maxDimension2,
        )
      } else {
        imageForAnnotation
      }

      // Convert to byte array with format and quality from config
      return if (screenshotScalingConfig != null) {
        scaledImage.toByteArray(
          format = screenshotScalingConfig.imageFormat,
          compressionQuality = screenshotScalingConfig.compressionQuality,
        )
      } else {
        // Default to PNG when no config is provided
        scaledImage.toByteArray()
      }
    }
}
