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
import android.hardware.HardwareBuffer
import android.os.Bundle
import android.os.Looper
import android.view.Display
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.test.platform.app.InstrumentationRegistry
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
import xyz.block.trailblaze.android.AndroidSdkVersion
import xyz.block.trailblaze.devices.TrailblazeAndroidDeviceCategory
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.util.Console

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
      AccessibilityEvent.TYPE_WINDOWS_CHANGED -> {
        lastUiEventTimestampMs = Clock.System.now().toEpochMilliseconds()
      }
    }
  }

  override fun onDestroy() {
    super.onDestroy()
    accessibilityServiceInstance = null
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

    fun getViewHierarchy(): TreeNode? {
      val rootNode = getApplicationWindowRoot() ?: return null
      return try {
        rootNode.toTreeNode()
      } finally {
        rootNode.recycle()
      }
    }

    /** Returns the raw [AccessibilityNodeInfo] root for direct, high-fidelity tree capture. */
    fun getRootNodeInfo(): android.view.accessibility.AccessibilityNodeInfo? =
      getApplicationWindowRoot()

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
     * Returns true only when an IME window is *confirmed* present via [getServiceWindows].
     * Distinct from [isKeyboardVisible], which falls back to a less reliable
     * focused-editable heuristic when window enumeration is unavailable. Use this when an
     * action would have a side effect on a false positive — e.g., GLOBAL_ACTION_BACK
     * navigating back when the keyboard isn't actually showing.
     */
    private fun isImeWindowConfirmed(): Boolean {
      val windows = getServiceWindows() ?: return false
      return windows.any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
    }

    /**
     * One-time guard for the `service.windows unavailable` breadcrumb in [getServiceWindows].
     * Reset in [onServiceConnected] so each service bind gets its own warning if applicable;
     * the gate prevents log spam on devices where window enumeration genuinely doesn't work.
     */
    private val windowsFallbackLogged = AtomicBoolean(false)

    fun hideKeyboard(): Boolean {
      // Two-tier strategy because the cost of a false positive matters:
      //
      // 1. If we have *high confidence* an IME is up — confirmed via the windows list —
      //    use GLOBAL_ACTION_BACK. That's the documented way to dismiss the IME from an
      //    accessibility service; the old ACTION_CLEAR_FOCUS approach was a no-op against
      //    most modern soft keyboards (focus loss doesn't retract the IME), which is what
      //    made the V3 trail loop on hideKeyboard.
      //
      // 2. If we don't have window enumeration (older OEMs, pre-init), [isKeyboardVisible]
      //    falls back to a focused-editable heuristic that can return true after the IME
      //    has already been dismissed (focus lingers). Pressing BACK in that state would
      //    navigate out of the app instead of dismissing a keyboard that isn't there.
      //    Drop to ACTION_CLEAR_FOCUS as a low-risk best-effort: harmless if redundant,
      //    never navigates.
      if (isImeWindowConfirmed()) {
        return requireService().performGlobalAction(GLOBAL_ACTION_BACK)
      }
      // Best-effort fallback: clear focus on the editable that has it, if any. Returns
      // true when there's no focused editable (treating "no keyboard to hide" as success
      // to match the historical contract).
      val root = getApplicationWindowRoot() ?: return true
      return try {
        val focusedNode = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        focusedNode?.performAction(AccessibilityNodeInfo.ACTION_CLEAR_FOCUS)
        focusedNode?.recycle()
        true
      } finally {
        root.recycle()
      }
    }

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
     *   test context (e.g., AndroidJUnitRunner). Uses
     *   [UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES] to prevent reconnection from
     *   destroying our running accessibility service.
     *
     * - **Native fallback** ([captureScreenshotNative]): Uses the AccessibilityService's own
     *   [takeScreenshot] API (API 30+), which enforces a 333ms minimum interval. Used when
     *   the accessibility service runs standalone without instrumentation.
     *
     * **Important**: All code paths that obtain a [UiAutomation] reference must use
     * [UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES]. Calling `getUiAutomation()`
     * without this flag (or with flags=0) will reconnect UiAutomation and destroy the running
     * accessibility service. The [OnDeviceAccessibilityServiceSetup] Configurator ensures
     * [UiDevice] uses the correct flags, but any direct `getUiAutomation()` calls elsewhere
     * must also include the flag.
     */
    fun captureScreenshot(): Bitmap? {
      return try {
        InstrumentationRegistry.getInstrumentation()
          .getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
          .takeScreenshot()
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

      // The focused EditText lives in the application window, not in the IME's `SoftInputWindow`
      // that becomes "active" when the keyboard is shown. Use the application root so
      // findFocusedEditableNode actually finds the field the user tapped.
      val root =
        getApplicationWindowRoot()
          ?: run {
            Console.log("getApplicationWindowRoot returned null for inputText")
            return false
          }

      return try {
        val editableNode =
          findFocusedEditableNode(root)
            ?: run {
              Console.log("No editable, focused node found in hierarchy")
              return false
            }

        try {
          val args =
            Bundle().apply {
              putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
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

