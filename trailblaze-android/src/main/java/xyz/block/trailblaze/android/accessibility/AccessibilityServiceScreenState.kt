package xyz.block.trailblaze.android.accessibility

import android.graphics.Bitmap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
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
 * How long a deferred capture will wait for its screenshot thread to actually reach the frame grab
 * before giving up on the ordering barrier. This is thread scheduling, not the grab itself, so a
 * healthy device clears it in well under a millisecond; the bound exists so a pathological device
 * degrades to a slightly skewed pair instead of hanging the capture.
 */
private const val SCREENSHOT_REQUEST_BARRIER_MS = 500L

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
  /**
   * When true, the screenshot thread is NOT joined before this constructor returns — the first read
   * of [screenshotBytes] joins it instead. Only the WAIT moves: the request is still issued at the
   * same instant, immediately after the tree reads and before the caller dispatches its gesture, so
   * the frame this capture pairs with its tree is the same frame it would have been.
   */
  private val asyncScreenshotJoin: Boolean = false,
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

  /** Raw capture awaiting its first [screenshotBytes] read; null once encoded or never captured. */
  private var _screenshotBitmap: Bitmap? = null

  /**
   * The innermost span open on the thread that constructed this capture.
   *
   * A span's parent is per-thread, so any work this capture hands to another thread — the parallel
   * screenshot, and the encode deferred behind [screenshotBytes] — would record as a root beside
   * the capture rather than inside it. Both re-install this id before they trace, so their cost
   * stays attributed to the capture that caused it no matter who runs it or when.
   */
  private val captureSpanId: String? = TraceSpanLocal.get()?.spanId
  private var foregroundAppId: String? = null
  private var currentActivity: String? = null

  /**
   * Pairing telemetry, on the device's wall clock. The screenshot and the hierarchy are handed to
   * the model as one exhibit, so how far apart in time they were taken is the first thing any
   * change to this path has to be judged on — ahead of how fast it is. [logPairSkew] is what makes
   * that checkable per capture rather than argued from the code.
   */
  @Volatile
  var treeReadEndMs: Long = 0L
    private set

  @Volatile
  var shotRequestMs: Long = 0L
    private set

  @Volatile
  var shotCompleteMs: Long = 0L
    private set

  /** Set while a screenshot thread is outstanding; joined by [awaitScreenshot]. */
  @Volatile
  private var pendingScreenshotThread: Thread? = null

  /**
   * Counted down by the screenshot thread immediately before it grabs the frame, so the capture can
   * wait for the REQUEST to be issued without waiting for the frame to arrive.
   */
  @Volatile
  private var screenshotRequested: CountDownLatch? = null

  @Volatile
  private var pairSkewLogged: Boolean = false
  override var captureCoverage: CaptureCoverage? = null
    private set

  /**
   * Nodes this capture asked the app for and did not get back while building the tree exposed as
   * [trailblazeNodeTree] — the accessibility projection, and the only tree any consumer of this
   * signal reads. Deliberately NOT the capture's aggregate: the sibling Maestro walk fetches
   * independently and can lose a node this one got, and a caller told "partial" about a tree that
   * is whole refuses to conclude absence it is entitled to conclude. See
   * [TrailblazeAccessibilityService.MergedScreenTrees.droppedFetchesForAccessibilityNode].
   *
   * Stays null on the mirror-only fast path below, which builds no tree at all — there is nothing
   * for a completeness verdict to describe there, and "unknown" is the honest answer.
   */
  override var droppedNodeFetches: Int? = null
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
    // The screenshot is taken AFTER every accessibility read finishes, never alongside them. The
    // two are handed to the model as one exhibit — the element comparator puts the hierarchy JSON
    // and the annotated screenshot in the same prompt — so they have to describe the same moment.
    // Starting the screenshot the instant the stability gate released (tried, and reverted here)
    // put the image a few hundred milliseconds ahead of the tree, and a prompt whose picture and
    // hierarchy disagree fails element lookups that otherwise never flake. It still overlaps the
    // tree-to-node conversion below, which is pure CPU over already-captured nodes.
    //
    // Only the capture happens here; the scale + encode is deferred to the first read of
    // [screenshotBytes], which for the action log is off the critical path. It was the single
    // largest slice of a logging capture and nothing on the device needs the bytes before the
    // next action starts.
    //
    // The screenshot thread adopts [captureSpanId] so its spans land under the capture rather than
    // beside it. Thread.join() provides the happens-before edge for the write to
    // _screenshotBitmap.
    var screenshotThread: Thread? = null

    // The join is in a `finally` because the screenshot thread outliving this constructor is worse
    // than a slow capture: it writes `_screenshotBitmap` with no happens-before edge to whoever
    // reads it, and it records spans naming a parent span that has already closed. Everything that
    // starts that thread, or that can throw once it is running, is inside the try.
    try {
      val mergedTrees =
        TrailblazeAccessibilityService.captureMergedScreenTrees(awaitStable = awaitStableTree)
      captureCoverage = mergedTrees.captureCoverage
      droppedNodeFetches = mergedTrees.droppedFetchesForAccessibilityNode
      foregroundAppId = mergedTrees.foregroundAppId

      treeReadEndMs = System.currentTimeMillis()

      if (includeScreenshot) {
        val requested = CountDownLatch(1)
        screenshotRequested = requested
        screenshotThread = thread(name = "tb-screenshot-capture") {
          if (captureSpanId != null) TraceSpanLocal.set(TraceSpanFrame(captureSpanId))
          shotRequestMs = System.currentTimeMillis()
          requested.countDown()
          try {
            _screenshotBitmap = TrailblazeAccessibilityService.captureScreenshot()
          } catch (e: Exception) {
            Console.log("⚠️ Parallel screenshot capture failed: ${e.message}")
          }
          shotCompleteMs = System.currentTimeMillis()
        }
        pendingScreenshotThread = screenshotThread
      }

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
      // Whatever is left of the screenshot the tree-to-node conversion did not manage to hide. On a
      // fast capture this is small; a long one says the screenshot, not the tree, set the floor.
      //
      // With [asyncScreenshotJoin] this wait is what moves: the thread is left running and the
      // first read of [screenshotBytes] joins it (see [awaitScreenshot]). The request was already
      // issued above, so the frame is unchanged — only who pays for waiting on it changes.
      if (asyncScreenshotJoin) {
        // Only the WAIT for the frame moves off this capture's path. The REQUEST still has to be
        // issued before the constructor returns, because the caller dispatches its gesture the
        // moment it does — a thread that had not yet been scheduled would grab a POST-action frame
        // and pair it with this pre-action tree. Waiting for the thread to reach the grab is
        // scheduling latency, not the grab.
        awaitScreenshotRequested()
        screenshotThread = null
      }
      TrailblazeTracer.traceDetail("awaitScreenshotThread", SCREEN_STATE_TRACE_CAT) {
        screenshotThread?.join()
      }
      if (!asyncScreenshotJoin) {
        pendingScreenshotThread = null
        logPairSkew()
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

  /**
   * Encoded on first read (thread-safe via `lazy`), so a capture whose bytes are only ever read by
   * the asynchronous action logger pays for the encode there instead of before the next action.
   *
   * The encode runs on whichever thread reads first, which is not the one that opened the capture's
   * span — so [captureSpanId] is re-installed around it and restored after. Without that the
   * `scaleAndEncodeScreenshot` span would be parented to whatever unrelated work that thread
   * happens to be tracing, and the encode's cost would be attributed to the wrong action in exactly
   * the profiles this deferral is judged by.
   */
  private val encodedScreenshot: ByteArray by lazy {
    awaitScreenshot()
    val bitmap = _screenshotBitmap ?: return@lazy _screenshotBytes
    _screenshotBitmap = null
    val previousFrame = TraceSpanLocal.get()
    TraceSpanLocal.set(TraceSpanFrame(captureSpanId))
    try {
      encodeScreenshot(bitmap)
    } finally {
      TraceSpanLocal.set(previousFrame)
    }
  }

  /**
   * Blocks until the screenshot thread has reached its frame grab, which is the ordering barrier
   * the deferred join depends on. Bounded by [SCREENSHOT_REQUEST_BARRIER_MS] and logged when it
   * expires, so a pair that could be skewed says so rather than being assumed sound.
   */
  private fun awaitScreenshotRequested() {
    val requested = screenshotRequested ?: return
    if (!requested.await(SCREENSHOT_REQUEST_BARRIER_MS, TimeUnit.MILLISECONDS)) {
      Console.log(
        "⚠️ [pair-skew] screenshot request not issued within ${SCREENSHOT_REQUEST_BARRIER_MS}ms; " +
          "the deferred frame may lag this tree",
      )
    }
  }

  /**
   * Joins an outstanding [asyncScreenshotJoin] screenshot thread. `Thread.join()` is what gives the
   * happens-before edge for the thread's writes to `_screenshotBitmap` / `_screenshotBytes`, so
   * every read of those goes through here first. Idempotent and cheap once joined.
   *
   * A capture whose bytes are never read never joins — the thread simply finishes on its own. That
   * is bounded in practice because `AccessibilityTrailRunner.logAsync` eagerly touches
   * [screenshotBytes] on a background dispatcher, which is the same thing that bounds the bitmap's
   * lifetime today.
   */
  @Synchronized
  private fun awaitScreenshot() {
    val thread = pendingScreenshotThread ?: return
    thread.join()
    pendingScreenshotThread = null
    logPairSkew()
  }

  /**
   * The pair, in one line: when the tree reads finished and when the frame was actually grabbed.
   * `doneSkewMs` is the number this path is judged on — how far the image is from the moment the
   * hierarchy describes. Logged exactly once per capture, from whichever site joined the thread,
   * so moving the join cannot quietly widen the pair without saying so.
   */
  private fun logPairSkew() {
    if (pairSkewLogged || !includeScreenshot) return
    pairSkewLogged = true
    Console.log(
      "[pair-skew] treeEndMs=$treeReadEndMs shotReqMs=$shotRequestMs shotDoneMs=$shotCompleteMs " +
        "reqSkewMs=${shotRequestMs - treeReadEndMs} doneSkewMs=${shotCompleteMs - treeReadEndMs}",
    )
  }

  override val screenshotBytes: ByteArray
    get() = encodedScreenshot

  override val annotatedScreenshotBytes: ByteArray
    get() {
      return AndroidBitmapUtils.annotateScreenshotBytes(
        screenshotBytes = screenshotBytes,
        config = screenshotScalingConfig,
        viewHierarchy = viewHierarchy,
        deviceWidth = deviceWidth,
        deviceHeight = deviceHeight,
        annotationElements = annotationElements,
        oomContext = "AccessibilityServiceScreenState.annotatedScreenshotBytes",
      )
    }
}
