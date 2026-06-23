package xyz.block.trailblaze.android.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.UiAutomation
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.graphics.Path
import android.graphics.Point
import android.graphics.Rect
import android.hardware.HardwareBuffer
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import android.view.Display
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.datetime.Clock
import maestro.TreeNode
import xyz.block.trailblaze.AdbCommandUtil
import xyz.block.trailblaze.InstrumentationUtil
import xyz.block.trailblaze.android.AndroidSdkVersion
import xyz.block.trailblaze.devices.TrailblazeAndroidDeviceCategory
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.util.Console
import xyz.block.trailblaze.util.PollingUtils

/**
 * Accessibility service for Trailblaze.
 *
 * This service is used to interact with the Android accessibility framework. It provides methods to
 * perform gestures, capture screenshots, and manage the accessibility state.
 */
class TrailblazeAccessibilityService : AccessibilityService() {

  override fun onServiceConnected() {
    super.onServiceConnected()
    Console.log("${this::class.simpleName} onServiceConnected")
    accessibilityServiceInstance = this
    // Reset per-bind diagnostic gates so a re-bind (e.g., user toggles accessibility off/on)
    // gets its own breadcrumb if the new bind also lacks window enumeration. Without this,
    // a debug session where someone disables and re-enables the service would never see
    // the fallback warning twice — even if the second bind is also broken.
    windowsFallbackLogged.set(false)
    val intent = Intent().apply { action = ACTION_SERVICE_READY }
    applicationContext.sendBroadcast(intent)
  }

  override fun onCreate() {
    super.onCreate()
    Console.log("${this::class.simpleName} onCreate")
  }

  override fun onAccessibilityEvent(event: AccessibilityEvent) {
    // Track the timestamp of meaningful UI events for settle detection.
    // Only event types that indicate actual UI mutations — not noise like announcements.
    when (event.eventType) {
      AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
        lastUiEventTimestampMs = Clock.System.now().toEpochMilliseconds()
        // Track the foreground activity class from window state changes — but ONLY when the
        // event is confirmed to come from a TYPE_APPLICATION window. IMEs, System UI,
        // toasts, and accessibility overlays all fire TYPE_WINDOW_STATE_CHANGED with their
        // own className; if any of those overwrites currentActivityClass the V3 verifier
        // sees the wrong "Activity:" in its screen state header and refuses to mark
        // text-entry steps complete (the LLM thinks input isn't finalized and loops).
        val cls = event.className?.toString() ?: return
        if (!cls.contains('.')) return
        if (!isEventFromApplicationWindow(event)) return
        currentActivityClass = cls
      }
      AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
      AccessibilityEvent.TYPE_VIEW_SCROLLED,
      AccessibilityEvent.TYPE_WINDOWS_CHANGED,
      AccessibilityEvent.TYPE_VIEW_CLICKED -> {
        // TYPE_VIEW_CLICKED is the only settle signal canvas widgets emit on the ACTION_CLICK
        // route (ExploreByTouchHelper virtual views) — gesture-path clicks emit nothing.
        lastUiEventTimestampMs = Clock.System.now().toEpochMilliseconds()
      }
    }
  }

  override fun onDestroy() {
    super.onDestroy()
    restoreSoftKeyboardIfPending()
    accessibilityServiceInstance = null
  }

  /**
   * Outcome of [waitForChangeSince]. Distinguishes "the UI was already quiet at entry" from
   * "the UI was in flux, then changed and settled" so the caller doesn't treat an already-settled
   * reaction as a missed change.
   */
  enum class WaitForChangeOutcome {
    /** The UI was already settled at entry (last event older than the quiet window). */
    ALREADY_SETTLED,

    /** The UI was in flux at entry, then changed and settled within the timeout. */
    CHANGED_AND_SETTLED,

    /** The UI never changed-and-settled within the timeout. */
    TIMED_OUT,
  }

  companion object {

    /**
     * When running in accessibility, the concept of instanceId doesn't apply as we're running on a
     * single device and cannot differentiate ourselves. We could consider the device serial number,
     * but this value is the adb name which we don't have access to.
     */
    internal val DEFAULT_TRAILBLAZE_ACCESSIBILITY_DEVICE_ID: TrailblazeDeviceId =
      TrailblazeDeviceId(
        instanceId = TrailblazeDriverType.ANDROID_ONDEVICE_ACCESSIBILITY.name,
        trailblazeDevicePlatform = TrailblazeDevicePlatform.ANDROID,
      )
    const val ACTION_SERVICE_READY = "xyz.block.trailblaze.SERVICE_READY"

    /**
     * Post-gesture settle time in milliseconds. Set to 0 because event-based settling
     * via [waitForSettled] handles this — the old 200ms blind sleep is redundant when
     * [AccessibilityDeviceManager] calls waitForSettled after every action.
     */
    @Volatile var postGestureSettleTimeMs: Long = 0L

    /**
     * Timestamp of the last meaningful UI accessibility event. Updated by [onAccessibilityEvent]
     * for event types that indicate actual UI mutations (window/content changes, scrolls).
     *
     * Volatile since it's written on the main thread (accessibility events) and read from worker
     * threads (settle detection).
     */
    @Volatile internal var lastUiEventTimestampMs: Long = 0L

    /** The class name of the current foreground activity, tracked from accessibility events. */
    @Volatile internal var currentActivityClass: String? = null

    /** Returns the short name of the current foreground activity (e.g., "HomeActivity"). */
    fun getCurrentActivity(): String? =
      currentActivityClass?.substringAfterLast('.')?.takeIf { it.isNotBlank() }

    @Volatile private var accessibilityServiceInstance: TrailblazeAccessibilityService? = null

    /** Returns true if the accessibility service is currently running and available. */
    fun isServiceRunning(): Boolean = accessibilityServiceInstance != null

    private fun requireService(): TrailblazeAccessibilityService =
      accessibilityServiceInstance
        ?: error(
          "TrailblazeAccessibilityService is not running. " +
            "Ensure it is enabled in Settings → Accessibility."
        )

    val appContext: Context
      get() = requireService().applicationContext

    /**
     * Waits for the UI to settle by monitoring accessibility events. Uses an adaptive debounce:
     *
     * 1. Records the event timestamp at call time.
     * 2. Polls rapidly — if new events have arrived (UI is actively updating), skips the grace
     *    period entirely since the OS is clearly processing.
     * 3. If no events have arrived yet, waits up to [maxGracePeriodMs] for the first event before
     *    starting quiet-window detection. This prevents false "already settled" when called
     *    immediately after dispatching a gesture.
     * 4. Once events stop flowing, waits for [quietWindowMs] of silence before declaring settled.
     *
     * @param quietWindowMs Duration of silence (no UI events) required to consider the UI settled.
     * @param maxGracePeriodMs Maximum time to wait for the first event if none have arrived yet.
     *   Skipped entirely if events are already flowing.
     * @param timeoutMs Maximum total time to wait before giving up. Returns false if exceeded.
     * @return true if the UI settled within the timeout, false if timed out.
     */
    fun waitForSettled(
      quietWindowMs: Long = 100L,
      maxGracePeriodMs: Long = 50L,
      timeoutMs: Long = 5_000L,
    ): Boolean {
      val startTime = Clock.System.now().toEpochMilliseconds()
      val eventTimestampAtEntry = lastUiEventTimestampMs

      val pollIntervalMs = 16L // ~1 frame at 60fps for responsive polling
      var sawNewEvent = false

      while (true) {
        val now = Clock.System.now().toEpochMilliseconds()
        val elapsed = now - startTime

        if (elapsed >= timeoutMs) {
          Console.log("waitForSettled timed out after ${elapsed}ms")
          return false
        }

        val currentEventTimestamp = lastUiEventTimestampMs
        val timeSinceLastEvent = now - currentEventTimestamp

        // Check if new events have arrived since we started waiting.
        if (!sawNewEvent && currentEventTimestamp > eventTimestampAtEntry) {
          sawNewEvent = true
        }

        // If no events have arrived yet and we're still within the grace period, keep waiting
        // for the OS to start processing. This prevents false "already settled" on fast dispatch.
        if (!sawNewEvent && elapsed < maxGracePeriodMs) {
          Thread.sleep(pollIntervalMs)
          continue
        }

        // Once we've seen events (or grace period expired), check for quiet window.
        if (timeSinceLastEvent >= quietWindowMs) {
          Console.log("UI settled after ${elapsed}ms (quiet for ${timeSinceLastEvent}ms)")
          return true
        }

        Thread.sleep(pollIntervalMs)
      }
    }

    /**
     * Two-phase "wait for the screen to change" against the live accessibility event stream.
     *
     * Fast path: if the UI is already settled at entry (the last UI event is older than
     * [quietWindowMs]), returns [WaitForChangeOutcome.ALREADY_SETTLED] immediately — the reaction
     * already happened and went quiet before this ran.
     *
     * Otherwise, Phase 1 (change): polls [lastUiEventTimestampMs] until it advances past
     * [baselineEventTs] — i.e. a real TYPE_WINDOW_CONTENT_CHANGED / WINDOW_STATE_CHANGED /
     * VIEW_SCROLLED / VIEW_CLICKED event fired after entry. Phase 2 (settle): once changed, waits
     * for [quietWindowMs] of no further events before returning [WaitForChangeOutcome.CHANGED_AND_SETTLED].
     * The whole thing is bounded by [timeoutMs]; if the tree never changes within the budget this
     * returns [WaitForChangeOutcome.TIMED_OUT] and the caller decides whether that's an error.
     *
     * [baselineEventTs] is captured at the agent/tool layer at call entry (NOT here) so the
     * baseline reflects the moment the step started, not the moment this method first runs.
     */
    fun waitForChangeSince(
      baselineEventTs: Long,
      quietWindowMs: Long,
      timeoutMs: Long,
    ): WaitForChangeOutcome {
      val entryNow = Clock.System.now().toEpochMilliseconds()
      if (entryNow - lastUiEventTimestampMs >= quietWindowMs) {
        Console.log("waitForChangeSince: UI already settled at entry; returning immediately")
        return WaitForChangeOutcome.ALREADY_SETTLED
      }

      val startTime = entryNow
      val pollIntervalMs = 16L // ~1 frame at 60fps for responsive polling
      var sawChange = false

      while (true) {
        val now = Clock.System.now().toEpochMilliseconds()
        val elapsed = now - startTime
        if (elapsed >= timeoutMs) {
          Console.log("waitForChangeSince timed out after ${elapsed}ms (sawChange=$sawChange)")
          return WaitForChangeOutcome.TIMED_OUT
        }

        val currentEventTs = lastUiEventTimestampMs
        if (!sawChange) {
          if (currentEventTs > baselineEventTs) {
            sawChange = true
            Console.log("waitForChangeSince observed change after ${elapsed}ms")
          }
        } else if (now - currentEventTs >= quietWindowMs) {
          Console.log("waitForChangeSince settled after ${elapsed}ms (quiet for ${now - currentEventTs}ms)")
          return WaitForChangeOutcome.CHANGED_AND_SETTLED
        }

        Thread.sleep(pollIntervalMs)
      }
    }

    fun getViewHierarchy(): TreeNode? = captureMergedScreenTrees().treeNode

    /**
     * Returns the raw [AccessibilityNodeInfo] root for direct, high-fidelity tree capture.
     *
     * Two layered mitigations cover post-gesture freshness:
     *
     * 1. **`UiDevice.waitForIdle()`** inside
     *    `AccessibilityDeviceManager.dispatchAndAwaitSettle` waits for the platform's
     *    accessibility-event quiet window (~500ms in UiAutomation) so we don't query a
     *    tree mid-animation. Handles the general "events still firing" case.
     *
     * 2. **`refreshTreeInPlace`** here forces `AccessibilityNodeInfo.refresh()` on every
     *    reachable node so subsequent text/state reads see the source view's current
     *    values rather than the platform's cached ones. Some Compose `Text` widgets
     *    (notably the sample app's `SwipeScreen` page-indicator header that reads
     *    "Page 1 of 5") don't fire a cache-invalidating accessibility event when they
     *    recompose — `waitForIdle()` returns cleanly but the cached text is stale.
     *    The refresh walk busts that cache.
     *
     * Both are needed. Empirical: dropping the refresh while keeping `waitForIdle`
     * regressed the swipe-directions test (body labels updated, header stayed stale
     * through the full 5s assertion poll). Measured per-test cost of the refresh walk
     * was within noise across before/after CI runs.
     */
    fun getRootNodeInfo(awaitStable: Boolean = true): AccessibilityNodeInfo? {
      if (awaitStable) awaitTreeStable()
      val root = getApplicationWindowRoot() ?: return null
      refreshTreeInPlace(root)
      return root
    }

    /**
     * Both shapes of the captured screen tree, built from the same single enumeration of window
     * roots so the Maestro-shape [treeNode] and the accessibility-shape [accessibilityNode] never
     * desync across two reads. [foregroundAppId] is the package of the primary (first/base)
     * window root.
     */
    class MergedScreenTrees(
      val treeNode: TreeNode?,
      val accessibilityNode: AccessibilityNode?,
      val foregroundAppId: String?,
    )

    /**
     * Captures the screen as merged trees that include secondary windows (dialogs, popups,
     * sub-panels) in addition to the active application window — see [getCaptureWindowRoots].
     *
     * Resolves every contributing window root once, refreshes each in place for text/state
     * freshness (parity with [getRootNodeInfo]), then converts that single root list into both the
     * Maestro [TreeNode] and the high-fidelity [AccessibilityNode] shapes. In the common
     * single-window case both trees are identical to the historical single-root capture. Every
     * resolved root is recycled before returning.
     */
    fun captureMergedScreenTrees(awaitStable: Boolean = true): MergedScreenTrees {
      if (awaitStable) awaitTreeStable()
      val roots = getCaptureWindowRoots()
      if (roots.isEmpty()) return MergedScreenTrees(null, null, null)
      return try {
        roots.forEach { refreshTreeInPlace(it) }
        MergedScreenTrees(
          treeNode = roots.toMergedTreeNode(),
          accessibilityNode = roots.toMergedAccessibilityNode(),
          // packageName must be read before recycle() invalidates the node.
          foregroundAppId = roots.first().packageName?.toString(),
        )
      } finally {
        roots.forEach { it.recycle() }
      }
    }

    /**
     * Pre-order walk that calls [AccessibilityNodeInfo.refresh] on every node reachable from
     * [root], recycling each child after recursion. The cached node-info entries seen by
     * later `getChild()` calls in the same capture remain populated with the freshly-fetched
     * data, so downstream tree walks read current text/state without their own refresh.
     *
     * Bounded by [MAX_REFRESH_DEPTH] to guard against StackOverflowError on pathological
     * Compose semantics trees — typical Android UIs stay well under this depth.
     */
    private fun refreshTreeInPlace(root: AccessibilityNodeInfo, depth: Int = 0) {
      if (depth >= MAX_REFRESH_DEPTH) {
        Console.log(
          "refreshTreeInPlace: stopped descending at depth $depth (>= MAX_REFRESH_DEPTH); " +
            "deeper subtree will use last-cached data",
        )
        return
      }
      // Refresh returning false means the source view is gone — leave the cached fields as
      // a last-known-good fallback rather than aborting the capture.
      root.refresh()
      for (i in 0 until root.childCount) {
        val child = root.getChild(i) ?: continue
        try {
          refreshTreeInPlace(child, depth + 1)
        } finally {
          child.recycle()
        }
      }
    }

    /**
     * Maximum recursion depth for [refreshTreeInPlace]. 200 is far above any realistic
     * Android UI tree (typical screens are under 50 levels); the cap exists purely to
     * keep a pathological Compose layout from turning the screen-state build into a
     * StackOverflowError that's hard to attribute back to this path.
     */
    private const val MAX_REFRESH_DEPTH = 200

    /**
     * Returns the foreground application window's root, bypassing the IME's `SoftInputWindow`.
     *
     * `AccessibilityService.getRootInActiveWindow` returns the IME's tree when the keyboard is
     * up — which broke the V3 verifier (it only saw the keyboard's keys, not the form). This
     * helper iterates [getServiceWindows] in z-order and prefers a TYPE_APPLICATION window
     * whose `getRoot()` is currently non-null (active first, then any). Falls back to
     * `rootInActiveWindow` when no application window is enumerable at all.
     *
     * Requires `flagRetrieveInteractiveWindows` in `accessibility_service_config.xml`; without
     * it the platform returns an empty list and we lose the IME-bypass.
     */
    private fun getApplicationWindowRoot(): AccessibilityNodeInfo? {
      val service = requireService()
      val windows = getServiceWindows() ?: return service.rootInActiveWindow
      if (windows.isEmpty()) return service.rootInActiveWindow

      // Two-pass selection: prefer an active app window with a resolvable root, then any.
      // `getRoot()` can transiently return null during window transitions; falling through to
      // `rootInActiveWindow` mid-transition would land us on the IME's tree exactly during
      // the transitions this helper exists to handle.
      val applicationWindows = windows.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
      applicationWindows.firstOrNull { it.isActive }?.root?.let { return it }
      applicationWindows.firstNotNullOfOrNull { it.root }?.let { return it }

      return service.rootInActiveWindow
    }

    /**
     * Lightweight, framework-free description of an accessibility window used by
     * [orderCaptureWindows] so the selection/ordering policy can be unit-tested without standing
     * up an [AccessibilityWindowInfo] (which can't be constructed off-device). Mirrors the IME
     * pure-function test seam ([isImeClassName], [parseDumpsysInputMethodShown]).
     */
    internal data class CaptureWindowInfo(
      val id: Int,
      val type: Int,
      val layer: Int,
      val isActive: Boolean,
    )

    /**
     * Selects and orders the windows whose accessibility content should be merged into a single
     * capture, given a snapshot of all enumerated windows.
     *
     * Policy:
     * - **Include** every `TYPE_APPLICATION` window, active and non-active. Android exposes
     *   dialogs, popups, sub-panels, spinner dropdowns, and companion/secondary app windows as
     *   their own `TYPE_APPLICATION` windows — there is no separate sub-panel window type at the
     *   [AccessibilityWindowInfo] level — so the non-active ones the previous single-window
     *   capture dropped are exactly the dialog/popover content this merge needs to surface.
     * - **Exclude** chrome that today's single-window capture never showed: the IME
     *   (`TYPE_INPUT_METHOD`), status/nav System UI (`TYPE_SYSTEM`), accessibility overlays,
     *   magnification, split-divider, and window-control windows. Keeping those out preserves
     *   the existing IME-bypass behavior — the form, not the keyboard's keys.
     * - **Order** by ascending `layer` (z-order), so the base application window comes first and
     *   higher (dialog/popup) windows are appended after it. The active window is placed first
     *   among equal layers so the primary app root stays the tree's first child, matching the
     *   single-window shape callers see today.
     *
     * Pure function on plain ints/booleans so it's unit-testable without Robolectric.
     */
    internal fun orderCaptureWindows(windows: List<CaptureWindowInfo>): List<CaptureWindowInfo> =
      windows
        .filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
        .sortedWith(compareBy({ it.layer }, { !it.isActive }))

    /**
     * Resolves the ordered list of window roots to merge into a single capture tree.
     *
     * Enumerates [getServiceWindows], runs the [orderCaptureWindows] policy to choose which
     * windows contribute content, resolves each `getRoot()` with per-window null-safety (a root
     * can transiently be null during a window transition — that window is simply skipped), and
     * de-duplicates by node identity so a window that resolves to the same root as another isn't
     * walked twice. Falls back to the single [getApplicationWindowRoot] when window enumeration
     * is unavailable (older OEMs, `flagRetrieveInteractiveWindows` not honored) or yields no
     * content window — preserving today's exact behavior in the common single-window case.
     *
     * The caller owns every returned [AccessibilityNodeInfo] and must recycle each one.
     */
    fun getCaptureWindowRoots(): List<AccessibilityNodeInfo> {
      val windows = getServiceWindows()
      if (windows.isNullOrEmpty()) {
        return listOfNotNull(getApplicationWindowRoot())
      }

      val byId = windows.associateBy { it.id }
      val ordered = orderCaptureWindows(
        windows.map {
          CaptureWindowInfo(id = it.id, type = it.type, layer = it.layer, isActive = it.isActive)
        },
      )

      val roots = ArrayList<AccessibilityNodeInfo>(ordered.size)
      for (windowInfo in ordered) {
        val root = byId[windowInfo.id]?.root ?: continue
        if (roots.any { it == root }) {
          root.recycle()
          continue
        }
        roots.add(root)
      }

      // Window enumeration is non-empty but no application window had a resolvable root
      // (mid-transition, or only chrome enumerated) — fall back so capture never goes blank.
      return roots.ifEmpty { listOfNotNull(getApplicationWindowRoot()) }
    }

    /**
     * Single source of truth for accessibility window enumeration. Wraps the platform call
     * with the SecurityException guard every caller used to inline, and emits a one-time
     * breadcrumb when the platform returns null/empty so the symptom is observable in user
     * reports. Returns null when the call throws or the service hasn't been bound yet —
     * distinct from an empty list, which the platform returns when window enumeration is
     * disabled (older OEMs, or `flagRetrieveInteractiveWindows` not yet honored).
     *
     * The one-time log lives here rather than in [getApplicationWindowRoot] alone so all
     * three callers ([getApplicationWindowRoot], [isKeyboardVisible], [isEventFromImeWindow])
     * share the same breadcrumb. The `windowsFallbackLogged` guard is reset in
     * [onServiceConnected] so each service bind gets its own warning.
     */
    private fun getServiceWindows(): List<AccessibilityWindowInfo>? {
      val windows = try {
        accessibilityServiceInstance?.windows
      } catch (_: SecurityException) {
        null
      }
      if (windows.isNullOrEmpty() && windowsFallbackLogged.compareAndSet(false, true)) {
        Console.log(
          "TrailblazeAccessibilityService: service.windows is unavailable on this device " +
            "(empty or threw SecurityException). Falling back to rootInActiveWindow / " +
            "focused-editable heuristics. If the soft keyboard is showing during a screen " +
            "capture, the IME's view hierarchy may resurface as the captured tree.",
        )
      }
      return windows
    }

    /** Sample cadence (~2 frames @60Hz) while waiting for the captured tree to stop changing. */
    private const val STABILITY_FRAME_MS = 32L

    /**
     * The tree signature must stay UNCHANGED for at least this long before the screen counts as
     * settled. A quiet WINDOW (not a single matching sample interval) is required because a slow or
     * janky transition — e.g. ~30fps, or any animation advancing less often than once per
     * [STABILITY_FRAME_MS] under load — can leave the tree momentarily identical across one interval
     * and then move again. Measuring elapsed quiet rather than a sample count keeps this
     * frame-rate-independent. Spans ~2.5 sample intervals.
     */
    private const val STABILITY_QUIET_MS = 80L

    /** Hard cap on the stability wait — a perpetually-animating screen proceeds instead of blocking. */
    private const val STABILITY_MAX_WAIT_MS = 1000L

    /** Max tree depth walked when hashing the structural signature. */
    private const val STABILITY_MAX_DEPTH = 60

    /** Non-zero FNV-style seed for the rolling tree signature. */
    private const val SIGNATURE_SEED = 1125899906842597L

    /**
     * Capture-time settle gate. Before a snapshot is built, briefly wait (hard-capped) for the
     * captured accessibility tree to stop changing, so the snapshot the runtime reasons about and
     * resolves selectors against carries final bounds — not the mid-transition coordinates that
     * drop nodes or land taps off-target during a Compose bottom-sheet / dialog / screen animation.
     *
     * Unlike a window-level signal, this works for Compose single-activity (intra-window)
     * navigation: it folds every node's bounds + class + text length into a cheap structural
     * [treeSignature] and waits until that signature stays unchanged for [STABILITY_QUIET_MS] — the
     * out-of-process analog of Playwright's "same bounding box across consecutive animation frames"
     * actionability gate. Requiring a quiet window (not a single matching interval) keeps a slow or
     * janky transition from reading as stable while a frame merely hasn't advanced yet. A fresh
     * window root is read per sample, so bounds reflect the live on-screen position.
     *
     * Hard-capped by [STABILITY_MAX_WAIT_MS] so a perpetual animation (e.g. an infinite spinner)
     * proceeds rather than blocking forever. Disable with `TRAILBLAZE_DISABLE_SETTLE_TREE_STABILITY=1`
     * (read per-call, flippable on a running daemon). The event-quiet [waitForSettled] path stays
     * intact as a complementary first-pass signal.
     */
    private fun awaitTreeStable() {
      if (treeStabilityGateDisabled()) return
      val startUptimeMs = SystemClock.uptimeMillis()
      val deadlineUptimeMs = startUptimeMs + STABILITY_MAX_WAIT_MS
      var previousSignature: Long? = null
      var lastChangeUptimeMs = startUptimeMs
      while (true) {
        val root = getApplicationWindowRoot() ?: return
        val signature = try {
          treeSignature(root)
        } finally {
          root.recycle()
        }
        val now = SystemClock.uptimeMillis()
        if (signature != previousSignature) {
          previousSignature = signature
          lastChangeUptimeMs = now
        } else if (now - lastChangeUptimeMs >= STABILITY_QUIET_MS) {
          Console.log("[settle] tree stable after ${now - startUptimeMs}ms")
          return
        }
        if (now >= deadlineUptimeMs) {
          Console.log("[settle] tree still changing after ${STABILITY_MAX_WAIT_MS}ms — proceeding")
          return
        }
        Thread.sleep(STABILITY_FRAME_MS)
      }
    }

    /**
     * Cheap structural signature of the live tree under [root]: a rolling hash of each reachable
     * node's screen bounds + class name + text length, bounded by [STABILITY_MAX_DEPTH]. Two equal
     * consecutive signatures mean the UI stopped moving; an animating node shifts its bounds and so
     * changes the hash frame-to-frame.
     */
    private fun treeSignature(root: AccessibilityNodeInfo): Long {
      var signature = SIGNATURE_SEED
      fun visit(node: AccessibilityNodeInfo, depth: Int) {
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        signature = mixNodeIntoSignature(
          signature,
          bounds.left,
          bounds.top,
          bounds.right,
          bounds.bottom,
          node.className,
          node.text,
        )
        if (depth >= STABILITY_MAX_DEPTH) return
        for (i in 0 until node.childCount) {
          val child = node.getChild(i) ?: continue
          try {
            visit(child, depth + 1)
          } finally {
            child.recycle()
          }
        }
      }
      visit(root, 0)
      return signature
    }

    private fun treeStabilityGateDisabled(): Boolean {
      val raw = System.getenv("TRAILBLAZE_DISABLE_SETTLE_TREE_STABILITY")?.lowercase()
      return raw == "1" || raw == "true"
    }

    /**
     * IME-window class-name suffix shared by the standard `SoftInputWindow` across all common
     * Android IMEs (Gboard, SwiftKey, Samsung Keyboard, Fleksy, etc.) — they all subclass
     * `android.inputmethodservice.SoftInputWindow` and inherit the suffix.
     *
     * Match is case-sensitive — Android class names are case-sensitive per the JLS, and IMEs
     * always use this exact casing. Don't lowercase before comparing.
     */
    private const val IME_SOFT_INPUT_WINDOW_SUFFIX = ".SoftInputWindow"

    /**
     * IME package fragment, anchored on both sides by dots so it matches a real package
     * boundary like `com.google.android.inputmethod.latin.Foo` but not legitimate app classes
     * that happen to contain `inputmethod` as a substring (e.g. `com.example.inputmethods.X`,
     * `com.foo.inputmethod_helpers.Y`). Anchoring on the trailing dot is the load-bearing
     * piece of correctness here.
     *
     * Match is case-sensitive — Android package names are lowercase by convention. OEM
     * keyboards that ship under unrelated package roots (e.g., `com.lge.ime.`) won't match
     * this fragment; the windows-list path ([getServiceWindows]) is the primary detection
     * for those, with this heuristic only used as a last-resort fallback.
     */
    private const val IME_PACKAGE_FRAGMENT = ".inputmethod."

    /**
     * Returns true if [className] looks like an IME window class.
     *
     * Visibility note: `internal` rather than `private` so the unit-test seam can call this
     * without instantiating an AccessibilityService — the function is otherwise module-private
     * by intent, not API. Treat as if it were `private` from a design standpoint; the relaxed
     * scope is for testability.
     */
    internal fun isImeClassName(className: String): Boolean =
      className.endsWith(IME_SOFT_INPUT_WINDOW_SUFFIX) ||
        className.contains(IME_PACKAGE_FRAGMENT)

    /**
     * Returns true when [event] is confirmed to originate from a foreground application
     * window (TYPE_APPLICATION). Looks up the window type via [getServiceWindows] when
     * possible; on devices where window enumeration is unavailable, falls back to a
     * negative IME class-name check — accept anything that doesn't look like an IME, which
     * preserves activity tracking on degraded devices rather than dropping it entirely.
     *
     * Used to gate `currentActivityClass` updates so System UI / IME / toast / overlay
     * events don't overwrite the foreground app's class.
     */
    private fun isEventFromApplicationWindow(event: AccessibilityEvent): Boolean {
      val windowId = event.windowId
      // AccessibilityEvent.getWindowId() returns a non-negative window id, or a negative
      // value when not associated with a window. Anything < 0 means we can't look up the
      // window — fall back to the class-name heuristic below.
      if (windowId >= 0) {
        val windows = getServiceWindows()
        if (!windows.isNullOrEmpty()) {
          val match = windows.firstOrNull { it.id == windowId } ?: return false
          // Strict positive: ONLY accept TYPE_APPLICATION. System UI is TYPE_SYSTEM,
          // toasts TYPE_TOAST, IME TYPE_INPUT_METHOD, overlays TYPE_ACCESSIBILITY_OVERLAY —
          // none of those are real activities and shouldn't overwrite currentActivityClass.
          return match.type == AccessibilityWindowInfo.TYPE_APPLICATION
        }
      }
      // Fallback when windows enumeration is unavailable (older OEMs, pre-init): we can't
      // confirm the type, so accept anything that doesn't *look* like an IME by class name.
      // This loses precision on degraded devices but preserves the activity-tracking
      // feature there rather than nulling it out entirely.
      val cls = event.className?.toString() ?: return false
      return !isImeClassName(cls)
    }

    /**
     * One-time guard for the `service.windows unavailable` breadcrumb in [getServiceWindows].
     * Reset in [onServiceConnected] so each service bind gets its own warning if applicable;
     * the gate prevents log spam on devices where window enumeration genuinely doesn't work.
     */
    private val windowsFallbackLogged = AtomicBoolean(false)

    /**
     * Strict IME-window detection: only returns true when [getServiceWindows] enumerates an
     * actual `TYPE_INPUT_METHOD` window. Unlike [isKeyboardVisible], does **not** fall back
     * to the focused-editable heuristic — that heuristic produces false positives when focus
     * lingers on an editable after the IME has been dismissed. Use this when the cost of a
     * false positive matters (e.g., asserting that a hideKeyboard call actually dismissed
     * the IME).
     */
    fun isImeWindowVisible(): Boolean {
      val windows = getServiceWindows() ?: return false
      return windows.any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
    }

    /**
     * Returns the screen-space bounds of the currently-visible soft IME window, or `null`
     * if no IME window is enumerated. Used by [AccessibilityDeviceManager]'s pre-tap
     * occlusion check to detect when a resolved tap coordinate is going to land on the
     * keyboard instead of the intended target — the silent-mis-tap scenario that occurs
     * on screens where the IME refuses to dismiss (e.g. Compose modals that consume BACK).
     */
    fun imeWindowBoundsInScreen(): android.graphics.Rect? {
      val windows = getServiceWindows() ?: return null
      val imeWindow = windows.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
        ?: return null
      val rect = android.graphics.Rect()
      imeWindow.getBoundsInScreen(rect)
      return rect.takeIf { !it.isEmpty }
    }

    /**
     * Companion check for [imeWindowBoundsInScreen] — returns true when the soft IME is
     * currently up according to the authoritative `dumpsys input_method` signal, even
     * when [getServiceWindows] is degraded (returns `null` under certain accessibility
     * service flags). This is the same dumpsys gate [waitForImeDismissed] uses; exposing
     * it here lets the pre-tap occlusion check fail conservatively when we can't measure
     * the IME's bounds but know it's present.
     */
    fun isImeShownAuthoritative(): Boolean = isImeShownViaDumpsys()

    /**
     * Tracks whether the most recent [hideKeyboard] call put the soft IME into
     * `SHOW_MODE_HIDDEN`. Cleared on the next dispatched action (via
     * [restoreSoftKeyboardIfPending]) so the suppression is scoped to "the moment after
     * hideKeyboard" rather than sticking globally until the service unbinds.
     */
    private val softKeyboardHideRequested = AtomicBoolean(false)

    /**
     * Reads `TRAILBLAZE_IME_DISMISS_VIA_SHOW_MODE`. When set, [hideKeyboard] routes the
     * dismissal through `SoftKeyboardController.setShowMode(SHOW_MODE_HIDDEN)` instead of
     * a synthetic BACK key — bypassing Compose `BackHandler` callbacks that otherwise eat
     * the BACK before the IME framework can hide the keyboard. Read on every call so an
     * oncall can flip it on a running daemon without restarting.
     */
    private fun imeDismissViaShowModeEnabled(): Boolean {
      val raw = System.getenv("TRAILBLAZE_IME_DISMISS_VIA_SHOW_MODE")?.lowercase()
      return raw == "1" || raw == "true"
    }

    /**
     * Restores the soft keyboard to `SHOW_MODE_AUTO` if a prior [hideKeyboard] put it in
     * `SHOW_MODE_HIDDEN`. Called at the start of [AccessibilityDeviceManager]'s next
     * dispatch and from [onDestroy]. No-op when no hide is pending or the service is gone.
     */
    internal fun restoreSoftKeyboardIfPending() {
      if (!softKeyboardHideRequested.compareAndSet(true, false)) return
      val service = accessibilityServiceInstance ?: return
      try {
        service.softKeyboardController.setShowMode(AccessibilityService.SHOW_MODE_AUTO)
      } catch (e: Exception) {
        Console.log("[hideKeyboard] restore to SHOW_MODE_AUTO failed: ${e.message}")
      }
    }

    fun hideKeyboard(): Boolean {
      // Gate dismissal on an authoritative IME-up check. Two signals, in order:
      //   1. [isImeWindowVisible] — strict windows-only, in-process and cheap.
      //   2. `dumpsys input_method` — authoritative shell fallback for environments where
      //      [getServiceWindows] is degraded (empty / SecurityException) under
      //      FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES.
      // Intentionally does NOT consider the focused-editable signal: that lingers on the
      // editable after the IME is dismissed and would cause back-to-back hideKeyboard
      // calls to navigate back out of the current screen.
      if (!isImeWindowVisible() && !isImeShownViaDumpsys()) return true
      val service = requireService()
      if (imeDismissViaShowModeEnabled()) {
        // SHOW_MODE_HIDDEN talks straight to ImeVisibilityStateComputer over the
        // accessibility client binder — never dispatches a KeyEvent, so the modal's
        // BackHandler / OnBackPressedCallback can't swallow the dismissal. Sticky until
        // restored; [restoreSoftKeyboardIfPending] flips it back on the next dispatch.
        val accepted =
          try {
            service.softKeyboardController.setShowMode(AccessibilityService.SHOW_MODE_HIDDEN)
          } catch (e: Exception) {
            Console.log("[hideKeyboard] setShowMode threw: ${e.message}")
            false
          }
        if (accepted) {
          softKeyboardHideRequested.set(true)
          return true
        }
        Console.log("[hideKeyboard] SHOW_MODE_HIDDEN rejected — falling back to GLOBAL_ACTION_BACK")
      }
      return service.performGlobalAction(GLOBAL_ACTION_BACK)
    }

    /**
     * Polls [isImeWindowVisible] for fast-fail, then always confirms with the authoritative
     * [isImeShownViaDumpsys] signal before returning. Returns true once the IME is confirmed
     * dismissed, false otherwise.
     *
     * Why this is needed: the IME dismissal animation can outlast the accessibility-event
     * settle wait. On Compose `EditText` + emulator runs, `onFinishInputView` fires ~20ms
     * after `dispatchAndAwaitSettleBlocking` returns from a GLOBAL_ACTION_BACK, so the
     * TYPE_INPUT_METHOD window is still enumerated when the post-check first runs. Polling
     * the cheap in-process check absorbs that transition; the final dumpsys gate closes
     * two gaps that the windows-only check leaves open:
     *   1. Timeout-but-actually-dismissed — enumeration stale at deadline, dumpsys confirms.
     *   2. Windows-null bypass — [isImeWindowVisible] returns false whenever
     *      `getServiceWindows()` is null (degraded under FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES),
     *      so without this gate the loop could exit "successfully" without ever observing
     *      the IME leave on a genuinely-stuck keyboard.
     */
    fun waitForImeDismissed(timeoutMs: Long): Boolean {
      PollingUtils.tryUntilSuccessOrTimeout(
        maxWaitMs = timeoutMs,
        intervalMs = 50,
        conditionDescription = "IME window absent from enumeration",
        condition = { !isImeWindowVisible() },
      )
      // Authoritative gate — one shell call per hideKeyboard regardless of poll outcome.
      return !isImeShownViaDumpsys()
    }

    private fun isImeShownViaDumpsys(): Boolean = try {
      parseDumpsysInputMethodShown(AdbCommandUtil.execShellCommand("dumpsys input_method"))
    } catch (e: Exception) {
      Console.log("[hideKeyboard] dumpsys input_method failed: ${e.message}")
      false
    }

    /**
     * Parses `dumpsys input_method` output and returns true when the WindowManager-recorded
     * IME visibility flag (`mInputShown=true`) is set on any line. Extracted from
     * [isImeShownViaDumpsys] so the parser can be exercised with captured fixture output
     * in unit tests without standing up the shell wrapper.
     *
     * Matching is line-anchored after trimming so we don't accidentally hit a substring
     * occurrence inside an unrelated diagnostic line.
     */
    internal fun parseDumpsysInputMethodShown(rawOutput: String): Boolean =
      rawOutput.lineSequence().any { it.trim().startsWith("mInputShown=true") }

    fun getCurrentLocale(): Locale = appContext.resources.configuration.getLocales().get(0)

    fun getDeviceCategory(): TrailblazeAndroidDeviceCategory =
      if (appContext.resources.configuration.smallestScreenWidthDp < 600)
        TrailblazeAndroidDeviceCategory.PHONE
      else TrailblazeAndroidDeviceCategory.TABLET

    fun getScreenDimensions(): Pair<Int, Int> {
      val windowManager = requireService().getSystemService(WINDOW_SERVICE) as WindowManager

      return if (AndroidSdkVersion.isAtLeast(30)) {
        val metrics = windowManager.currentWindowMetrics
        val bounds = metrics.bounds
        Pair(bounds.width(), bounds.height())
      } else {
        val display = windowManager.defaultDisplay
        val size = Point()
        display.getRealSize(size)
        Pair(size.x, size.y)
      }
    }

    private fun internalDispatchGesture(gesture: GestureDescription): Boolean {
      if (Looper.myLooper() == Looper.getMainLooper()) {
        throw IllegalStateException("Cannot dispatch a gesture from the main thread")
      }

      val latch = CountDownLatch(1)
      val success = AtomicBoolean(false)

      requireService()
        .dispatchGesture(
          gesture,
          object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
              success.set(true)
              latch.countDown()
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
              Console.log("Gesture was cancelled or failed.")
              success.set(false)
              latch.countDown()
            }
          },
          null,
        )

      try {
        if (!latch.await(2, TimeUnit.SECONDS)) {
          Console.log("Gesture timed out after 2 seconds.")
          success.set(false)
        }
      } catch (e: InterruptedException) {
        Console.log("Gesture wait interrupted: ${e.message}")
        success.set(false)
      }

      Thread.sleep(postGestureSettleTimeMs)
      return success.get()
    }

    fun tap(x: Int, y: Int): Boolean {
      val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
      val gesture =
        GestureDescription.Builder()
          .addStroke(GestureDescription.StrokeDescription(path, 0, 100))
          .build()
      return internalDispatchGesture(gesture)
    }

    /**
     * Dispatches `ACTION_CLICK` on the live accessibility node whose screen bounds equal
     * [targetBounds], whose `className` matches [targetClassName] (when supplied), whose
     * `viewIdResourceName` matches [targetResourceId] (when supplied), and which advertises
     * `ACTION_CLICK` in its action list. Returns `true` when such a node was located AND
     * `performAction` returned true; `false` otherwise (caller should fall back to coordinate
     * gesture dispatch).
     *
     * Identity matching is by exact bounds + className + resourceId, not by coordinate
     * hit-test: callers pass the identity of the already-resolved selector node, so this
     * routine looks up that same node by identity rather than re-running a "what's at (x, y)"
     * walk. The className tiebreaker disambiguates merged-semantics trees where a clickable
     * wrapper and a non-clickable child share bounds (e.g. `android.view.View` clickable
     * wrapper over an `android.widget.Button` inner — same rect, different classes); the
     * resourceId tiebreaker disambiguates the equivalent in Compose merged-semantics trees.
     */
    fun tapByActionClickOnBounds(
      targetBounds: Rect,
      targetClassName: String? = null,
      targetResourceId: String? = null,
    ): Boolean {
      val root = getRootNodeInfo()
      if (root == null) {
        // Service has no active window root (transient — service rebinding, permission
        // hiccup, or the window we resolved against has been torn down). Log so an oncall
        // can grep for repeated occurrences without drowning normal runs.
        Console.log("[tapByActionClickOnBounds] no live root, caller will gesture-fall-back")
        return false
      }
      return root.useRecycling { rootNode ->
        findClickableNodeWithBounds(rootNode, targetBounds, targetClassName, targetResourceId)
          ?.useRecycling { it.performAction(AccessibilityNodeInfo.ACTION_CLICK) }
          ?: false
      }
    }

    /**
     * DFS for a node whose `getBoundsInScreen` equals [targetBounds], whose `className`
     * matches [targetClassName] (when supplied), whose `viewIdResourceName` matches
     * [targetResourceId] (when supplied), and which advertises `ACTION_CLICK`. Returns null
     * when no such node exists in the subtree rooted at [node].
     *
     * Returns an independent [AccessibilityNodeInfo.obtain] copy on success so the caller
     * owns a handle that's lifecycle-independent from [node] and its descendants — the
     * recursive frames can then unconditionally recycle every `getChild` reference,
     * including the one whose subtree contained the match, without invalidating the returned
     * handle. This sidesteps both the leaked-ancestor and the double-recycle failure modes
     * the must-recycle contract is prone to with tree-walk patterns.
     *
     * Matching policy:
     * - Bounds equality is **exact** by design. A 1px shift between resolve and dispatch
     *   (mid-scroll, animation tick) means the resolved identity is stale and we want to
     *   miss + fall back to gesture rather than tap a different node whose bounds happen
     *   to overlap.
     * - `(bounds, className, resourceId)` is not strictly unique in Compose merged-semantics
     *   trees (a `Row { Button(); Button() }` layout could theoretically produce overlapping
     *   siblings with identical class+id+rect). DFS pre-order wins on collision, which
     *   matches the selector's own first-match semantic.
     */
    private fun findClickableNodeWithBounds(
      node: AccessibilityNodeInfo,
      targetBounds: Rect,
      targetClassName: String?,
      targetResourceId: String?,
    ): AccessibilityNodeInfo? {
      val nodeBounds = Rect().also(node::getBoundsInScreen)
      val classMatches = targetClassName == null || node.className?.toString() == targetClassName
      val resourceMatches = targetResourceId == null || node.viewIdResourceName == targetResourceId
      val advertisesClick =
        node.actionList?.any { it.id == AccessibilityNodeInfo.ACTION_CLICK } == true
      if (nodeBounds == targetBounds && classMatches && resourceMatches && advertisesClick) {
        return AccessibilityNodeInfo.obtain(node)
      }
      return (0 until node.childCount).firstNotNullOfOrNull { i ->
        node.getChild(i)?.useRecycling { child ->
          findClickableNodeWithBounds(child, targetBounds, targetClassName, targetResourceId)
        }
      }
    }

    /**
     * Runs [block] with this node, then recycles it. The node must not be referenced after
     * this returns. Mirrors `AutoCloseable.use()` semantics — [AccessibilityNodeInfo] does
     * not implement `AutoCloseable`, so this gives us the same "scoped resource" idiom
     * without the nested try/finally noise.
     */
    private inline fun <R> AccessibilityNodeInfo.useRecycling(
      block: (AccessibilityNodeInfo) -> R,
    ): R {
      try {
        return block(this)
      } finally {
        recycle()
      }
    }

    fun pressBack() {
      requireService().performGlobalAction(GLOBAL_ACTION_BACK)
    }

    fun pressHome() {
      requireService().performGlobalAction(GLOBAL_ACTION_HOME)
    }

    /**
     * Launches an app by package ID using the service's [Context].
     *
     * Uses [Intent.FLAG_ACTIVITY_NEW_TASK] and [Intent.FLAG_ACTIVITY_CLEAR_TASK] to clear
     * the activity task stack, giving a fresh UI start without needing shell permissions.
     */
    fun launchApp(appId: String) {
      val service = requireService()
      val intent = service.packageManager.getLaunchIntentForPackage(appId)
        ?: error("No launcher intent found for package: $appId")
      intent.addFlags(
        android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
          android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK,
      )
      service.startActivity(intent)
    }

    fun pressRecents() {
      requireService().performGlobalAction(GLOBAL_ACTION_RECENTS)
    }

    fun setClipboard(text: String) {
      val clipboardManager =
        requireService().getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
      val clip = android.content.ClipData.newPlainText("Trailblaze", text)
      clipboardManager.setPrimaryClip(clip)
    }

    fun getClipboardText(): String? {
      val clipboardManager =
        requireService().getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
      return clipboardManager.primaryClip?.getItemAt(0)?.text?.toString()
    }

    /**
     * Scrolls in the given direction using a swipe gesture.
     *
     * @param forward true to scroll forward (swipe up), false to scroll backward (swipe down)
     */
    fun scroll(forward: Boolean): Boolean {
      val (width, height) = getScreenDimensions()
      val centerX = width / 2
      val startY: Int
      val endY: Int
      if (forward) {
        startY = (height * 0.7).toInt()
        endY = (height * 0.3).toInt()
      } else {
        startY = (height * 0.3).toInt()
        endY = (height * 0.7).toInt()
      }
      return directionalSwipe(
        durationMs = 300,
        startX = centerX,
        startY = startY,
        endX = centerX,
        endY = endY,
      )
    }

    /**
     * Captures a screenshot, preferring the fast UiAutomation path when instrumentation is
     * available and falling back to the native AccessibilityService API otherwise.
     *
     * - **UiAutomation path**: No rate limit, no overhead. Requires an active instrumentation
     *   test context (e.g., AndroidJUnitRunner). Routed through
     *   [InstrumentationUtil.withUiAutomation], which always requests
     *   [UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES] (so reconnection can't destroy
     *   our running accessibility service) and transparently recovers a stale / half-connected
     *   handle (e.g. one left over from a cancelled prior session) by clearing the cached handle,
     *   reconnecting, and retrying the screenshot once before this method falls back to native.
     *   See [InstrumentationUtil.withUiAutomation] for the exact handle signatures it recovers
     *   from. Without that recovery a single wedged handle would silently degrade every
     *   subsequent screenshot to the slower native path until the next reconnect.
     *
     * - **Native fallback** ([captureScreenshotNative]): Uses the AccessibilityService's own
     *   [takeScreenshot] API (API 30+), which enforces a 333ms minimum interval. Used when
     *   the accessibility service runs standalone without instrumentation, or when UiAutomation
     *   recovery itself could not restore a working handle.
     *
     * **Important**: All code paths that obtain a [UiAutomation] reference must use
     * [UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES]. Calling `getUiAutomation()`
     * without this flag (or with flags=0) will reconnect UiAutomation and destroy the running
     * accessibility service. Going through [InstrumentationUtil.withUiAutomation] is how this
     * path guarantees the flag (and the shared stale-handle recovery) without re-deriving the
     * signature list or reflection logic here.
     */
    fun captureScreenshot(): Bitmap? {
      return try {
        InstrumentationUtil.withUiAutomation { takeScreenshot() }
      } catch (e: Exception) {
        Console.log(
          "captureScreenshot: UiAutomation unavailable (${e.message}), " +
            "falling back to native accessibility screenshot"
        )
        captureScreenshotNativeBlocking()
      }
    }

    /**
     * Captures a screenshot using the AccessibilityService's native [takeScreenshot] API.
     *
     * This API (available on API 30+) enforces a 333ms minimum interval between captures
     * (`ACCESSIBILITY_TAKE_SCREENSHOT_REQUEST_INTERVAL_TIMES_MS` in the framework). This
     * method respects that rate limit by tracking the last capture timestamp and sleeping
     * if needed.
     *
     * Prefer [captureScreenshot] which auto-selects the fastest available path. This method
     * is exposed for callers that need the native path explicitly, or for contexts where
     * UiAutomation is unavailable (e.g., standalone accessibility service without
     * instrumentation).
     */
    suspend fun captureScreenshotNative(): Bitmap? {
      if (!AndroidSdkVersion.isAtLeast(30)) {
        Console.log("captureScreenshotNative: API Level too low, screenshot not supported")
        return null
      }

      val currTimeMs = Clock.System.now().toEpochMilliseconds()
      val timeElapsedSinceLastScreenshot: Long = currTimeMs - lastNativeScreenshotTimestampMs.get()
      if (timeElapsedSinceLastScreenshot < NATIVE_SCREENSHOT_MIN_INTERVAL_MS) {
        val remainingTime = NATIVE_SCREENSHOT_MIN_INTERVAL_MS - timeElapsedSinceLastScreenshot
        if (remainingTime > 0) {
          delay(remainingTime)
        }
      }

      return suspendCancellableCoroutine { continuation ->
        requireService()
          .takeScreenshot(
            Display.DEFAULT_DISPLAY,
            { runnable: Runnable -> Thread(runnable).start() },
            object : TakeScreenshotCallback {
              override fun onSuccess(screenshotResult: ScreenshotResult) {
                lastNativeScreenshotTimestampMs.set(Clock.System.now().toEpochMilliseconds())
                val hardwareBuffer: HardwareBuffer = screenshotResult.hardwareBuffer
                val colorSpace: ColorSpace = screenshotResult.colorSpace
                val bitmap = Bitmap.wrapHardwareBuffer(hardwareBuffer, colorSpace)
                hardwareBuffer.close()
                continuation.resume(bitmap)
              }

              override fun onFailure(errorCode: Int) {
                Console.log("captureScreenshotNative failed with errorCode: $errorCode")
                continuation.resume(null)
              }
            },
          )
      }
    }

    /**
     * Framework-enforced minimum interval between native accessibility screenshots.
     * See: AccessibilityService.ACCESSIBILITY_TAKE_SCREENSHOT_REQUEST_INTERVAL_TIMES_MS
     */
    private const val NATIVE_SCREENSHOT_MIN_INTERVAL_MS = 333L

    private val lastNativeScreenshotTimestampMs =
      AtomicLong(Clock.System.now().toEpochMilliseconds() - NATIVE_SCREENSHOT_MIN_INTERVAL_MS)

    /** Blocking wrapper around [captureScreenshotNative] for use from non-coroutine contexts. */
    private fun captureScreenshotNativeBlocking(): Bitmap? = kotlinx.coroutines.runBlocking {
      captureScreenshotNative()
    }

    fun longPress(x: Int, y: Int, durationMs: Long = 500): Boolean {
      val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }

      val gesture =
        GestureDescription.Builder()
          .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
          .build()

      return internalDispatchGesture(gesture)
    }

    fun directionalSwipe(
      durationMs: Long,
      startX: Int,
      startY: Int,
      endX: Int,
      endY: Int,
    ): Boolean {
      val start = Point(startX, startY)
      val end = Point(endX, endY)

      val path =
        Path().apply {
          moveTo(start.x.toFloat(), start.y.toFloat())
          lineTo(end.x.toFloat(), end.y.toFloat())
        }

      val gesture =
        GestureDescription.Builder()
          .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
          .build()
      return internalDispatchGesture(gesture)
    }

    /**
     * Reports keyboard visibility by checking for an IME window in the window list — a stronger
     * signal than the previous "focused editable node" heuristic, which produced false positives
     * when focus lingered after dismissal. Falls back to the focused-editable check if windows
     * aren't enumerable (e.g., older OEMs or pre-init).
     */
    fun isKeyboardVisible(): Boolean {
      val windows = getServiceWindows()
      if (!windows.isNullOrEmpty()) {
        return windows.any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
      }
      val root = requireService().rootInActiveWindow ?: return false
      val focusedNode = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
      val visible = focusedNode?.isEditable == true
      focusedNode?.recycle()
      root.recycle()
      return visible
    }

    fun inputText(text: String): Boolean {
      if (Looper.myLooper() == Looper.getMainLooper()) {
        throw IllegalStateException("Cannot run from main thread")
      }
      // Empty input is a no-op; verifying via focused-text-changed would never succeed.
      if (text.isEmpty()) return true

      val initialFocusedText = readFocusedEditableText() ?: run {
        Console.log("No editable, focused node found in hierarchy")
        return false
      }

      // Try the fast ACTION_SET_TEXT path first; verify the field actually changed.
      if (
        tryDispatchActionSetText(text) &&
        focusedTextChangedFrom(initialFocusedText, VERIFY_POLL_TIMEOUT_MS)
      ) {
        return true
      }

      // Fall back to keystroke synthesis. Some masked EditTexts (e.g., payment-form
      // MM/YY, CVV, ZIP fields) silently reject ACTION_SET_TEXT in their TextWatcher
      // pipeline and only accept real per-character KeyEvents.
      Console.log(
        "inputText (length=${text.length}) ACTION_SET_TEXT did not land; " +
          "falling back to keystroke synthesis."
      )
      return performInputTextWithVerifyAndRetry(text, initialFocusedText)
    }

    /**
     * Dispatches [text] via `ACTION_SET_TEXT` on the focused editable node. Returns the
     * action's boolean result (or false when no focused editable exists). Callers must
     * verify the field actually changed via [focusedTextChangedFrom] — many masked
     * EditTexts return true here while silently rejecting the CharSequence.
     */
    private fun tryDispatchActionSetText(text: String): Boolean {
      val root = getApplicationWindowRoot() ?: return false
      return try {
        val editableNode = findFocusedEditableNode(root) ?: return false
        try {
          val args =
            Bundle().apply {
              putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                text,
              )
            }
          editableNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        } finally {
          editableNode.recycle()
        }
      } finally {
        root.recycle()
      }
    }

    /**
     * Read-only access to the currently focused editable field's text. Returns null when no
     * focused editable exists in the application window. Returns "" for an empty/hint-state
     * field — the caller distinguishes by treating the initial text as the baseline to compare
     * post-input.
     */
    private fun readFocusedEditableText(): String? {
      val root = getApplicationWindowRoot() ?: run {
        Console.log("getApplicationWindowRoot returned null")
        return null
      }
      return try {
        val editableNode = findFocusedEditableNode(root) ?: return null
        try {
          editableNode.text?.toString().orEmpty()
        } finally {
          editableNode.recycle()
        }
      } finally {
        root.recycle()
      }
    }

    /**
     * Dispatches [text] via [InstrumentationUtil.inputTextByTyping] and verifies the focused
     * field's text changed afterward. Retries once if the first dispatch was swallowed by an
     * IME-not-ready race (observed on some payment-form postal fields). Returns true only when
     * the field's text actually changed; returns false if both attempts fail to land or the
     * keystroke loop throws.
     */
    private fun performInputTextWithVerifyAndRetry(text: String, baselineText: String): Boolean {
      val attempts = 2
      for (attempt in 1..attempts) {
        try {
          InstrumentationUtil.inputTextByTyping(text)
        } catch (t: Throwable) {
          Console.log(
            "inputText (length=${text.length}) via inputTextByTyping failed " +
              "(attempt $attempt/$attempts): ${t::class.java.simpleName}: ${t.message}"
          )
          return false
        }
        if (focusedTextChangedFrom(baselineText, VERIFY_POLL_TIMEOUT_MS)) return true
        if (attempt < attempts) {
          Console.log(
            "inputText (length=${text.length}) first dispatch did not change focused text; " +
              "redispatching once."
          )
        }
      }
      return false
    }

    /**
     * Polls the focused editable field's text for up to [timeoutMs], returning true as soon as
     * its text differs from [baselineText]. Returns false if the timeout expires with no
     * change, or if the focused editable disappears (e.g., field lost focus to a navigation).
     * The disappearance case returns false because we can't safely redispatch — there's no
     * field to type into.
     */
    private fun focusedTextChangedFrom(baselineText: String, timeoutMs: Long): Boolean {
      val deadline = Clock.System.now().toEpochMilliseconds() + timeoutMs
      while (Clock.System.now().toEpochMilliseconds() < deadline) {
        val current = readFocusedEditableText() ?: return false
        if (current != baselineText) return true
        Thread.sleep(VERIFY_POLL_INTERVAL_MS)
      }
      return false
    }

    /**
     * How long [focusedTextChangedFrom] polls before declaring the keystrokes unlanded.
     * 500ms covers the worst observed dispatch-to-text-applied latency on a slow payment-form
     * postal field (a first dispatch took ~515ms before the field accepted the next try)
     * without padding the success path noticeably — text changes normally land within one
     * or two poll intervals.
     */
    private const val VERIFY_POLL_TIMEOUT_MS = 500L

    /**
     * Poll cadence inside [focusedTextChangedFrom]. 50ms gives ~10 reads inside the timeout
     * window — enough for fast detection of a successful first burst while keeping the cost
     * of each read (a tree walk via [findFocusedEditableNode]) bounded.
     */
    private const val VERIFY_POLL_INTERVAL_MS = 50L

    /**
     * Searches the tree rooted at [node] for a focused, editable node.
     *
     * Non-matching child references are recycled to avoid resource leaks.
     * The matching node is returned un-recycled — the caller must recycle
     * it (or its root) when done.
     */
    private fun findFocusedEditableNode(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
      if (node.isEditable && node.isFocused) {
        return node
      }

      for (i in 0 until node.childCount) {
        val child = node.getChild(i) ?: continue
        val result = findFocusedEditableNode(child)
        if (result != null) return result
        // Recycle children that didn't contain the match
        child.recycle()
      }
      return null
    }

    fun eraseText(charactersToErase: Int): Boolean {
      // Same window-routing fix as [inputText]: the EditText is in the app window, not the IME.
      val root = getApplicationWindowRoot() ?: return false
      return try {
        val editableNode =
          findFocusedEditableNode(root)
            ?: run {
              Console.log("No editable, focused node found in hierarchy")
              return false
            }

        try {
          with(editableNode) {
            val currentText = text?.toString() ?: return false
            if (!isEditable) return false
            if (!isFocused) {
              performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            }

            val newText = currentText.dropLast(charactersToErase.coerceAtMost(currentText.length))
            val args =
              Bundle().apply {
                putCharSequence(
                  AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                  newText,
                )
              }
            performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
          }
        } finally {
          editableNode.recycle()
        }
      } finally {
        root.recycle()
      }
    }
  }

  override fun onInterrupt() {}
}

private const val FNV_PRIME = 1099511628211L

/**
 * Pure per-node mixing for the capture-time tree-stability gate (see the service's
 * `treeSignature` / `awaitTreeStable`). Folds one node's screen bounds + class + text length into
 * a rolling FNV-style hash. Bounds-sensitive: any change to a node's position or size changes the
 * result, so an animating tree yields a different signature frame-to-frame and a settled tree
 * yields a stable one. Side-effect-free so it is unit-testable without a device (see
 * [TreeSignatureTest]).
 */
internal fun mixNodeIntoSignature(
  accumulator: Long,
  left: Int,
  top: Int,
  right: Int,
  bottom: Int,
  className: CharSequence?,
  text: CharSequence?,
): Long {
  var hash = accumulator
  for (edge in intArrayOf(left, top, right, bottom)) {
    hash = (hash xor edge.toLong()) * FNV_PRIME
  }
  hash = (hash xor (className?.toString()?.hashCode()?.toLong() ?: 0L)) * FNV_PRIME
  hash = (hash xor (text?.length?.toLong() ?: 0L)) * FNV_PRIME
  return hash
}

