package xyz.block.trailblaze.android.test.hierarchy

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.UiAutomation
import android.graphics.Rect
import android.os.Build
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.test.platform.app.InstrumentationRegistry
import kotlin.math.abs
import xyz.block.trailblaze.android.test.hierarchy.a11y.AccessibilityNode
import xyz.block.trailblaze.android.test.hierarchy.a11y.NodeIdCounter
import xyz.block.trailblaze.android.test.hierarchy.a11y.TreeCaptureTally
import xyz.block.trailblaze.android.test.hierarchy.a11y.toAccessibilityNode
import xyz.block.trailblaze.android.test.hierarchy.a11y.toTrailblazeNode
import xyz.block.trailblaze.android.test.onMainThread
import xyz.block.trailblaze.api.MigrationScreenState
import xyz.block.trailblaze.api.ScreenState
import xyz.block.trailblaze.api.TrailblazeNode
import xyz.block.trailblaze.api.filterImportantForAccessibility
import xyz.block.trailblaze.util.Console

/**
 * The app's real accessibility tree, read from inside its own process, so a recorded
 * `androidAccessibility:` selector is matched exactly as the accessibility driver matches it.
 *
 * Nothing is translated. The tree comes from the platform (`UiAutomation`, with the accessibility
 * driver's own service flags) and is mapped by the accessibility driver's own mapper, so every
 * field a recording can name — resource id, text, hint, role description, test tag, collection
 * position — has the value that driver would see, and whether a node is a View or a composable
 * never enters into matching.
 *
 * Acting still happens on a live identity. [ownerOf] links a matched node back to the View or
 * Compose semantics node that produced it by building that owner's node locally and comparing:
 * [AccessibilityNodeInfo.equals] is identity — the source View, the virtual child id, and the
 * window — so the link is exact. Bounds only order the candidates tried.
 *
 * Opt-in per device, read per call: `adb shell setprop debug.trailblaze.inprocess.a11yTree 1`.
 */
object InProcessAccessibilityTree {

  const val SYSPROP = "debug.trailblaze.inprocess.a11yTree"

  private val getSysprop: java.lang.reflect.Method? by lazy {
    runCatching {
      Class.forName("android.os.SystemProperties").getMethod("get", String::class.java, String::class.java)
    }.getOrNull()
  }

  fun sysprop(name: String): String =
    runCatching { getSysprop?.invoke(null, name, "") as? String }.getOrNull() ?: ""

  fun isEnabled(): Boolean = sysprop(SYSPROP).lowercase() in setOf("1", "true")

  class Captured(
    val tree: TrailblazeNode,
    private val infoByNodeId: Map<Long, AccessibilityNodeInfo>,
    /** Nodes and window roots the device did not answer for, so [tree] can be missing a subtree. */
    val droppedFetches: Int = 0,
  ) {
    val isComplete: Boolean get() = droppedFetches == 0

    fun infoFor(node: TrailblazeNode): AccessibilityNodeInfo? = infoByNodeId[node.nodeId]
  }

  private const val EVENTS_QUIET_MS = 150L
  private const val EVENTS_QUIET_TIMEOUT_MS = 2_000L

  /** Same flags the accessibility driver's service declares, so both read the same tree. */
  private const val DRIVER_FLAGS =
    AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS or
      AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
      AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS

  /**
   * Sets the accessibility driver's service flags on [uiAutomation] if they are not set yet. Window
   * enumeration needs `FLAG_RETRIEVE_INTERACTIVE_WINDOWS`: without it the window list can leave out
   * the keyboard and other apps' windows, so anything that reads windows calls this first rather
   * than relying on an earlier capture.
   */
  fun ensureDriverFlags(uiAutomation: UiAutomation) {
    val serviceInfo = uiAutomation.serviceInfo
    if (serviceInfo != null && serviceInfo.flags and DRIVER_FLAGS != DRIVER_FLAGS) {
      serviceInfo.flags = serviceInfo.flags or DRIVER_FLAGS
      uiAutomation.serviceInfo = serviceInfo
    }
  }

  /**
   * Must not run on the main thread: the app's main thread is what answers the query.
   *
   * [fresh] waits for the app's accessibility events to go quiet, then reads past the platform's
   * node cache. That cache is updated from those events, and they trail the frame: a tree read
   * right after a scroll still listed the rows from before it, while the View/Compose tree read
   * beside it did not.
   *
   * [uncached] reads past the cache without the wait, for a selector lookup that polls on its own:
   * Compose does not send an event for every change, so a cached read can keep text that is gone.
   */
  fun capture(fresh: Boolean = false, uncached: Boolean = fresh): Captured? {
    val uiAutomation = InstrumentationRegistry.getInstrumentation()
      .getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
    ensureDriverFlags(uiAutomation)
    if (fresh) {
      // A screen that never stops sending events reads at the timeout instead; still a tree.
      runCatching { uiAutomation.waitForIdle(EVENTS_QUIET_MS, EVENTS_QUIET_TIMEOUT_MS) }
    }
    // The cache is kept current by those events alone, and Compose's do not all reach it: after
    // a scroll it still listed the rows from before. Dropping it makes this read ask the app; with
    // no way to drop it, each node is refreshed as it is read.
    val canClearCache = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE
    if (uncached && canClearCache) uiAutomation.clearCache()
    val refreshEach = uncached && !canClearCache
    val tally = TreeCaptureTally()
    val roots = windowRoots(uiAutomation, tally).ifEmpty { return null }
    val infos = HashMap<Long, AccessibilityNodeInfo>()
    val counter = NodeIdCounter()
    val onNode = { id: Long, info: AccessibilityNodeInfo -> infos[id] = copyOf(info) }
    // One window converts exactly as it always did; more are gathered under one container, in the
    // order the accessibility driver merges them, so a recording made there matches here.
    val node = roots.singleOrNull()?.toAccessibilityNode(counter, tally, onNode, refreshEach)
      ?: AccessibilityNode(
        nodeId = counter.next(),
        children = roots.map { it.toAccessibilityNode(counter, tally, onNode, refreshEach) },
      )
    return Captured(node.toTrailblazeNode(), infos, droppedFetches = tally.total)
  }

  /** A copy that outlives [info]'s recycling. The copy constructor is API 30; `obtain` before it. */
  @Suppress("DEPRECATION")
  private fun copyOf(info: AccessibilityNodeInfo): AccessibilityNodeInfo =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) AccessibilityNodeInfo(info) else AccessibilityNodeInfo.obtain(info)

  /**
   * Every application window's root, base window first: a popup or dialog the app opens is a
   * window of its own and is often not the active one, so reading the active window alone missed
   * its controls. Mirrors the accessibility driver's `getCaptureWindowRoots`: the keyboard and
   * system chrome stay out, and a window whose root will not resolve is counted as a hole in the
   * capture rather than skipped silently.
   */
  private fun windowRoots(uiAutomation: UiAutomation, tally: TreeCaptureTally): List<AccessibilityNodeInfo> {
    // A failed enumeration can hide a popup window, so the capture is not complete without it.
    val windows = (runCatching { uiAutomation.windows }.getOrNull() ?: emptyList<AccessibilityWindowInfo>().also { tally.recordDroppedWindowRoot() })
      .filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
      .sortedWith(compareBy({ it.layer }, { !it.isActive }))
    val roots = ArrayList<AccessibilityNodeInfo>(windows.size)
    for (window in windows) {
      val root = runCatching { window.root }.getOrNull()
      if (root == null) {
        tally.recordDroppedWindowRoot()
      } else if (roots.none { it == root }) {
        roots += root
      }
    }
    return roots.ifEmpty { listOfNotNull(runCatching { uiAutomation.rootInActiveWindow }.getOrNull()) }
  }

  /**
   * Waits until the app has sent no accessibility events for [quietMs], for at most [timeoutMs].
   * False on timeout. The accessibility driver's `waitForChange` settles on the same signal: the
   * time since the last UI event its service received.
   */
  fun awaitEventsQuiet(quietMs: Long, timeoutMs: Long): Boolean {
    val uiAutomation = InstrumentationRegistry.getInstrumentation()
      .getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
    return try {
      uiAutomation.waitForIdle(quietMs, timeoutMs)
      true
    } catch (_: java.util.concurrent.TimeoutException) {
      false
    }
  }

  /** What acts on a matched node: a live View, or a semantics id. */
  sealed interface Owner {
    val node: TrailblazeNode

    data class ViewOwner(override val node: TrailblazeNode, val view: View) : Owner

    data class ComposeOwner(override val node: TrailblazeNode, val semanticsId: Int) : Owner
  }

  /**
   * The View or Compose node in [hybrid] that produced [info], or null when none in this process
   * did (a system window, or a node the hybrid capture doesn't carry).
   */
  fun ownerOf(
    info: AccessibilityNodeInfo,
    hybrid: TrailblazeNode,
    viewByNodeId: Map<Long, View>,
    semanticsIdByNodeId: Map<Long, Int>,
  ): Owner? {
    val target = Rect().also(info::getBoundsInScreen)
    val parents = HashMap<Long, TrailblazeNode>()
    val all = ArrayList<TrailblazeNode>()
    fun walk(node: TrailblazeNode) {
      all += node
      node.children.forEach { child ->
        parents[child.nodeId] = node
        walk(child)
      }
    }
    walk(hybrid)
    fun distance(node: TrailblazeNode): Int = node.bounds?.let {
      abs(it.left - target.left) + abs(it.top - target.top) + abs(it.right - target.right) +
        abs(it.bottom - target.bottom)
    } ?: Int.MAX_VALUE
    fun hostViewOf(node: TrailblazeNode): View? {
      var current: TrailblazeNode? = parents[node.nodeId]
      while (current != null) {
        viewByNodeId[current.nodeId]?.let { if (it.accessibilityNodeProvider != null) return it }
        current = parents[current.nodeId]
      }
      return null
    }
    val candidates = all.filter { it.nodeId in viewByNodeId || it.nodeId in semanticsIdByNodeId }
      .sortedBy(::distance)
    return onMainThread {
      candidates.firstNotNullOfOrNull { node ->
        val view = viewByNodeId[node.nodeId]
        val semanticsId = semanticsIdByNodeId[node.nodeId]
        when {
          view != null ->
            Owner.ViewOwner(node, view).takeIf { view.createAccessibilityNodeInfo() == info }
          semanticsId != null -> {
            val local = hostViewOf(node)?.accessibilityNodeProvider?.createAccessibilityNodeInfo(semanticsId)
            Owner.ComposeOwner(node, semanticsId).takeIf { local != null && local == info }
          }
          else -> null
        }
      }
    }
  }

  /**
   * `trailblaze.captureSecondaryTree=true`: the dual-tree switch the host sets from
   * `TRAILBLAZE_CAPTURE_SECONDARY_TREE`, for rewriting `androidAccessibility:` recordings into this
   * driver's own `androidView:` / `compose:` selectors with `trailblaze waypoint migrate-trail`.
   */
  val dualCaptureEnabled: Boolean by lazy {
    InstrumentationRegistry.getArguments().getString("trailblaze.captureSecondaryTree")
      .equals("true", ignoreCase = true)
  }

  /**
   * [primary] as a dual-tree capture when [dualCaptureEnabled]: the accessibility tree is read
   * immediately before it, so both trees describe the same frame, and becomes the PRIMARY tree,
   * with [primary]'s own tree as the side-channel one. `migrate-trail` reads the primary as the
   * dialect a recording is migrated FROM and the side channel as the one it is migrated TO.
   *
   * For logged snapshots only, never for a state a tool resolves against: the primary tree is no
   * longer this driver's. Off the main thread, like [capture].
   */
  fun dualCapture(primary: () -> ScreenState): ScreenState {
    if (!dualCaptureEnabled) return primary()
    // Filtered as the accessibility driver logs its own tree, so a recording is matched here
    // exactly as it is on that driver's captures: unfiltered, a layout-only wrapper between a tab
    // and its label breaks a recorded `containsChild`.
    val accessibility = runCatching { capture(fresh = true) }
      .onFailure { Console.log("[migration-capture] accessibility tree read failed: ${it.message}") }
      .getOrNull()
    val state = primary()
    if (accessibility == null) return state
    val accessibilityTree = accessibility.tree.filterImportantForAccessibility()
    val accessibilityPrimary = object : ScreenState by state {
      override val trailblazeNodeTree: TrailblazeNode = accessibilityTree

      // The primary tree's own count: a fetch it lost must not read as "not on screen".
      override val droppedNodeFetches: Int? = accessibility.droppedFetches
    }
    return MigrationScreenState.wrap(accessibilityPrimary, state.trailblazeNodeTree)
  }

  /** One line per node, indented by depth, for a miss message. */
  fun describe(tree: TrailblazeNode, maxLines: Int = 80): String = buildString {
    var lines = 0
    fun walk(node: TrailblazeNode, depth: Int) {
      if (lines++ >= maxLines) return
      val d = node.driverDetail as? xyz.block.trailblaze.api.DriverNodeDetail.AndroidAccessibility
      append("  ".repeat(depth))
      append(d?.className?.substringAfterLast('.') ?: "?")
      if (d?.isPassword != true) {
        d?.text?.let { append(" text=").append(diagnosticValue(it)) }
        d?.contentDescription?.let { append(" desc=").append(diagnosticValue(it)) }
      }
      d?.collectionItemInfo?.let { append(" r=").append(it.rowIndex).append(" c=").append(it.columnIndex) }
      append('\n')
      node.children.forEach { walk(it, depth + 1) }
    }
    walk(tree, 0)
  }

  /**
   * A value whole, or only its length when it is long. Session logs scrub sensitive values by
   * matching them whole, so a truncated prefix of one would get through.
   */
  private fun diagnosticValue(value: String): String =
    if (value.length <= DESCRIBE_MAX_VALUE_CHARS) "\"$value\"" else "<${value.length} chars>"

  private const val DESCRIBE_MAX_VALUE_CHARS = 40
}
