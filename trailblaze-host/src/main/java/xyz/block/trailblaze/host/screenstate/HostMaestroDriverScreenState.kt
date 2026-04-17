package xyz.block.trailblaze.host.screenstate

import maestro.DeviceInfo
import maestro.Driver
import maestro.Platform
import maestro.TreeNode
import maestro.filterOutOfBounds
import okio.Buffer
import xyz.block.trailblaze.api.AnnotationElement
import xyz.block.trailblaze.api.CompactScreenElements
import xyz.block.trailblaze.api.ScreenState
import xyz.block.trailblaze.api.ScreenshotScalingConfig
import xyz.block.trailblaze.api.TrailblazeNode
import xyz.block.trailblaze.api.ViewHierarchyTreeNode
import xyz.block.trailblaze.api.ViewHierarchyTreeNode.Companion.relabelWithFreshIds
import xyz.block.trailblaze.devices.TrailblazeDeviceClassifier
import xyz.block.trailblaze.util.Console
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.host.setofmark.HostCanvasSetOfMark
import xyz.block.trailblaze.host.toTrailblazeDevicePlatform
import xyz.block.trailblaze.host.util.BufferedImageUtils.rotate180
import xyz.block.trailblaze.host.util.BufferedImageUtils.rotateClockwise90
import xyz.block.trailblaze.host.util.BufferedImageUtils.rotateCounterClockwise90
import xyz.block.trailblaze.host.util.BufferedImageUtils.scale
import xyz.block.trailblaze.host.util.BufferedImageUtils.toByteArray
import xyz.block.trailblaze.utils.Ext.toViewHierarchyTreeNode
import xyz.block.trailblaze.viewhierarchy.ViewHierarchyFilter
import xyz.block.trailblaze.viewhierarchy.ViewHierarchyTreeNodeUtils
import xyz.block.trailblaze.viewmatcher.matching.toTrailblazeNodeAndroidMaestro
import xyz.block.trailblaze.viewmatcher.matching.toTrailblazeNodeIosMaestro
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO

/**
 * Pulls screen state from the Maestro [maestro.Driver]
 */
class HostMaestroDriverScreenState(
  maestroDriver: Driver,
  private val screenshotScalingConfig: ScreenshotScalingConfig? = ScreenshotScalingConfig.DEFAULT,
  override val deviceClassifiers: List<TrailblazeDeviceClassifier> = emptyList(),
  /** When true, skip device screenshot capture for maximum speed. View hierarchy is still captured. */
  private val skipScreenshot: Boolean = false,
) : ScreenState {

  private val deviceInfo: DeviceInfo = maestroDriver.deviceInfo()
  override val deviceWidth: Int = deviceInfo.widthGrid
  override val deviceHeight: Int = deviceInfo.heightGrid

  private var stableRelabeledViewHierarchy: ViewHierarchyTreeNode? = null
  private var stableTrailblazeNodeTree: TrailblazeNode? = null
  private var stableBufferedImage: BufferedImage? = null

  /**
   * Bundle identifier of the foreground iOS app, captured from the XCTest accessibility tree.
   *
   * The Maestro iOS driver maps `element.identifier` → `attributes["resource-id"]` for every
   * node. For the root `XCUIApplication` element, `.identifier` IS the bundle ID (e.g.
   * "com.example.myapp"). The iOS tree has a 0x0 container as its root with the application
   * node as its first child, so we check the root itself first and then `root[0]`.
   *
   * Null for Android devices (which surface the foreground package via other means).
   */
  private var foregroundAppId: String? = null

  init {
    // Single-fetch: Maestro's XCTest accessibility snapshot is atomic — no settling needed.
    // The XCTest runner returns a point-in-time snapshot of the accessibility tree; it won't
    // be "half-updated." If the screen is mid-animation, the agent will re-capture after its
    // next action anyway.
    Console.log("[ScreenState] maestroDriver class: ${maestroDriver.javaClass.simpleName}")
    val rawTree = maestroDriver.contentDescriptor(false)
    Console.log("[ScreenState] rawTree children: ${rawTree.children.size}")
    // Keep unfiltered tree for iOS orientation detection (status bar has 0x0 bounds
    // and gets stripped by filterOutOfBounds)
    val unfilteredVh = rawTree.toViewHierarchyTreeNode()
    val vh = rawTree
      .filterOutOfBounds(width = deviceWidth, height = deviceHeight)
      ?.toViewHierarchyTreeNode()

    stableRelabeledViewHierarchy = vh?.relabelWithFreshIds()

    // Build platform-specific TrailblazeNode tree for compact element list
    when (deviceInfo.platform) {
      Platform.IOS -> {
        stableTrailblazeNodeTree = rawTree.toTrailblazeNodeIosMaestro()
        foregroundAppId = extractIosBundleId(rawTree)
      }
      Platform.ANDROID -> {
        stableTrailblazeNodeTree = rawTree.toTrailblazeNodeAndroidMaestro()
        foregroundAppId = extractAndroidPackageId(rawTree)
      }
      else -> {}
    }

    // Take the screenshot (raw, without set of mark).
    // In fast mode (skipScreenshot), skip device screenshot capture entirely —
    // saves ~200-500ms per capture. View hierarchy is still captured for text-only analysis.
    if (!skipScreenshot) {
      val sink = Buffer()
      maestroDriver.takeScreenshot(sink, compressed = false)
      val screenshotBytes = sink.readByteArray()
      val bufferedImage = ByteArrayInputStream(screenshotBytes).use { bis ->
        ImageIO.read(bis)
      }

      // On iOS, screenshots always arrive in the native portrait pixel orientation regardless
      // of the device orientation. Detect the actual orientation by checking the position of
      // status bar elements in the original (unfiltered) view hierarchy and rotate to match.
      stableBufferedImage = if (deviceInfo.platform == Platform.IOS && unfilteredVh != null) {
        rotateIosScreenshotToMatchOrientation(bufferedImage, unfilteredVh, deviceInfo)
      } else {
        bufferedImage
      }
    }
  }

  override val viewHierarchy: ViewHierarchyTreeNode = stableRelabeledViewHierarchy
    ?: throw IllegalStateException("Failed to get view hierarchy from Maestro driver.")

  override val trailblazeDevicePlatform: TrailblazeDevicePlatform = deviceInfo.platform.toTrailblazeDevicePlatform()

  /** Cached compact elements result — shared between text representation and annotation elements. */
  private val compactElements: CompactScreenElements? by lazy {
    val tree = stableTrailblazeNodeTree ?: return@lazy null
    when (deviceInfo.platform) {
      Platform.IOS -> CompactScreenElements.buildForIos(tree, screenHeight = deviceHeight, screenWidth = deviceWidth)
      Platform.ANDROID -> CompactScreenElements.buildForAndroid(tree, screenHeight = deviceHeight)
      else -> null
    }
  }

  override val trailblazeNodeTree: TrailblazeNode? by lazy {
    val tree = stableTrailblazeNodeTree ?: return@lazy null
    compactElements?.applyRefsToTree(tree) ?: tree
  }

  override val viewHierarchyTextRepresentation: String? by lazy {
    compactElements?.buildTextRepresentation(foregroundAppId)
  }

  override val annotationElements: List<AnnotationElement>? by lazy {
    compactElements?.buildAnnotationElements()
  }

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
   *
   * When [annotationElements] is available (from the compact element list), uses those
   * to draw IDs that exactly match the `[nID]` refs in the text representation.
   * Falls back to the ViewHierarchyTreeNode-based approach for legacy paths.
   *
   * Applies the same scaling as [screenshotBytes] via [screenshotScalingConfig].
   */
  override val annotatedScreenshotBytes: ByteArray?
    get() {
      val bufferedImage = stableBufferedImage ?: return null

      // Create a copy of the buffered image for annotation (don't modify original)
      val imageForAnnotation = BufferedImage(
        bufferedImage.width,
        bufferedImage.height,
        bufferedImage.type
      )
      val graphics = imageForAnnotation.createGraphics()
      graphics.drawImage(bufferedImage, 0, 0, null)
      graphics.dispose()

      val canvas = HostCanvasSetOfMark(imageForAnnotation, deviceInfo)

      // Prefer annotation elements from the compact element list — these use the same
      // node IDs that appear in the text representation sent to the LLM.
      val annotations = annotationElements
      if (annotations != null && annotations.isNotEmpty()) {
        canvas.drawAnnotations(annotations)
      } else {
        // Fallback: derive elements from the ViewHierarchyTreeNode tree (legacy path).
        // These IDs come from relabelWithFreshIds() and match the fallback text path
        // in InnerLoopScreenAnalyzer.buildNodeDescription().
        val filtered = ViewHierarchyFilter.create(
          screenWidth = deviceWidth,
          screenHeight = deviceHeight,
          platform = deviceInfo.platform.toTrailblazeDevicePlatform(),
        ).filterInteractableViewHierarchyTreeNodes(viewHierarchy)
        val elementList = ViewHierarchyTreeNodeUtils.from(
          filtered,
          deviceInfo,
        )
        canvas.draw(elementList)
      }

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

    /**
     * Extracts the iOS app bundle identifier from the raw Maestro tree.
     *
     * The Maestro iOS driver maps `XCUIElement.identifier` → `attributes["resource-id"]`.
     * For `XCUIApplication`, the identifier IS the bundle ID (e.g. "com.example.myapp").
     * The tree root may be a 0x0 container with the app node as `root[0]`, so both are
     * checked. A bundle ID is distinguished from a plain accessibility identifier by the
     * presence of at least one dot and the absence of a colon (which would indicate an
     * Android-style `package:id/resource` format instead).
     */
    internal fun extractIosBundleId(root: TreeNode): String? {
      val candidates = listOf(root.attributes["resource-id"]) +
        root.children.map { it.attributes["resource-id"] }
      return candidates.firstOrNull { id ->
        !id.isNullOrBlank() && id.contains('.') && !id.contains(':')
      }
    }

    /**
     * Extracts the Android foreground app package from the raw Maestro tree.
     *
     * UiAutomator resource IDs use the format `"com.example.app:id/view_name"`.
     * We walk the tree looking for the first resource-id with a package prefix
     * (contains both '.' and ':') and extract the package portion.
     */
    internal fun extractAndroidPackageId(root: TreeNode): String? {
      fun findPackage(node: TreeNode): String? {
        val resId = node.attributes["resource-id"]
        if (!resId.isNullOrBlank() && ':' in resId && '.' in resId) {
          return resId.substringBefore(':')
        }
        for (child in node.children) {
          val result = findPackage(child)
          if (result != null) return result
        }
        return null
      }
      return findPackage(root)
    }

    internal sealed class StatusBarPosition {
      data class Found(val cx: Int, val cy: Int) : StatusBarPosition()
      data object NOT_FOUND : StatusBarPosition()
    }
  }
}
