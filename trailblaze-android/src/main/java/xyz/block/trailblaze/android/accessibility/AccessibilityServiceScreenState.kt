package xyz.block.trailblaze.android.accessibility

import kotlin.concurrent.thread
import xyz.block.trailblaze.AdbCommandUtil
import xyz.block.trailblaze.android.MaestroUiAutomatorXmlParser
import xyz.block.trailblaze.android.uiautomator.AndroidOnDeviceUiAutomatorScreenState
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
  /**
   * When true, skip [filterImportantForAccessibility] so the resulting tree contains every
   * node the accessibility framework reported — even those with
   * `isImportantForAccessibility = false`. Used by `--all` / [SnapshotDetail.ALL_ELEMENTS]
   * callers that are willing to pay the larger response size for full fidelity.
   */
  private val includeAllElements: Boolean = false,
  /**
   * When true, after the accessibility-derived `viewHierarchy` is built, ALSO dump the
   * UiAutomator window hierarchy (`UiDevice.dumpWindowHierarchy`) and use the resulting
   * tree as `viewHierarchy` instead. The accessibility-derived `trailblazeNodeTree` is
   * unaffected — both are captured side-by-side.
   *
   * Used by the deterministic Maestro→accessibility selector migration so legacy Maestro
   * selectors can be resolved against the EXACT tree the legacy runtime saw, rather than
   * against the accessibility-shape projection. Off by default — UiAutomator dumps add
   * ≈ a few hundred ms per capture (capped at 30s by the underlying `dumpWindowHierarchy`
   * timeout) and roughly double session-log size, neither of which we want absent a
   * specific need.
   */
  private val captureSecondaryTree: Boolean = false,
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

      val rawTree = rootNodeInfo?.toAccessibilityNode()?.toTrailblazeNode()
      trailblazeNodeTree =
        if (includeAllElements) rawTree else rawTree?.filterImportantForAccessibility()
    } finally {
      rootNodeInfo?.recycle()
    }

    screenshotThread?.join()

    // Optional dual-tree capture for Maestro→accessibility migration. Sequential rather
    // than parallel with the accessibility tree above because both query through the
    // accessibility IPC channel and concurrent calls have caused ANR-style hangs on
    // resource-constrained emulators. The cost is tolerable (this path only runs with
    // `trailblaze.captureSecondaryTree=true` set, which is migration-only).
    if (captureSecondaryTree) {
      try {
        val xmlDump = AndroidOnDeviceUiAutomatorScreenState.dumpViewHierarchy()
        val maestroTree =
          MaestroUiAutomatorXmlParser
            .getUiAutomatorViewHierarchyFromViewHierarchyAsMaestroTreeNodes(
              viewHiearchyXml = xmlDump,
              excludeKeyboardElements = false,
            )
        val dualVh = maestroTree.toViewHierarchyTreeNode()?.relabelWithFreshIds()
        if (dualVh != null) {
          // Overwrite the accessibility-derived projection. The accessibility tree is
          // already preserved as `trailblazeNodeTree` above, so we lose nothing by
          // replacing the Maestro-shape projection with the true UiAutomator tree.
          viewHierarchy = dualVh
          Console.log(
            "[dual-tree] viewHierarchy replaced with UiAutomator dump " +
              "(accessibility-derived projection discarded)",
          )
        } else {
          Console.log(
            "[dual-tree] UiAutomator dump returned null tree; keeping accessibility-derived viewHierarchy",
          )
        }
      } catch (e: Exception) {
        // Don't let a dual-tree-capture failure abort the screen-state build — the
        // primary accessibility path is intact. Log loudly so a CI run with this flag
        // on but no dumps surfaces the failure.
        Console.log(
          "[dual-tree] capture failed; keeping accessibility-derived viewHierarchy: ${e.message}",
        )
      }
    }
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
