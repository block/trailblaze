package xyz.block.trailblaze.host.screenstate

import maestro.DeviceInfo
import maestro.Driver
import maestro.filterOutOfBounds
import okio.Buffer
import xyz.block.trailblaze.api.ScreenState
import xyz.block.trailblaze.api.ViewHierarchyTreeNode
import xyz.block.trailblaze.api.ViewHierarchyTreeNode.Companion.relabelWithFreshIds
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.host.setofmark.HostCanvasSetOfMark
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
  maxAttempts: Int = 10,
) : ScreenState {

  companion object {
    fun ByteArray.computedImageSizeJvm(): Pair<Int, Int> {
      val image = ImageIO.read(ByteArrayInputStream(this))
      return image.width to image.height
    }
  }

  private val deviceInfo: DeviceInfo = maestroDriver.deviceInfo()
  override val deviceWidth: Int = deviceInfo.widthGrid
  override val deviceHeight: Int = deviceInfo.heightGrid

  private var matched = false
  private var attempts = 0
  private var stableRelabeledViewHierarchy: ViewHierarchyTreeNode? = null
  private var stableFilteredViewHierarchy: ViewHierarchyTreeNode? = null
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

      // Filter the view hierarchy if needed
      stableFilteredViewHierarchy =
        if (filterViewHierarchy && stableRelabeledViewHierarchy != null) {
          val viewHierarchyFilter = ViewHierarchyFilter.create(
            screenWidth = deviceWidth,
            screenHeight = deviceHeight,
            platform = deviceInfo.platform.toTrailblazeDevicePlatform(),
          )
          viewHierarchyFilter.filterInteractableViewHierarchyTreeNodes(
            stableRelabeledViewHierarchy!!,
          )
        } else {
          stableRelabeledViewHierarchy
        }

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

  override val viewHierarchy: ViewHierarchyTreeNode = stableFilteredViewHierarchy
    ?: throw IllegalStateException("Failed to get stable view hierarchy from Maestro driver after $maxAttempts attempts.")

  override val trailblazeDevicePlatform: TrailblazeDevicePlatform = deviceInfo.platform.toTrailblazeDevicePlatform()

  /**
   * Returns screenshot bytes with set of mark annotations applied if enabled.
   * This matches the device-side behavior where set of mark is applied based on the setOfMarkEnabled flag.
   * Uses the filtered view hierarchy for set of mark annotations.
   */
  private fun computeScreenshotBytes(): ByteArray? {
    val rawBytes = stableRawScreenshotBytes ?: return null
    val bufferedImage = stableBufferedImage ?: return null

    if (!setOfMarkEnabled) {
      return rawBytes
    }

    val elementList = ViewHierarchyTreeNodeUtils.from(
      viewHierarchy, // Use filtered hierarchy for set of mark
      deviceInfo,
    )

    val canvas = HostCanvasSetOfMark(bufferedImage, deviceInfo)
    canvas.draw(elementList)
    val result = canvas.toByteArray()

    return result
  }

  /**
   * The screenshotBytes property returns screenshots with or without set of mark annotations
   * based on the setOfMarkEnabled flag, matching the device-side behavior.
   */
  override val screenshotBytes: ByteArray? = computeScreenshotBytes()
}
