package xyz.block.trailblaze.host.screenstate

import maestro.DeviceInfo
import maestro.Driver
import maestro.filterOutOfBounds
import okio.Buffer
import xyz.block.trailblaze.api.ScreenState
import xyz.block.trailblaze.api.ViewHierarchyTreeNode
import xyz.block.trailblaze.api.ViewHierarchyTreeNode.Companion.relabelWithFreshIds
import xyz.block.trailblaze.host.setofmark.HostCanvasSetOfMark
import xyz.block.trailblaze.utils.Ext.toViewHierarchyTreeNode
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
  private var stableScreenshotBytes: ByteArray? = null
  private var stableBufferedImage: BufferedImage? = null

  init {
    while (!matched && attempts < maxAttempts) {
      // Grab the first raw hierarchy (do NOT relabel)
      val vh1 = maestroDriver.contentDescriptor(false)
        .filterOutOfBounds(width = deviceWidth, height = deviceHeight)
        ?.toViewHierarchyTreeNode()

      // Relabel for drawing and returning
      val vh1Labelled = vh1?.relabelWithFreshIds()

      // Take the screenshot (for set of mark, relabel only for drawing)
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

      stableRelabeledViewHierarchy = vh1Labelled
      stableScreenshotBytes = screenshotBytes
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

  override val viewHierarchy: ViewHierarchyTreeNode = stableRelabeledViewHierarchy
    ?: throw IllegalStateException("Failed to get stable view hierarchy from Maestro driver after $maxAttempts attempts.")

  val screenshotModifier = { viewHierarchy: ViewHierarchyTreeNode, byteArray: ByteArray, bufferedImage: BufferedImage ->
    if (!setOfMarkEnabled) {
      byteArray
    } else {
      val elementList = ViewHierarchyTreeNodeUtils.from(
        viewHierarchy,
        deviceInfo,
      )
      val canvas = HostCanvasSetOfMark(bufferedImage)
      canvas.draw(elementList)
      canvas.toByteArray()
    }
  }
  override val screenshotBytes: ByteArray? = stableScreenshotBytes?.let {
    screenshotModifier(viewHierarchy, it, stableBufferedImage!!)
  }
}
