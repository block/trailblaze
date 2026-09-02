package xyz.block.trailblaze.android.accessibility

import android.graphics.Bitmap
import kotlin.concurrent.thread
import xyz.block.trailblaze.AdbCommandUtil
import xyz.block.trailblaze.android.MaestroUiAutomatorXmlParser
import xyz.block.trailblaze.android.uiautomator.AndroidOnDeviceUiAutomatorScreenState
import xyz.block.trailblaze.api.AnnotationElement
import xyz.block.trailblaze.api.CaptureCoverage
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
import xyz.block.trailblaze.tracing.TraceSpanFrame
import xyz.block.trailblaze.tracing.TraceSpanLocal
import xyz.block.trailblaze.tracing.TrailblazeTracer
import xyz.block.trailblaze.util.Console
import xyz.block.trailblaze.utils.Ext.toViewHierarchyTreeNode

/**
 * Category for every span this file records. Matches the host side's screen-state category so a
 * profile can compare the two halves of the same capture by category rather than by span name.
 */
private const val SCREEN_STATE_TRACE_CAT = "screenState"

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
  /**
   * When `false`, skip the accessibility-tree walk and node-info traversal entirely. The
   * resulting [viewHierarchy] is an empty placeholder and [trailblazeNodeTree] is `null`,
   * but [screenshotBytes] is still captured. Default `true` preserves the atomic
   * (screenshot, tree) pair the recording flow relies on; only mirror-only callers (live
   * `/devices` viewer's frame loop) should pass `false`. See
   * [xyz.block.trailblaze.mcp.android.ondevice.rpc.GetScreenStateRequest.includeTree] for
   * the wire-level entry point.
   */
  private val includeTree: Boolean = true,
  /**
   * When false, skip the capture-time tree-stability gate ([TrailblazeAccessibilityService]'s
   * `awaitTreeStable` inside [TrailblazeAccessibilityService.captureMergedScreenTrees]) and
   * capture the tree as-is. For "immediate state" captures (post-action logging snapshots)
   * where the caller explicitly wants the un-settled UI, the gate only adds latency.
   */
  private val awaitStableTree: Boolean = true,
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
  override var captureCoverage: CaptureCoverage? = null
    private set

  init {
    val (displayWidth, displayHeight) =
      TrailblazeTracer.traceDetail("getScreenDimensions", SCREEN_STATE_TRACE_CAT) {
        TrailblazeAccessibilityService.getScreenDimensions()
      }
    deviceWidth = displayWidth
    deviceHeight = displayHeight

    // The fallback gets its own span because it is a different order of cost: the service answers
    // from a field it maintains off the event stream, while the fallback shells out to adb.
    currentActivity = TrailblazeTracer.traceDetail("getCurrentActivity", SCREEN_STATE_TRACE_CAT) {
      TrailblazeAccessibilityService.getCurrentActivity()
    } ?: TrailblazeTracer.traceDetail("getForegroundActivity.adb", SCREEN_STATE_TRACE_CAT) {
      AdbCommandUtil.getForegroundActivity()
    }

    // Mirror-only fast path: when the caller doesn't need the tree (live `/devices` viewer
    // frame loop), skip the accessibility-tree walk entirely. We still capture the screenshot
    // on the same thread (no parallelism win without a tree-build to overlap) — this drops
    // per-frame on-device cost from ~100-300 ms to ~30-60 ms.
    if (!includeTree) {
      viewHierarchy = ViewHierarchyTreeNode()
      _trailblazeNodeTree = null
      refsApplied = true // no tree → no refs to apply
      if (includeScreenshot) {
        try {
          _screenshotBytes = TrailblazeAccessibilityService.captureScreenshot()
            ?.let { bitmap -> encodeScreenshot(bitmap) }
            ?: ByteArray(0)
        } catch (e: Exception) {
          Console.log("⚠️ Mirror-fast-path screenshot capture failed: ${e.message}")
        }
      }
    } else {
    // Single-pass capture: the UI is already settled (caller guarantees via waitForSettled),
    // so we capture the tree and screenshot once without a consistency retry loop.
    //
    // Deliberately reads via the bound TrailblazeAccessibilityService rather than UiAutomation:
    // the UiAutomation path (briefly tried in #2866 to avoid screen-reader-detection
    // callbacks on apps that watch for accessibility query traffic) intermittently returned
    // only the system status/nav-bar windows on CI emulators, dropping the TYPE_APPLICATION
    // window entirely. If an app's screen-reader-detection logic blocks Trailblaze, switch
    // that test to the UiAutomator driver instead of moving the tree read back to UiAutomation.
    // Merge all contributing windows (active app window plus any dialog/popup/sub-panel
    // windows) into a single capture so secondary-window content is visible in both tree shapes.
    // Node recycling and per-window refresh happen inside captureMergedScreenTrees().
    val mergedTrees =
      TrailblazeAccessibilityService.captureMergedScreenTrees(awaitStable = awaitStableTree)
    captureCoverage = mergedTrees.captureCoverage

    // Capture screenshot in parallel with hierarchy building. UiAutomation.takeScreenshot()
    // is independent of AccessibilityNodeInfo traversal, and starting both concurrently also
    // improves temporal consistency between the visual and structural snapshots.
    // Thread.join() provides a happens-before guarantee for the write to _screenshotBytes.
    //
    // A span's parent is per-thread, and this thread has never seen the capture's span, so the
    // spans recorded over there would come out roots beside the capture rather than inside it.
    // Read the innermost open span here and adopt it there. The screenshot's spans then still
    // OVERLAP the tree-building spans below on the timeline, which is the point: that overlap is
    // what makes the parallelism visible instead of implied.
    val captureSpanId = TraceSpanLocal.get()?.spanId
    val screenshotThread = if (includeScreenshot) {
      thread(name = "tb-screenshot-capture") {
        if (captureSpanId != null) TraceSpanLocal.set(TraceSpanFrame(captureSpanId))
        try {
          _screenshotBytes = TrailblazeAccessibilityService.captureScreenshot()
            ?.let { bitmap -> encodeScreenshot(bitmap) }
            ?: ByteArray(0)
        } catch (e: Exception) {
          Console.log("⚠️ Parallel screenshot capture failed: ${e.message}")
        }
      }
    } else null

    foregroundAppId = mergedTrees.foregroundAppId

    // The join is in a `finally` because the screenshot thread outliving this constructor is worse
    // than a slow capture: it writes `_screenshotBytes` with no happens-before edge to whoever
    // reads them, and it records spans naming a parent span that has already closed.
    try {
      viewHierarchy = TrailblazeTracer.traceDetail("buildViewHierarchy", SCREEN_STATE_TRACE_CAT) {
        (mergedTrees.treeNode?.toViewHierarchyTreeNode()
            ?: ViewHierarchyTreeNode())
          .relabelWithFreshIds()
      }

      trailblazeNodeTree = TrailblazeTracer.traceDetail("buildTrailblazeNodeTree", SCREEN_STATE_TRACE_CAT) {
        val rawTree = mergedTrees.accessibilityNode?.toTrailblazeNode()
        if (includeAllElements) rawTree else rawTree?.filterImportantForAccessibility()
      }
    } finally {
      // Whatever is left of the screenshot the tree build did not manage to hide. On a fast capture
      // this is near zero; a long one says the screenshot, not the hierarchy, set the floor.
      TrailblazeTracer.traceDetail("awaitScreenshotThread", SCREEN_STATE_TRACE_CAT) {
        screenshotThread?.join()
      }
    }

    // Optional dual-tree capture for Maestro→accessibility migration. Sequential rather
    // than parallel with the accessibility tree above because both query through the
    // accessibility IPC channel and concurrent calls have caused ANR-style hangs on
    // resource-constrained emulators. The cost is tolerable (this path only runs with
    // `trailblaze.captureSecondaryTree=true` set, which is migration-only).
    if (captureSecondaryTree) {
      try {
        val xmlDump = TrailblazeTracer.traceDetail("dumpSecondaryUiAutomatorTree", SCREEN_STATE_TRACE_CAT) {
          AndroidOnDeviceUiAutomatorScreenState.dumpViewHierarchy()
        }
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
    } // end else (full tree path)
  }

  /**
   * Scales and PNG-encodes [bitmap], in its own span.
   *
   * Separate from the capture itself (spanned inside [TrailblazeAccessibilityService.captureScreenshot],
   * so every caller of that gets it) because the two halves fail for unrelated reasons: the capture
   * is an IPC round trip whose slow path is rate-limited, the encode is CPU on this thread and
   * scales with the screen.
   */
  private fun encodeScreenshot(bitmap: Bitmap): ByteArray =
    TrailblazeTracer.traceDetail("scaleAndEncodeScreenshot", SCREEN_STATE_TRACE_CAT) {
      bitmap.scaleAndEncode(screenshotScalingConfig)
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
