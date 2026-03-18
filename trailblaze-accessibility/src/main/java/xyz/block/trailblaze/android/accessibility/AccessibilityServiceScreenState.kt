package xyz.block.trailblaze.android.accessibility

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import maestro.DeviceInfo
import maestro.Platform
import xyz.block.trailblaze.AdbCommandUtil
import xyz.block.trailblaze.api.ScreenState
import xyz.block.trailblaze.api.TrailblazeNode
import xyz.block.trailblaze.api.ViewHierarchyTreeNode
import xyz.block.trailblaze.api.ViewHierarchyTreeNode.Companion.relabelWithFreshIds
import xyz.block.trailblaze.devices.TrailblazeDeviceClassifier
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.setofmark.android.AndroidBitmapUtils.toByteArray
import xyz.block.trailblaze.setofmark.android.AndroidCanvasSetOfMark
import xyz.block.trailblaze.utils.Ext.toViewHierarchyTreeNode
import xyz.block.trailblaze.viewhierarchy.ViewHierarchyCompactFormatter
import xyz.block.trailblaze.viewhierarchy.ViewHierarchyFilter
import xyz.block.trailblaze.viewhierarchy.ViewHierarchyTreeNodeUtils

/**
 * [ScreenState] using the [TrailblazeAccessibilityService].
 *
 * Captures the view hierarchy and screenshot in a single pass. Callers (e.g.,
 * [AccessibilityDeviceManager]) are responsible for ensuring the UI is settled before
 * constructing this object — the event-based [TrailblazeAccessibilityService.waitForSettled]
 * guarantees stability, making the old two-pass consistency check unnecessary.
 *
 * Screenshots are captured via [android.app.UiAutomation.takeScreenshot] (no rate limit)
 * rather than the accessibility service's native API (which enforces a 333ms minimum interval).
 */
class AccessibilityServiceScreenState(
  private val filterViewHierarchy: Boolean = false,
  private val setOfMarkEnabled: Boolean = true,
  private val includeScreenshot: Boolean = true,
  deviceClassifiers: List<TrailblazeDeviceClassifier> = emptyList(),
  private val includeOffscreen: Boolean = false,
  /**
   * When true (default), prunes nodes where [DriverNodeDetail.AndroidAccessibility.isImportantForAccessibility]
   * is false from [trailblazeNodeTree], promoting their children to preserve meaningful descendants.
   * Set to false to expose all structural/decorative nodes (e.g. for debugging).
   */
  private val filterImportantForAccessibility: Boolean = true,
) : ScreenState {

  override var deviceWidth: Int = -1
  override var deviceHeight: Int = -1
  override var viewHierarchyOriginal: ViewHierarchyTreeNode
  override var trailblazeNodeTree: TrailblazeNode? = null

  private var _screenshotBytes: ByteArray = ByteArray(0)
  private var foregroundAppId: String? = null
  private var currentActivity: String? = null

  init {
    val (displayWidth, displayHeight) = TrailblazeAccessibilityService.getScreenDimensions()
    deviceWidth = displayWidth
    deviceHeight = displayHeight

    currentActivity = AdbCommandUtil.getForegroundActivity()

    // Single-pass capture: the UI is already settled (caller guarantees via waitForSettled),
    // so we capture the tree and screenshot once without a consistency retry loop.
    val rootNodeInfo = TrailblazeAccessibilityService.getRootNodeInfo()
    try {
      // packageName must be read before recycle() invalidates the node
      foregroundAppId = rootNodeInfo?.packageName?.toString()

      viewHierarchyOriginal =
        (rootNodeInfo?.toTreeNode()?.toViewHierarchyTreeNode()
            ?: ViewHierarchyTreeNode())
          .relabelWithFreshIds()

      trailblazeNodeTree = rootNodeInfo?.toAccessibilityNode()?.toTrailblazeNode()
        ?.let { if (filterImportantForAccessibility) it.filterImportantForAccessibility() else it }
    } finally {
      rootNodeInfo?.recycle()
    }

    _screenshotBytes =
      if (includeScreenshot) {
        val bitmap = TrailblazeAccessibilityService.captureScreenshot()
        val bytes = bitmap?.toByteArray()
        bitmap?.recycle()
        bytes ?: ByteArray(0)
      } else {
        ByteArray(0)
      }
  }

  override val trailblazeDevicePlatform: TrailblazeDevicePlatform = TrailblazeDevicePlatform.ANDROID

  override val viewHierarchyTextRepresentation: String
    get() = ViewHierarchyCompactFormatter.format(
      root = viewHierarchy,
      platform = trailblazeDevicePlatform,
      screenWidth = deviceWidth,
      screenHeight = deviceHeight,
      foregroundAppId = foregroundAppId,
      currentActivity = currentActivity,
      deviceClassifiers = deviceClassifiers,
      includeOffscreen = includeOffscreen,
    )

  override val deviceClassifiers: List<TrailblazeDeviceClassifier> = deviceClassifiers

  override val viewHierarchy: ViewHierarchyTreeNode
    get() {
      if (!filterViewHierarchy) {
        return viewHierarchyOriginal
      }
      val viewHierarchyFilter =
        ViewHierarchyFilter.create(
          screenHeight = deviceHeight,
          screenWidth = deviceWidth,
          platform = TrailblazeDevicePlatform.ANDROID,
        )
      return viewHierarchyFilter.filterInteractableViewHierarchyTreeNodes(viewHierarchyOriginal)
    }

  override val screenshotBytes: ByteArray
    get() = _screenshotBytes

  override val annotatedScreenshotBytes: ByteArray
    get() {
      if (!setOfMarkEnabled) {
        return _screenshotBytes
      }
      if (_screenshotBytes.isEmpty()) {
        return _screenshotBytes
      }

      val bitmap =
        BitmapFactory.decodeByteArray(_screenshotBytes, 0, _screenshotBytes.size)
          ?: return _screenshotBytes

      val annotatedBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, true)
      // Original bitmap is no longer needed regardless of copy outcome
      bitmap.recycle()

      if (annotatedBitmap == null) {
        return _screenshotBytes
      }

      try {
        AndroidCanvasSetOfMark.drawSetOfMarkOnBitmap(
          originalScreenshotBitmap = annotatedBitmap,
          elements =
            ViewHierarchyTreeNodeUtils.from(
              viewHierarchy,
              DeviceInfo(
                platform = Platform.ANDROID,
                widthPixels = deviceWidth,
                heightPixels = deviceHeight,
                widthGrid = deviceWidth,
                heightGrid = deviceHeight,
              ),
            ),
          includeLabel = true,
          deviceWidth = deviceWidth,
          deviceHeight = deviceHeight,
        )

        val bytes = annotatedBitmap.toByteArray()
        annotatedBitmap.recycle()
        return bytes
      } catch (t: Throwable) {
        annotatedBitmap.recycle()
        throw t
      }
    }
}
