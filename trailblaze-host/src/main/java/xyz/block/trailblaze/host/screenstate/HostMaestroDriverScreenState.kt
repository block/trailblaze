package xyz.block.trailblaze.host.screenstate

import maestro.DeviceInfo
import maestro.Driver
import maestro.Platform
import maestro.filterOutOfBounds
import okio.Buffer
import xyz.block.trailblaze.api.ScreenState
import xyz.block.trailblaze.api.ScreenshotScalingConfig
import xyz.block.trailblaze.api.ViewHierarchyTreeNode
import xyz.block.trailblaze.api.ViewHierarchyTreeNode.Companion.relabelWithFreshIds
import xyz.block.trailblaze.devices.TrailblazeDeviceClassifier
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.host.setofmark.HostCanvasSetOfMark
import xyz.block.trailblaze.host.toTrailblazeDevicePlatform
import xyz.block.trailblaze.host.util.BufferedImageUtils.rotate180
import xyz.block.trailblaze.host.util.BufferedImageUtils.rotateClockwise90
import xyz.block.trailblaze.host.util.BufferedImageUtils.rotateCounterClockwise90
import xyz.block.trailblaze.host.util.BufferedImageUtils.scale
import xyz.block.trailblaze.host.util.BufferedImageUtils.toByteArray
import xyz.block.trailblaze.utils.Ext.toViewHierarchyTreeNode
import xyz.block.trailblaze.viewhierarchy.ViewHierarchyCompactFormatter
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
  override val deviceClassifiers: List<TrailblazeDeviceClassifier> = emptyList(),
  private val fullHierarchy: Boolean = false,
  private val includeOffscreen: Boolean = false,
) : ScreenState {

  private val deviceInfo: DeviceInfo = maestroDriver.deviceInfo()
  override val deviceWidth: Int = deviceInfo.widthGrid
  override val deviceHeight: Int = deviceInfo.heightGrid

  private var matched = false
  private var attempts = 0
  private var stableRelabeledViewHierarchy: ViewHierarchyTreeNode? = null
  private var stableBufferedImage: BufferedImage? = null

  init {
    while (!matched && attempts < maxAttempts) {
      // Grab the first raw hierarchy (do NOT relabel)
      val rawTree1 = maestroDriver.contentDescriptor(false)
      // Keep unfiltered tree for iOS orientation detection (status bar has 0x0 bounds
      // and gets stripped by filterOutOfBounds)
      val unfilteredVh1 = rawTree1.toViewHierarchyTreeNode()
      val vh1 = rawTree1
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

      // On iOS, screenshots always arrive in the native portrait pixel orientation regardless
      // of the device orientation. Detect the actual orientation by checking the position of
      // status bar elements in the original (unfiltered) view hierarchy and rotate to match.
      val finalImage = if (deviceInfo.platform == Platform.IOS && unfilteredVh1 != null) {
        rotateIosScreenshotToMatchOrientation(bufferedImage, unfilteredVh1, deviceInfo)
      } else {
        bufferedImage
      }
      stableBufferedImage = finalImage

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

  override val viewHierarchyTextRepresentation: String
    get() = ViewHierarchyCompactFormatter.format(
      root = viewHierarchy,
      platform = trailblazeDevicePlatform,
      screenWidth = deviceWidth,
      screenHeight = deviceHeight,
      deviceClassifiers = deviceClassifiers,
      includeOffscreen = includeOffscreen,
      fullHierarchy = fullHierarchy,
    )

  /**
   * Returns the clean screenshot bytes without any annotations.
   * Applies scaling from [screenshotScalingConfig] so all logged screenshots use a consistent size.
   */
  override val screenshotBytes: ByteArray? by lazy {
    scaleAndEncode(stableBufferedImage)
  }

  /**
   * Returns screenshot bytes with set-of-mark annotations applied if enabled.
   * Generates annotations on-demand without caching - used only for LLM requests.
   * Uses the filtered view hierarchy for set-of-mark annotations.
   * Applies the same scaling as [screenshotBytes] via [screenshotScalingConfig].
   */
  override val annotatedScreenshotBytes: ByteArray?
    get() {
      val bufferedImage = stableBufferedImage ?: return null

      // If set-of-mark is disabled, return the clean (already-scaled) screenshot
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

      // Apply set of mark annotations on full-res image (before scaling for max quality)
      val elementList = ViewHierarchyTreeNodeUtils.from(
        viewHierarchy, // Use filtered hierarchy for set of mark
        deviceInfo,
      )

      val canvas = HostCanvasSetOfMark(imageForAnnotation, deviceInfo)
      canvas.draw(elementList)

      return scaleAndEncode(imageForAnnotation)
    }

  /**
   * Scales and encodes a [BufferedImage] using [screenshotScalingConfig].
   * Shared by [screenshotBytes] and [annotatedScreenshotBytes] for consistent output.
   */
  private fun scaleAndEncode(image: BufferedImage?): ByteArray? {
    image ?: return null

    if (screenshotScalingConfig == null) {
      return image.toByteArray()
    }

    val scaled = image.scale(
      maxDim1 = screenshotScalingConfig.maxDimension1,
      maxDim2 = screenshotScalingConfig.maxDimension2,
    )

    return scaled.toByteArray(
      format = screenshotScalingConfig.imageFormat,
      compressionQuality = screenshotScalingConfig.compressionQuality,
    )
  }

  companion object {
    /**
     * Detects the iOS device orientation from the view hierarchy by finding status bar elements
     * and checking their position, then rotates the screenshot to match the VH coordinate system.
     *
     * iOS screenshots always arrive in the native portrait pixel buffer orientation regardless
     * of device rotation. The view hierarchy coordinates, however, reflect the current orientation.
     *
     * The original (unfiltered) VH has a `0x0` overlay node that contains the status bar.
     * Any descendant with real bounds inside that overlay tells us where the status bar sits:
     *
     * - PORTRAIT: status bar near top (y ≈ 0) → no rotation
     * - UPSIDE_DOWN: status bar near bottom (y ≈ deviceHeight) → vertical flip
     * - LANDSCAPE_RIGHT: status bar near left edge (x ≈ 0) → CCW 90° rotation
     * - LANDSCAPE_LEFT: status bar near right edge (x ≈ deviceWidth) or not found → CW 90°
     */
    internal fun rotateIosScreenshotToMatchOrientation(
      screenshot: BufferedImage,
      viewHierarchy: ViewHierarchyTreeNode,
      deviceInfo: DeviceInfo,
    ): BufferedImage {
      val isDeviceLandscape = deviceInfo.widthGrid > deviceInfo.heightGrid
      val isScreenshotPortrait = screenshot.height > screenshot.width

      // Landscape device with portrait screenshot → needs 90° rotation.
      // CW 90° maps the LEFT column of the portrait buffer to the TOP of landscape output.
      // CCW 90° maps the RIGHT column of the portrait buffer to the TOP of landscape output.
      if (isDeviceLandscape && isScreenshotPortrait) {
        return when (val pos = findIosStatusBarPosition(viewHierarchy)) {
          is StatusBarPosition.Found ->
            if (pos.cx < deviceInfo.widthGrid / 2) screenshot.rotateClockwise90()
            else screenshot.rotateCounterClockwise90()
          // No status bar found → default to LANDSCAPE_LEFT (CCW 90°)
          StatusBarPosition.NOT_FOUND -> screenshot.rotateCounterClockwise90()
        }
      }

      // Portrait device with portrait screenshot — check for upside-down
      if (!isDeviceLandscape && isScreenshotPortrait) {
        return when (val pos = findIosStatusBarPosition(viewHierarchy)) {
          is StatusBarPosition.Found ->
            if (pos.cy > deviceInfo.heightGrid / 2) screenshot.rotate180() else screenshot
          StatusBarPosition.NOT_FOUND -> screenshot
        }
      }

      return screenshot
    }

    /**
     * Finds the status bar position from the original (unfiltered) iOS view hierarchy.
     *
     * The original tree has a root with 0x0 dimensions containing:
     * - root[0]: the app window (real dimensions)
     * - root[1]: the status bar overlay (0x0 dimensions, contains time/Wi-Fi/battery)
     *
     * Everything inside the 0x0 overlay IS the status bar, so we just find the first
     * descendant with real bounds — no text matching needed.
     */
    private fun findIosStatusBarPosition(
      root: ViewHierarchyTreeNode,
    ): StatusBarPosition {
      // Find the status bar overlay: a root child with 0x0 dimensions
      val statusBarOverlay = root.children.find { it.dimensions == "0x0" }
        ?: return StatusBarPosition.NOT_FOUND

      // Find the first descendant with real bounds
      val element = findFirstWithBounds(statusBarOverlay)
        ?: return StatusBarPosition.NOT_FOUND

      val bounds = element.bounds!!
      return StatusBarPosition.Found(
        cx = (bounds.x1 + bounds.x2) / 2,
        cy = (bounds.y1 + bounds.y2) / 2,
      )
    }

    private fun findFirstWithBounds(node: ViewHierarchyTreeNode): ViewHierarchyTreeNode? {
      if (node.bounds != null && node.dimensions != "0x0") return node
      for (child in node.children) {
        val result = findFirstWithBounds(child)
        if (result != null) return result
      }
      return null
    }

    internal sealed class StatusBarPosition {
      data class Found(val cx: Int, val cy: Int) : StatusBarPosition()
      data object NOT_FOUND : StatusBarPosition()
    }
  }
}
