package xyz.block.trailblaze.android.accessibility

import kotlin.concurrent.thread
import xyz.block.trailblaze.AdbCommandUtil
import xyz.block.trailblaze.api.AnnotationElement
import xyz.block.trailblaze.api.CompactScreenElements
import xyz.block.trailblaze.api.ScreenState
import xyz.block.trailblaze.api.ScreenshotScalingConfig
import xyz.block.trailblaze.api.TrailblazeNode
import xyz.block.trailblaze.api.ViewHierarchyTreeNode
import xyz.block.trailblaze.api.ViewHierarchyTreeNode.Companion.relabelWithFreshIds
import xyz.block.trailblaze.devices.TrailblazeDeviceClassifier
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.setofmark.android.AndroidBitmapUtils
import xyz.block.trailblaze.setofmark.android.AndroidBitmapUtils.scaleAndEncode
import xyz.block.trailblaze.util.Console
import xyz.block.trailblaze.utils.Ext.toViewHierarchyTreeNode

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
  private val includeScreenshot: Boolean = true,
  deviceClassifiers: List<TrailblazeDeviceClassifier> = emptyList(),
  private val screenshotScalingConfig: ScreenshotScalingConfig = ScreenshotScalingConfig.ON_DEVICE,
) : ScreenState {

  override var deviceWidth: Int = -1
  override var deviceHeight: Int = -1
  override var viewHierarchy: ViewHierarchyTreeNode
  // Backing field for the tree. Refs are applied lazily via [ensureRefsApplied].
  private var _trailblazeNodeTree: TrailblazeNode? = null
  private var refsApplied = false

  override var trailblazeNodeTree: TrailblazeNode?
    get() {
      ensureRefsApplied()
      return _trailblazeNodeTree
    }
    set(value) {
      _trailblazeNodeTree = value
      refsApplied = false
    }

  private fun ensureRefsApplied() {
    if (!refsApplied && _trailblazeNodeTree != null) {
      compactElements
      refsApplied = true
    }
  }

  private var _screenshotBytes: ByteArray = ByteArray(0)
  private var foregroundAppId: String? = null
  private var currentActivity: String? = null

  init {
    val (displayWidth, displayHeight) = TrailblazeAccessibilityService.getScreenDimensions()
    deviceWidth = displayWidth
    deviceHeight = displayHeight

    currentActivity = TrailblazeAccessibilityService.getCurrentActivity()
      ?: AdbCommandUtil.getForegroundActivity()

    // Single-pass capture: the UI is already settled (caller guarantees via waitForSettled),
    // so we capture the tree and screenshot once without a consistency retry loop.
    val rootNodeInfo = TrailblazeAccessibilityService.getRootNodeInfo()

    // Capture screenshot in parallel with hierarchy building. UiAutomation.takeScreenshot()
    // is independent of AccessibilityNodeInfo traversal, and starting both concurrently also
    // improves temporal consistency between the visual and structural snapshots.
    // Thread.join() provides a happens-before guarantee for the write to _screenshotBytes.
    val screenshotThread = if (includeScreenshot) {
      thread(name = "tb-screenshot-capture") {
        try {
          _screenshotBytes = TrailblazeAccessibilityService.captureScreenshot()
            ?.scaleAndEncode(screenshotScalingConfig)
            ?: ByteArray(0)
        } catch (e: Exception) {
          Console.log("⚠️ Parallel screenshot capture failed: ${e.message}")
        }
      }
    } else null

    try {
      // packageName must be read before recycle() invalidates the node
      foregroundAppId = rootNodeInfo?.packageName?.toString()

      viewHierarchy =
        (rootNodeInfo?.toTreeNode()?.toViewHierarchyTreeNode()
            ?: ViewHierarchyTreeNode())
          .relabelWithFreshIds()

      trailblazeNodeTree = rootNodeInfo?.toAccessibilityNode()?.toTrailblazeNode()
        ?.filterImportantForAccessibility()
    } finally {
      rootNodeInfo?.recycle()
    }

    screenshotThread?.join()
  }

  override val trailblazeDevicePlatform: TrailblazeDevicePlatform = TrailblazeDevicePlatform.ANDROID

  /** Cached compact elements result — shared between text representation and annotation elements. */
  private val compactElements: CompactScreenElements? by lazy {
    val tree = _trailblazeNodeTree ?: return@lazy null
    val result = CompactScreenElements.buildForAndroid(tree, screenHeight = deviceHeight)
    // Annotate tree nodes with their stable hash refs for debugging and inspector
    _trailblazeNodeTree = result.applyRefsToTree(tree)
    result
  }

  override val viewHierarchyTextRepresentation: String? by lazy {
    compactElements?.buildTextRepresentation(foregroundAppId, currentActivity)
  }

  override val annotationElements: List<AnnotationElement>? by lazy {
    compactElements?.buildAnnotationElements()
  }

  override val deviceClassifiers: List<TrailblazeDeviceClassifier> = deviceClassifiers

  override val screenshotBytes: ByteArray
    get() = _screenshotBytes

  override val annotatedScreenshotBytes: ByteArray
    get() {
      return AndroidBitmapUtils.annotateScreenshotBytes(
        screenshotBytes = _screenshotBytes,
        config = screenshotScalingConfig,
        viewHierarchy = viewHierarchy,
        deviceWidth = deviceWidth,
        deviceHeight = deviceHeight,
        annotationElements = annotationElements,
        oomContext = "AccessibilityServiceScreenState.annotatedScreenshotBytes",
      )
    }
}
