package xyz.block.trailblaze.android.uiautomator

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.os.Build
import android.view.accessibility.AccessibilityNodeInfo
import maestro.DeviceInfo
import maestro.device.Platform
import xyz.block.trailblaze.AdbCommandUtil
import xyz.block.trailblaze.InstrumentationUtil.withUiAutomation
import xyz.block.trailblaze.InstrumentationUtil.withUiDevice
import xyz.block.trailblaze.android.MemoryDiagnostics
import xyz.block.trailblaze.android.MaestroUiAutomatorXmlParser
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
import xyz.block.trailblaze.setofmark.android.AndroidBitmapUtils.toByteArray
import xyz.block.trailblaze.setofmark.android.AndroidCanvasSetOfMark
import xyz.block.trailblaze.tracing.TrailblazeTracer.traceRecorder
import xyz.block.trailblaze.util.Console
import xyz.block.trailblaze.utils.Ext.toViewHierarchyTreeNode
import xyz.block.trailblaze.viewhierarchy.ViewHierarchyTreeNodeUtils
import xyz.block.trailblaze.viewmatcher.matching.toTrailblazeNodeAndroidMaestro
import java.io.ByteArrayOutputStream
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Snapshot in time of what the screen has in it.
 *
 * Memory Optimization:
 * - Only stores the clean (non-annotated) screenshot in memory
 * - Set-of-mark annotations are generated on-demand
 */
class AndroidOnDeviceUiAutomatorScreenState(
  private val screenshotScalingConfig: ScreenshotScalingConfig = ScreenshotScalingConfig.ON_DEVICE,
  maxAttempts: Int = 1,
  includeScreenshot: Boolean = true,
  deviceClassifiers: List<TrailblazeDeviceClassifier> = emptyList(),
  /**
   * When `false`, skip the `dumpViewHierarchy()` XML capture and stabilization loop. The
   * resulting [viewHierarchy] is an empty placeholder and [trailblazeNodeTree] is `null`,
   * but [screenshotBytes] is still captured. Mirror-only callers (live `/devices` viewer
   * frame loop) pass `false`; every other caller takes the default `true` and gets the
   * atomic (screenshot, tree) pair the recording flow depends on.
   */
  includeTree: Boolean = true,
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

  /**
   * Ensures refs are applied to the tree. Refs are generated during compact element list
   * creation and applied back to the tree. Without this, tools like [TapTrailblazeTool]
   * that resolve elements by ref will fail because all ref fields are null.
   */
  private fun ensureRefsApplied() {
    if (!refsApplied && _trailblazeNodeTree != null) {
      // Accessing compactElements triggers ref generation and application
      compactElements
      refsApplied = true
    }
  }
  private val foregroundAppId: String?
  private val currentActivity: String?

  // Store only the clean screenshot bytes (compressed)
  private var _screenshotBytes: ByteArray = ByteArray(0)

  init {
    val (displayWidth, displayHeight, currentPackage) = withUiDevice {
      Triple(displayWidth, displayHeight, currentPackageName)
    }
    deviceWidth = displayWidth
    deviceHeight = displayHeight
    foregroundAppId = currentPackage
    currentActivity = AdbCommandUtil.getForegroundActivity()

    // Mirror-only fast path: skip both `dumpViewHierarchy()` XML captures (each carries a
    // 30s IPC timeout) and the stabilization comparison loop. Just grab a screenshot. Drops
    // per-frame on-device cost from ~150-400 ms to ~30-80 ms. Kotlin doesn't allow early
    // `return` from init blocks, so we structure as if/else with all field assignments inside.
    if (!includeTree) {
      viewHierarchy = ViewHierarchyTreeNode()
      _screenshotBytes = if (includeScreenshot) {
        getScreenshot(viewHierarchy = null, screenshotScalingConfig) ?: ByteArray(0)
      } else {
        ByteArray(0)
      }
      _trailblazeNodeTree = null
      refsApplied = true
    } else {

    var matched = false
    var attempts = 0
    var lastViewHierarchyOriginal: ViewHierarchyTreeNode? = null
    var lastMaestroTree: maestro.TreeNode? = null
    var lastScreenshotBytes: ByteArray? = null
    // Cap total stabilization time: each iteration calls dumpViewHierarchy() twice
    // (each with its own 30s IPC timeout). Without this cap, slow devices can spin
    // for minutes and exhaust the enclosing test's JUnit time budget.
    val initStartMs = System.currentTimeMillis()
    val initBudgetMs = 60_000L

    try {
      while (!matched && attempts < maxAttempts) {
        if (System.currentTimeMillis() - initStartMs > initBudgetMs) {
          throw RuntimeException(
            "AndroidOnDeviceUiAutomatorScreenState init exceeded 60s budget after $attempts " +
              "stabilization attempts — view hierarchy did not stabilize. Device may be slow or unhealthy."
          )
        }
        // Parse via Maestro TreeNode so we can derive both VH and TrailblazeNode
        val xmlDump = dumpViewHierarchy()
        val maestroTree =
          MaestroUiAutomatorXmlParser.getUiAutomatorViewHierarchyFromViewHierarchyAsMaestroTreeNodes(
            viewHiearchyXml = xmlDump,
            excludeKeyboardElements = false,
          )
        val vh1Original = maestroTree.toViewHierarchyTreeNode()!!.relabelWithFreshIds()

        val bytes = if (includeScreenshot) {
          // Use original hierarchy for screenshot capture (filtering happens lazily later)
          getScreenshot(vh1Original, screenshotScalingConfig)
        } else {
          null
        }

        val vh2Original =
          MaestroUiAutomatorXmlParser.getUiAutomatorViewHierarchyAsSerializableTreeNodes(
            xmlHierarchy = dumpViewHierarchy(),
            excludeKeyboardElements = false,
          )

        lastViewHierarchyOriginal = vh1Original
        lastMaestroTree = maestroTree
        lastScreenshotBytes = bytes

        // Create a copy of the view hierarchies with the node ids all set to 0 and then compare
        val vh1Zeroed = vh1Original.copy(nodeId = 0)
        val vh2Zeroed = vh2Original.copy(nodeId = 0)

        if (vh1Zeroed == vh2Zeroed) {
          matched = true
        } else {
          attempts++
          if (attempts < maxAttempts) {
            Thread.sleep((attempts * 100).toLong())
          }
        }
      }
    } catch (e: OutOfMemoryError) {
      MemoryDiagnostics.dumpOnOom(e, "AndroidOnDeviceUiAutomatorScreenState.init")
      throw e
    }

    // Compose semantics-collapse recovery (UiAutomator/instrumentation path only) — see
    // [recoverCollapsedComposeTree] for the full rationale. Shares the enclosing init's 60s
    // budget (the recovery deadline is the sooner of its own cap and the init deadline) so a
    // capture that already spent most of initBudgetMs stabilizing can't be pushed past the 60s
    // init cap by recovery. The screenshot is untouched — the content renders fine; only its
    // semantics export lagged.
    lastViewHierarchyOriginal?.let { current ->
      val recoveryDeadlineMs = minOf(
        System.currentTimeMillis() + COMPOSE_COLLAPSE_RECOVERY_BUDGET_MS,
        initStartMs + initBudgetMs,
      )
      recoverCollapsedComposeTree(current, deviceWidth, deviceHeight, recoveryDeadlineMs)?.let { (vh, tree) ->
        lastViewHierarchyOriginal = vh
        lastMaestroTree = tree
      }
    }

    // Ensure these are set after the loop
    viewHierarchy =
      lastViewHierarchyOriginal ?: throw IllegalStateException("Failed to get view hierarchy")
    _screenshotBytes = lastScreenshotBytes ?: ByteArray(0)

    // Populate trailblazeNodeTree with the UiAutomator-derived Maestro tree —
    // the canonical shape for this driver. Migration capture lives separately
    // (see MigrationTreeCapture) and rides along the wire response without
    // mutating the primary tree shape that runtime tools and reports rely on.
    _trailblazeNodeTree = lastMaestroTree?.toTrailblazeNodeAndroidMaestro()
    } // end else (full tree path)
  }

  override val trailblazeDevicePlatform: TrailblazeDevicePlatform = TrailblazeDevicePlatform.ANDROID

  /** Cached compact elements — shared between text representation and annotation elements. */
  private val compactElements: CompactScreenElements? by lazy {
    val tree = _trailblazeNodeTree ?: return@lazy null
    val result = CompactScreenElements.buildForAndroid(tree, screenHeight = deviceHeight)
    _trailblazeNodeTree = result.applyRefsToTree(tree)
    result
  }

  override val viewHierarchyTextRepresentation: String? by lazy {
    compactElements?.buildTextRepresentation(foregroundAppId, currentActivity)
  }

  override val annotationElements: List<AnnotationElement>? by lazy {
    compactElements?.buildAnnotationElements()
  }

  override val pageContextSummary: String?
    get() = buildList {
      foregroundAppId?.let { add("App: $it") }
      currentActivity?.let { add("Activity: $it") }
    }.takeIf { it.isNotEmpty() }?.joinToString("\n")

  override val deviceClassifiers: List<TrailblazeDeviceClassifier> = deviceClassifiers

  /**
   * Returns the clean screenshot without any annotations.
   * This is always stored in memory and used for logging and snapshots.
   */
  override val screenshotBytes: ByteArray
    get() = _screenshotBytes

  /**
   * Returns screenshot bytes with set-of-mark annotations applied.
   * Generates annotations on-demand without caching - used only for LLM requests.
   *
   * Decodes the stored screenshot bytes back to a bitmap, applies annotations, then re-encodes.
   * This avoids storing the uncompressed bitmap in memory.
   */
  override val annotatedScreenshotBytes: ByteArray
    get() {
      return AndroidBitmapUtils.annotateScreenshotBytes(
        screenshotBytes = _screenshotBytes,
        config = screenshotScalingConfig,
        viewHierarchy = viewHierarchy,
        deviceWidth = deviceWidth,
        deviceHeight = deviceHeight,
        annotationElements = annotationElements,
        oomContext = "AndroidOnDeviceUiAutomatorScreenState.annotatedScreenshotBytes",
      )
    }

  companion object {

    /**
     * Max refresh+settle re-dump attempts when a collapsed Compose content pane is detected.
     * A single refresh resolves the common stale-cache case; the extra attempts cover a
     * semantics export that is still propagating. Bounded so a genuinely unrecoverable
     * (app-side) collapse can't spin.
     */
    private const val COMPOSE_COLLAPSE_RECOVERY_ATTEMPTS = 3

    /**
     * Wall-clock ceiling for the whole collapse-recovery loop. Guards the pathological case
     * where each accessibility-tree re-dump rides its own 30s IPC timeout on a wedged device —
     * without this, three timed-out dumps could add 90s to a single capture.
     */
    private const val COMPOSE_COLLAPSE_RECOVERY_BUDGET_MS = 15_000L

    /**
     * Compose semantics-collapse recovery for the UiAutomator/instrumentation capture path.
     *
     * The UiAutomator dump reads UiAutomation's *cached* AccessibilityNodeInfo tree without
     * refreshing it. When a full-screen Compose content pane (ComposeView) exports its
     * accessibility semantics *after* UiAutomation cached the pane's pre-semantics (childless)
     * node, the dump serves that stale node: the whole main screen shows up as a single opaque
     * View while sibling native/Compose views render normally — breaking every selector that
     * targets the main content. The accessibility-driver capture never hits this because it
     * refresh()es every node (busting the cache).
     *
     * The stabilization loop in [init] cannot catch this: it accepts once two dumps *match* (the
     * screen stopped changing), but a collapsed surface is perfectly stable — it stays collapsed
     * for tens of seconds. Stability is not completeness, so completeness is checked here.
     *
     * Retries (refresh + settle) until the surface is no longer collapsed or [deadlineMs] passes,
     * adopting a re-dump only if it is un-collapsed AND not smaller than [current] — a genuine
     * recovery only ADDS the missing subtree, so a degenerate/truncated dump (a timed-out walk, or
     * `visibleOnly` filtering out the window root) is rejected in favor of [current], which at
     * least retains the nav bar.
     *
     * @return the recovered (viewHierarchy, maestroTree) pair to adopt, or null if [current]
     *   wasn't collapsed or couldn't be recovered within the budget. The unrecoverable case is
     *   logged loudly and attributably (rather than silently returning a one-node screen) so an
     *   app-side surface that genuinely never exports semantics is debuggable.
     */
    private fun recoverCollapsedComposeTree(
      current: ViewHierarchyTreeNode,
      deviceWidth: Int,
      deviceHeight: Int,
      deadlineMs: Long,
    ): Pair<ViewHierarchyTreeNode, maestro.TreeNode>? {
      val collapsedNode =
        ComposeSemanticsCollapseDetector.detectCollapsedComposeSurface(current, deviceWidth, deviceHeight)
          ?: return null
      Console.log(
        "[compose-collapse] Detected collapsed ComposeView (${collapsedNode.resourceId ?: "no-id"}); " +
          "recovering via refresh()ing accessibility-tree walk (up to $COMPOSE_COLLAPSE_RECOVERY_ATTEMPTS attempts).",
      )
      val originalSize = current.aggregate().size
      var attempt = 0
      while (attempt < COMPOSE_COLLAPSE_RECOVERY_ATTEMPTS && System.currentTimeMillis() < deadlineMs) {
        attempt++
        // Let any in-flight recomposition / semantics export settle before re-reading.
        // waitForIdle() returns as soon as the accessibility-event stream is quiet — not a sleep.
        runCatching { withUiDevice { waitForIdle() } }
        // visibleOnly=false so the walk reaches nodes inside invisible ViewFactoryHolder containers.
        // A ComposeView whose host Compose AndroidView is transiently invisible (e.g. during a
        // fragment transition or when am instrument suppressed accessibility at startup) shows up
        // collapsed in dumpWindowHierarchy() but its parent ViewFactoryHolder has
        // isVisibleToUser=false in the accessibility tree. With visibleOnly=true the recovery
        // would skip that ViewFactoryHolder and never call refresh() on the ComposeView inside —
        // so the stale-cache bust never happens and every attempt reports "still collapsed".
        // visibleOnly=false matches the sparse-dump fallback's intent: walk through invisible
        // containers to reach and refresh() the actual collapsed surface.
        val refreshedXml = dumpViewHierarchyFromAccessibilityTree(visibleOnly = false) ?: continue
        val refreshedMaestro =
          MaestroUiAutomatorXmlParser.getUiAutomatorViewHierarchyFromViewHierarchyAsMaestroTreeNodes(
            viewHiearchyXml = refreshedXml,
            excludeKeyboardElements = false,
          )
        val refreshedVh = refreshedMaestro.toViewHierarchyTreeNode()?.relabelWithFreshIds() ?: continue
        val stillCollapsed =
          ComposeSemanticsCollapseDetector.detectCollapsedComposeSurface(refreshedVh, deviceWidth, deviceHeight)
        when {
          stillCollapsed != null ->
            Console.log("[compose-collapse] Attempt $attempt still collapsed; retrying.")
          refreshedVh.aggregate().size < originalSize ->
            Console.log("[compose-collapse] Attempt $attempt re-dump un-collapsed but smaller than original; rejecting, retrying.")
          else -> {
            Console.log("[compose-collapse] Recovered on attempt $attempt: ComposeView semantics present.")
            return refreshedVh to refreshedMaestro
          }
        }
      }
      Console.log(
        "[compose-collapse] UNRECOVERED after $attempt attempt(s): main-content ComposeView " +
          "(${collapsedNode.resourceId ?: "no-id"}) still exports no semantics. Returning best-effort " +
          "capture; selectors against the main content will not resolve. Likely a slow or app-side " +
          "semantics export (e.g. a legacy View-hosted Compose pane), not a stale-cache miss.",
      )
      return null
    }

    private inline fun <T> traceOnDeviceUiAutomatorScreenState(name: String, block: () -> T): T = traceRecorder.trace(name, "OnDeviceUiAutomatorScreenState", emptyMap(), block)

    fun dumpViewHierarchy(): String = traceOnDeviceUiAutomatorScreenState("dumpViewHierarchy") {
      // dumpWindowHierarchy traverses the accessibility tree via IPC and can deadlock
      // on slow or resource-constrained emulators (AccessibilityInteractionClient.findAccessibilityNodeInfoByAccessibilityId
      // hangs in Binder IPC). Run with a 30s timeout so callers fail fast rather than
      // hanging until the JUnit @Rule kills the test after 5 minutes.
      val executor = Executors.newSingleThreadExecutor()
      val future = executor.submit(Callable {
        ByteArrayOutputStream().use { outputStream ->
          withUiDevice {
            setCompressedLayoutHierarchy(false)
            dumpWindowHierarchy(outputStream)
          }
          outputStream.toString()
        }
      })
      val standardDump = try {
        future.get(30, TimeUnit.SECONDS)
      } catch (e: TimeoutException) {
        future.cancel(true)
        throw RuntimeException("dumpViewHierarchy timed out after 30s — likely an accessibility IPC deadlock. Failing fast.", e)
      } finally {
        executor.shutdownNow()
      }

      // When all top-level nodes have isVisibleToUser()=false (e.g. an AndroidView wrapper
      // whose host view is INVISIBLE), AccessibilityNodeInfoDumper skips them and produces a
      // dump containing only empty structural containers (text="") and system-UI nodes. The
      // text-node count is a reliable signal: fewer than TEXT_NODE_FALLBACK_THRESHOLD non-empty
      // text= values implies the real app content was entirely skipped. Fall back to
      // getRootInActiveWindow() which traverses without the visibility filter so Compose virtual
      // nodes inside invisible containers are still reachable.
      val standardTextNodeCount = countTextNodes(standardDump)
      if (standardTextNodeCount < TEXT_NODE_FALLBACK_THRESHOLD) {
        Console.log("[dumpViewHierarchy] Sparse dump ($standardTextNodeCount text nodes); activating accessibility-tree fallback.")
        // visibleOnly defaults to false here ON PURPOSE — the sparse case wants the invisible
        // ViewFactoryHolder content that dumpWindowHierarchy() skipped. The Compose-collapse
        // recovery in recoverCollapsedComposeTree() also passes visibleOnly=false for the same
        // reason: a collapsed ComposeView often lives inside a ViewFactoryHolder whose
        // isVisibleToUser is false, so the recovery must walk through it to reach and refresh()
        // the collapsed surface. Same dump helper, same intent in both callers.
        val fallbackDump = dumpViewHierarchyFromAccessibilityTree()
        if (fallbackDump != null) {
          fallbackDump
        } else {
          Console.log("[dumpViewHierarchy] Fallback returned null (timeout or null root); using standard dump.")
          standardDump
        }
      } else {
        standardDump
      }
    }

    /**
     * Creates a blank bitmap with the current window size.
     *
     * Used when a screenshot comes back as "null" which occurs on secure screens.
     */
    fun createBlankBitmapForCurrentWindowSize(): Bitmap = withUiDevice {
      // Create a bitmap with the specified width and height
      val bitmap = Bitmap.createBitmap(displayWidth, displayHeight, Bitmap.Config.ARGB_8888)
      // Create a canvas to draw on the bitmap
      val canvas = Canvas(bitmap)
      // Fill the canvas with a black background
      canvas.drawColor(Color.BLACK)
      bitmap
    }

    /**
     * Takes the screenshot with UiAutomator.
     * If the screenshot is blank, we'll draw bounding boxes based on view hierarchy data.
     */
    fun takeScreenshot(): Bitmap? = traceOnDeviceUiAutomatorScreenState("${AndroidOnDeviceUiAutomatorScreenState::class.simpleName}.takeScreenshot") {
      traceOnDeviceUiAutomatorScreenState("takeScreenshot") {
        // UiAutomation.takeScreenshot() uses Binder IPC which can deadlock on slow or
        // resource-constrained emulators (same failure mode as dumpWindowHierarchy).
        // Wrap in a 30s timeout so callers fail fast rather than hanging until JUnit kills them.
        val executor = Executors.newSingleThreadExecutor()
        val future = executor.submit(Callable<Bitmap?> {
          withUiAutomation { takeScreenshot() }
        })
        try {
          future.get(30, TimeUnit.SECONDS)
        } catch (e: TimeoutException) {
          future.cancel(true)
          throw RuntimeException("takeScreenshot timed out after 30s — likely a Binder IPC deadlock.", e)
        } finally {
          executor.shutdownNow()
        }
      }
    }

    private fun addSetOfMark(screenshotBitmap: Bitmap, viewHierarchy: ViewHierarchyTreeNode): Bitmap = traceOnDeviceUiAutomatorScreenState("addSetOfMark") {
      val mutableBitmap = if (!screenshotBitmap.isMutable) {
        screenshotBitmap.copy(Bitmap.Config.ARGB_8888, true)
      } else {
        screenshotBitmap
      }
      AndroidCanvasSetOfMark.drawSetOfMarkOnBitmap(
        originalScreenshotBitmap = mutableBitmap,
        elements = ViewHierarchyTreeNodeUtils.from(
          viewHierarchy,
          DeviceInfo(
            platform = Platform.ANDROID,
            widthPixels = mutableBitmap.width,
            heightPixels = mutableBitmap.height,
            widthGrid = mutableBitmap.width,
            heightGrid = mutableBitmap.height,
          ),
        ),
        includeLabel = true,
      )
      return mutableBitmap
    }

    // Minimum number of non-empty text= values in the dump before we consider it healthy.
    // Zero (or very few) text-bearing nodes indicates that all Compose content was skipped by
    // UIAutomator because it lived inside an invisible ViewFactoryHolder wrapper.
    private const val TEXT_NODE_FALLBACK_THRESHOLD = 5

    // Count nodes that carry non-empty text= values. Structural container nodes always have
    // text="" and are not counted; only nodes with real label text contribute.
    private fun countTextNodes(xml: String): Int {
      var count = 0
      var searchFrom = 0
      while (searchFrom < xml.length) {
        val textIdx = xml.indexOf("text=\"", searchFrom)
        if (textIdx < 0) break
        val valueStart = textIdx + 6
        val valueEnd = xml.indexOf('"', valueStart)
        if (valueEnd > valueStart) count++
        searchFrom = if (valueEnd >= 0) valueEnd + 1 else break
      }
      return count
    }

    /**
     * Fallback hierarchy dump using [android.app.UiAutomation.getRootInActiveWindow].
     *
     * Unlike [UiDevice.dumpWindowHierarchy] (which calls AccessibilityNodeInfoDumper and
     * skips every node where [AccessibilityNodeInfo.isVisibleToUser] is false), this traversal
     * includes invisible nodes. That lets us reach Compose virtual nodes inside an invisible
     * [androidx.compose.ui.viewinterop.ViewFactoryHolder] — they carry correct screen bounds
     * even when the host View's visibility flag is INVISIBLE, so gestures at those coordinates
     * still reach the rendered content.
     *
     * Returns null on timeout or if [getRootInActiveWindow] returns null, signalling the caller
     * to fall back to the standard (sparse) dump rather than throwing.
     *
     * @param visibleOnly when true, skip nodes (and their subtrees) where
     *   [AccessibilityNodeInfo.isVisibleToUser] is false, matching [UiDevice.dumpWindowHierarchy]'s
     *   visibility filtering. Both the sparse-dump fallback and the Compose-collapse recovery use
     *   `false`: a collapsed ComposeView is often inside a [androidx.compose.ui.viewinterop.ViewFactoryHolder] whose
     *   isVisibleToUser is false (e.g. during a fragment transition or after am instrument
     *   suppressed accessibility at startup), so skipping invisible nodes would prevent refresh()
     *   from ever reaching the collapsed surface.
     */
    internal fun dumpViewHierarchyFromAccessibilityTree(visibleOnly: Boolean = false): String? {
      val executor = Executors.newSingleThreadExecutor()
      val future = executor.submit(Callable {
        val root = withUiAutomation { rootInActiveWindow }
        if (root == null) {
          Console.log("[dumpViewHierarchy] getRootInActiveWindow() returned null.")
          return@Callable null
        }
        try {
          buildString {
            append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
            append("<hierarchy rotation=\"0\">\n")
            dumpAccessibilityNodeToXml(root, this, 0, visibleOnly)
            append("</hierarchy>")
          }
        } finally {
          root.recycle()
        }
      })
      return try {
        future.get(30, TimeUnit.SECONDS)
      } catch (e: TimeoutException) {
        future.cancel(true)
        Console.log("[dumpViewHierarchy] Accessibility-tree fallback timed out after 30s.")
        null
      } finally {
        executor.shutdownNow()
      }
    }

    private fun dumpAccessibilityNodeToXml(node: AccessibilityNodeInfo, sb: StringBuilder, index: Int, visibleOnly: Boolean) {
      // Bust UiAutomation's cached AccessibilityNodeInfo for this node before reading it. Compose
      // exports a ComposeView's semantic children lazily; if UiAutomation cached the node before
      // those semantics existed, the getChild() walk below would replay the stale (childless)
      // subtree. refresh() forces a re-fetch from the source window — the same cache-bust the
      // accessibility-service capture path (refreshTreeInPlace) relies on. Best-effort: a false
      // return means the source view is gone, in which case the last-cached fields are still our
      // best available data, so we keep reading.
      node.refresh()
      // When visibleOnly, skip this node and its subtree if it isn't visible to the user — mirrors
      // dumpWindowHierarchy()'s AccessibilityNodeInfoDumper filtering so a recovered tree doesn't
      // expose hidden nodes that the standard capture would have dropped. Checked after refresh()
      // so isVisibleToUser reflects the live view state.
      if (visibleOnly && !node.isVisibleToUser) return
      val bounds = Rect()
      node.getBoundsInScreen(bounds)
      val text = escapeXmlAttribute(node.text?.toString() ?: "")
      val contentDesc = escapeXmlAttribute(node.contentDescription?.toString() ?: "")
      val resourceId = escapeXmlAttribute(node.viewIdResourceName ?: "")
      val className = escapeXmlAttribute(node.className?.toString() ?: "")
      val packageName = escapeXmlAttribute(node.packageName?.toString() ?: "")
      val hintText = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        escapeXmlAttribute(node.hintText?.toString() ?: "")
      } else {
        ""
      }
      val boundsStr = "[${bounds.left},${bounds.top}][${bounds.right},${bounds.bottom}]"
      sb.append(
        """<node index="$index" text="$text" resource-id="$resourceId" class="$className" """ +
          """package="$packageName" content-desc="$contentDesc" hintText="$hintText" """ +
          """checkable="${node.isCheckable}" checked="${node.isChecked}" """ +
          """clickable="${node.isClickable}" enabled="${node.isEnabled}" """ +
          """focusable="${node.isFocusable}" focused="${node.isFocused}" """ +
          """scrollable="${node.isScrollable}" long-clickable="${node.isLongClickable}" """ +
          """password="${node.isPassword}" selected="${node.isSelected}" bounds="$boundsStr">"""
      )
      sb.append("\n")
      for (i in 0 until node.childCount) {
        val child = node.getChild(i)
        if (child != null) {
          dumpAccessibilityNodeToXml(child, sb, i, visibleOnly)
          child.recycle()
        }
      }
      sb.append("</node>\n")
    }

    private fun escapeXmlAttribute(value: String): String = value
      .replace("&", "&amp;")
      .replace("<", "&lt;")
      .replace(">", "&gt;")
      .replace("\"", "&quot;")
  }

  /**
   * Captures the clean screenshot and returns the compressed bytes.
   * The bitmap is converted to bytes and recycled immediately to minimize memory usage.
   */
  private fun getScreenshot(
    viewHierarchy: ViewHierarchyTreeNode?,
    screenshotScalingConfig: ScreenshotScalingConfig?,
  ): ByteArray? {
    val cleanBitmap = takeScreenshot() ?: return null

    return if (screenshotScalingConfig != null) {
      cleanBitmap.scaleAndEncode(screenshotScalingConfig)
    } else {
      val bytes = cleanBitmap.toByteArray()
      cleanBitmap.recycle()
      bytes
    }
  }
}
