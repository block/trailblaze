package xyz.block.trailblaze.android.accessibility

import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.net.Uri
import android.net.wifi.WifiManager
import android.telephony.TelephonyManager
import kotlinx.datetime.Clock
import xyz.block.trailblaze.AdbCommandUtil
import xyz.block.trailblaze.InstrumentationUtil.withInstrumentation
import xyz.block.trailblaze.InstrumentationUtil.withUiDevice
import xyz.block.trailblaze.android.InstrumentationArgUtil
import xyz.block.trailblaze.api.DriverDispatch
import xyz.block.trailblaze.api.DriverNodeDetail
import xyz.block.trailblaze.api.ScreenState
import xyz.block.trailblaze.api.TapDispatchRoute
import xyz.block.trailblaze.api.TargetTypeGuard
import xyz.block.trailblaze.api.TrailblazeNode
import xyz.block.trailblaze.api.TrailblazeNodeSelector
import xyz.block.trailblaze.api.TrailblazeNodeSelectorResolver
import xyz.block.trailblaze.devices.TrailblazeDeviceClassifier
import xyz.block.trailblaze.model.TapRouteOverride
import xyz.block.trailblaze.util.Console

/**
 * Manages device interaction purely through the Android accessibility framework.
 *
 * This is the Android equivalent of `PlaywrightPageManager` — a single entry point for all device
 * operations that completely bypasses Maestro. Each action method dispatches the gesture via
 * [TrailblazeAccessibilityService] and waits for the UI to settle using event-based detection
 * before returning, ensuring the screen is stable for the next operation.
 *
 * Supports two interaction modes:
 * 1. **Action-based** via [execute]: takes [AccessibilityAction] objects for structured dispatch
 * 2. **Direct methods** (tap, swipe, etc.): convenience wrappers for tool implementations
 *
 * Designed for:
 * - **Trail playback** via [AccessibilityTrailRunner]: fast sequential action dispatch
 * - **AI agent tools**: direct device control with built-in settle guarantees
 */
class AccessibilityDeviceManager(
  private val deviceClassifiers: List<TrailblazeDeviceClassifier> = emptyList(),
  // Settle primitive — [defaultAwaitSettle] in production. Exposed as an injectable lambda
  // so unit tests can substitute a counting stub without standing up an instrumentation context.
  // Lambda invocation cost is negligible compared to the settle itself.
  private val awaitSettle: () -> Unit = { defaultAwaitSettle() },
  // Per-session template context surfaced to every internal resolve() call so selectors
  // carrying `{{target.appId}}` placeholders expand correctly. Null when the agent
  // wasn't constructed with a target (target-agnostic rules / unit-test fixtures);
  // selectors authored without templates work either way.
  private val templateContext: xyz.block.trailblaze.api.TargetTemplateContext? = null,
) : DriverDispatch {

  companion object {
    /** Polling interval for element resolution loops. Balances responsiveness with CPU usage. */
    private const val POLL_INTERVAL_MS = 100L

    /**
     * Upper bound for the [hideKeyboard] post-check poll. The Compose EditText + emulator
     * dismissal animation completes well under 100ms in practice; 1.5s is a generous
     * ceiling that still fails fast on a genuinely-stuck keyboard.
     */
    private const val HIDE_KEYBOARD_POST_CHECK_TIMEOUT_MS = 1500L

    /**
     * Post-action settle: quiet window required on the service's UI-event stream before the
     * action is considered settled. Matches the capture-time gates' quiet window — the
     * platform's `waitForIdle()` hardcodes 500ms, which was the single largest per-action cost
     * in recorded replay (https://github.com/block/trailblaze/issues/210 — ~400–900ms of every gesture/inputText/pressKey).
     */
    private const val SETTLE_QUIET_WINDOW_MS = 100L

    /**
     * Post-action settle: how long to wait for the action's FIRST UI event before concluding the
     * action produced no reaction. Longer than [waitForSettled]'s 50ms default so a slow-starting
     * transition (activity launch, heavyweight recompose) has room to emit its first event — a
     * grace the legacy `waitForIdle()` never offered.
     */
    private const val SETTLE_GRACE_PERIOD_MS = 150L

    /** Hard cap on the post-action settle (same bound the legacy path used in spirit; the
     *  platform's `waitForIdle()` global timeout is 10s). */
    private const val SETTLE_TIMEOUT_MS = 5_000L

    /**
     * Production settle primitive run after every dispatched action (see
     * [dispatchAndAwaitSettleBlocking]): waits for the accessibility event stream to go quiet via
     * [TrailblazeAccessibilityService.waitForSettled] — the same signal the capture-time gates
     * use — instead of `UiDevice.waitForIdle()`, whose hardcoded 500ms quiet window made every
     * action pay ~½s+ after its last UI event (https://github.com/block/trailblaze/issues/210).
     * Late-arriving UI reactions stay covered: [SETTLE_GRACE_PERIOD_MS] waits for the reaction to
     * *start*, and every subsequent capture re-gates on event-quiet + `awaitTreeStable` before a
     * snapshot is trusted.
     *
     * Kill-switch: `TRAILBLAZE_SETTLE_VIA_WAIT_FOR_IDLE=1` (read per call, flippable on a running
     * daemon) restores the legacy `UiDevice.waitForIdle()` primitive. Also falls back to it when
     * the accessibility service isn't bound — without the service there is no event stream to
     * observe, and [TrailblazeAccessibilityService.waitForSettled] would see a stale
     * `lastUiEventTimestampMs` and return "settled" instantly.
     */
    internal fun defaultAwaitSettle() {
      val heuristic: (earlyExit: () -> Boolean) -> Boolean = heuristic@{ earlyExit ->
        val useLegacy = envFlagEnabled("TRAILBLAZE_SETTLE_VIA_WAIT_FOR_IDLE")
        if (useLegacy || !TrailblazeAccessibilityService.isServiceRunning()) {
          // `UiDevice.waitForIdle()` is a single blocking call that can't honor `earlyExit`
          // mid-flight, so if the idle detector already won by the time this arm runs, skip it entirely
          // rather than start a non-cancellable wait that would keep poking UiAutomator after the
          // race is decided. (Narrows, doesn't eliminate, the window — the idle detector can still win
          // during the call — but the common already-idle case answers in ~10ms.)
          if (earlyExit()) return@heuristic false
          // Log which primitive ran so a triage can tell the branches apart — the event-quiet
          // path emits its own "UI settled after …" line; without this the fallback is silent.
          Console.log(
            "[settle] via UiDevice.waitForIdle() — " +
              if (useLegacy) "TRAILBLAZE_SETTLE_VIA_WAIT_FOR_IDLE set" else "accessibility service not bound",
          )
          withUiDevice { waitForIdle() }
          // waitForIdle() has no timeout verdict — returning IS its "settled".
          true
        } else {
          TrailblazeAccessibilityService.waitForSettled(
            quietWindowMs = SETTLE_QUIET_WINDOW_MS,
            maxGracePeriodMs = SETTLE_GRACE_PERIOD_MS,
            timeoutMs = SETTLE_TIMEOUT_MS,
            earlyExit = earlyExit,
          )
        }
      }
      // EXPERIMENTAL: in-process in-process idle (see [InProcessIdleSettleClient]) — opt-in via
      // `setprop debug.trailblaze.settle.inProcessIdle 1`. Raced against the standard settle so the
      // idle detector can only ever make settling FASTER; a missing/hung idle detector never breaks a run.
      if (InProcessIdleSettleClient.isEnabled()) {
        val winner =
          InProcessIdleSettleClient.raceIdleAgainstHeuristic(SETTLE_TIMEOUT_MS, heuristic = heuristic)
        Console.log("[settle] post-action via $winner")
        return
      }
      heuristic { false }
    }

    /** True when env flag [name] is set to `1`/`true` (case-insensitive). Read per call, never cached. */
    internal fun envFlagEnabled(name: String): Boolean {
      val raw = System.getenv(name)?.lowercase()
      return raw == "1" || raw == "true"
    }
  }

  // --- Screen state ---

  /**
   * Captures the current screen state after waiting for the UI to settle.
   *
   * Follows the same pattern as `PlaywrightBrowserManager.getScreenState()` — calls
   * [waitForReady] before capturing to ensure the snapshot reflects a stable UI.
   */
  fun getScreenState(): ScreenState {
    waitForReady()
    return AccessibilityServiceScreenState(
      deviceClassifiers = deviceClassifiers,
      captureSecondaryTree = InstrumentationArgUtil.shouldCaptureSecondaryTree(),
    )
  }

  /**
   * Captures screen state for logging without waiting for settle. Useful for recording the
   * immediate state after an action without the settle overhead.
   */
  fun captureScreenStateForLogging(): ScreenState {
    return AccessibilityServiceScreenState(
      deviceClassifiers = deviceClassifiers,
      captureSecondaryTree = InstrumentationArgUtil.shouldCaptureSecondaryTree(),
      // EXPERIMENTAL (inprocess-idle mode only): honor this method's "immediate state, no settle
      // overhead" contract by skipping the capture-time stability gate too — this snapshot is
      // for the session log, not for selector resolution. NOTE this deliberately couples a
      // capture-side behavior to the settle sysprop: enabling `debug.trailblaze.settle.inProcessIdle`
      // is opting into "extreme speed mode" as a package, and this logging-only path is the one
      // capture spot where a mid-transition tree is acceptable. Selector-resolving captures
      // (via [getScreenState]/[waitForReady]) keep the stability gate in both modes.
      awaitStableTree = !InProcessIdleSettleClient.isEnabled(),
    )
  }

  /**
   * Waits for the UI to settle using accessibility event-based detection.
   *
   * Equivalent to `PlaywrightBrowserManager.waitForPageReady()` — uses a debounce on
   * accessibility events rather than DOM mutations.
   */
  fun waitForReady(timeoutMs: Long = 5_000L) {
    // EXPERIMENTAL inprocess-idle race (see [InProcessIdleSettleClient]): settle on whichever
    // answers first — true main-thread idle or the standard event-quiet wait.
    if (InProcessIdleSettleClient.isEnabled()) {
      val winner = InProcessIdleSettleClient.raceIdleAgainstHeuristic(timeoutMs) { earlyExit ->
        TrailblazeAccessibilityService.waitForSettled(timeoutMs = timeoutMs, earlyExit = earlyExit)
      }
      Console.log("[settle] waitForReady via $winner")
      return
    }
    TrailblazeAccessibilityService.waitForSettled(timeoutMs = timeoutMs)
  }

  /**
   * Waits for the UI tree to change relative to [baselineEventTs] (captured at the caller's
   * entry), then settles for [quietWindowMs]. Returns the change outcome, distinguishing an
   * already-settled entry from a change-then-settle and from a timeout.
   */
  fun waitForChange(
    baselineEventTs: Long,
    quietWindowMs: Long,
    timeoutMs: Long,
  ): TrailblazeAccessibilityService.WaitForChangeOutcome =
    TrailblazeAccessibilityService.waitForChangeSince(
      baselineEventTs = baselineEventTs,
      quietWindowMs = quietWindowMs,
      timeoutMs = timeoutMs,
    )

  // --- Action dispatch ---

  /**
   * Result of executing an accessibility action, containing resolved coordinates
   * for logging and visualization.
   */
  data class ExecutionResult(
    val resolvedX: Int? = null,
    val resolvedY: Int? = null,
    val dispatchRoute: TapDispatchRoute? = null,
  )

  /**
   * Executes an [AccessibilityAction] and waits for the UI to settle.
   *
   * This is the primary entry point for structured action dispatch. The trail runner and tools
   * should use this method rather than calling individual gesture methods directly.
   *
   * Returns an [ExecutionResult] with resolved coordinates for logging/visualization.
   */
  fun execute(action: AccessibilityAction): ExecutionResult {
    Console.log("Executing: ${action.description}")
    return when (action) {
      is AccessibilityAction.Tap -> {
        tap(action.x, action.y)
        ExecutionResult(resolvedX = action.x, resolvedY = action.y)
      }
      is AccessibilityAction.TapRelative -> {
        val (width, height) = getScreenDimensions()
        val resolvedX = width * action.percentX / 100
        val resolvedY = height * action.percentY / 100
        tap(resolvedX, resolvedY)
        ExecutionResult(resolvedX = resolvedX, resolvedY = resolvedY)
      }
      is AccessibilityAction.LongPress -> {
        longPress(action.x, action.y, action.durationMs)
        ExecutionResult(resolvedX = action.x, resolvedY = action.y)
      }
      is AccessibilityAction.Swipe -> {
        swipe(action.startX, action.startY, action.endX, action.endY, action.durationMs)
        ExecutionResult()
      }
      is AccessibilityAction.SwipeDirection -> {
        val (width, height) = getScreenDimensions()
        executeSwipeDirection(action.direction, width, height, action.durationMs)
        ExecutionResult()
      }
      is AccessibilityAction.ScrollForward -> {
        scroll(forward = true)
        ExecutionResult()
      }
      is AccessibilityAction.ScrollBackward -> {
        scroll(forward = false)
        ExecutionResult()
      }
      is AccessibilityAction.InputText -> {
        inputText(action.text)
        ExecutionResult()
      }
      is AccessibilityAction.EraseText -> {
        eraseText(action.characters)
        ExecutionResult()
      }
      is AccessibilityAction.PressBack -> {
        pressBack()
        ExecutionResult()
      }
      is AccessibilityAction.PressHome -> {
        pressHome()
        ExecutionResult()
      }
      is AccessibilityAction.PressKey -> {
        AdbCommandUtil.pressKey(action.code)
        ExecutionResult()
      }
      is AccessibilityAction.HideKeyboard -> {
        hideKeyboard()
        ExecutionResult()
      }
      is AccessibilityAction.WaitForSettle -> {
        waitForReady(action.timeoutMs)
        ExecutionResult()
      }
      is AccessibilityAction.LaunchApp -> {
        executeLaunchApp(action)
        ExecutionResult()
      }
      is AccessibilityAction.SetClipboard -> {
        TrailblazeAccessibilityService.setClipboard(action.text)
        ExecutionResult()
      }
      is AccessibilityAction.PasteText -> {
        val text = TrailblazeAccessibilityService.getClipboardText()
        if (text != null) {
          inputText(text)
        }
        ExecutionResult()
      }
      is AccessibilityAction.CopyTextFrom -> executeCopyTextFrom(action)
      is AccessibilityAction.StopApp -> {
        AdbCommandUtil.forceStopApp(action.appId)
        ExecutionResult()
      }
      is AccessibilityAction.KillApp -> {
        AdbCommandUtil.forceStopApp(action.appId)
        ExecutionResult()
      }
      is AccessibilityAction.ClearState -> {
        AdbCommandUtil.clearPackageData(action.appId)
        ExecutionResult()
      }
      is AccessibilityAction.OpenLink -> {
        executeOpenLink(action)
        ExecutionResult()
      }
      is AccessibilityAction.SetOrientation -> {
        AdbCommandUtil.execShellCommand("settings put system accelerometer_rotation 0")
        AdbCommandUtil.execShellCommand("settings put system user_rotation ${action.rotation}")
        ExecutionResult()
      }
      is AccessibilityAction.SetAirplaneMode -> {
        executeSetAirplaneMode(action.enabled)
        ExecutionResult()
      }
      is AccessibilityAction.ToggleAirplaneMode -> {
        executeSetAirplaneMode(!isAirplaneModeEnabled())
        ExecutionResult()
      }
      is AccessibilityAction.ScrollUntilVisible -> executeScrollUntilVisible(action)
      is AccessibilityAction.TapOnElement -> executeTapOnElement(action)
      is AccessibilityAction.AssertVisible -> executeAssertVisible(action)
      is AccessibilityAction.AssertNotVisible -> executeAssertNotVisible(action)
    }
  }

  // --- Gestures ---

  /**
   * [DriverDispatch] implementation for the Android accessibility path. Suspend-friendly form
   * for cross-driver polymorphic callers; the per-gesture `fun tap()` / `fun swipe()` / etc.
   * methods below use the private [dispatchAndAwaitSettleBlocking] variant to avoid forcing a
   * coroutine context onto blocking call sites.
   *
   * The body never actually suspends — it invokes [action] (which may suspend), then calls the
   * blocking [awaitSettle] primitive inline. The settle runs whether [action] returns normally
   * or throws (see [DriverDispatch] kdoc for the exception contract).
   */
  override suspend fun <R> dispatchAndAwaitSettle(action: suspend () -> R): R = try {
    action()
  } finally {
    awaitSettle()
  }

  /**
   * Blocking variant of [dispatchAndAwaitSettle] for the per-gesture `fun` methods. Same
   * exception-safe shape (`try { action() } finally { awaitSettle() }`) with a non-suspend
   * signature so `tap()` / `swipe()` / etc. and their callers stay outside any coroutine context.
   *
   * Settles via [defaultAwaitSettle] — event-quiet detection on the accessibility service's own
   * stream with a 100ms quiet window, not a blind sleep. See its kdoc for why this replaced the
   * platform `UiDevice.waitForIdle()` (500ms hardcoded quiet window — the largest per-action cost
   * in recorded replay) and for the `TRAILBLAZE_SETTLE_VIA_WAIT_FOR_IDLE=1` kill-switch.
   */
  private inline fun <R> dispatchAndAwaitSettleBlocking(action: () -> R): R {
    // If a prior hideKeyboard() left the soft IME in SHOW_MODE_HIDDEN, restore SHOW_MODE_AUTO
    // before the next dispatch so subsequent inputText/tap actions see a normally-behaving IMF.
    TrailblazeAccessibilityService.restoreSoftKeyboardIfPending()
    return try {
      action()
    } finally {
      awaitSettle()
    }
  }

  /**
   * Taps at the given coordinates and waits for the UI to settle.
   *
   * Fails loudly (see [failIfGestureNotDispatched]) rather than returning normally when the
   * underlying `dispatchGesture()` was cancelled or timed out, so callers — including
   * [AccessibilityDeviceManager.execute]'s direct [AccessibilityAction.Tap] /
   * [AccessibilityAction.TapRelative] dispatch — can't mistake a no-op gesture for a completed one.
   */
  fun tap(x: Int, y: Int) = dispatchAndAwaitSettleBlocking {
    failIfGestureNotDispatched(TrailblazeAccessibilityService.tap(x, y), "tap at ($x, $y)")
  }

  /** Long-presses at the given coordinates and waits for the UI to settle. Fails loudly on a
   *  cancelled/timed-out gesture — see [tap]'s kdoc. */
  fun longPress(x: Int, y: Int, durationMs: Long = 500L) = dispatchAndAwaitSettleBlocking {
    failIfGestureNotDispatched(
      TrailblazeAccessibilityService.longPress(x, y, durationMs),
      "long-press at ($x, $y)",
    )
  }

  /** Swipes between two points and waits for the UI to settle. Fails loudly on a
   *  cancelled/timed-out gesture — see [tap]'s kdoc. */
  fun swipe(startX: Int, startY: Int, endX: Int, endY: Int, durationMs: Long) =
    dispatchAndAwaitSettleBlocking {
      failIfGestureNotDispatched(
        TrailblazeAccessibilityService.directionalSwipe(durationMs, startX, startY, endX, endY),
        "swipe from ($startX, $startY) to ($endX, $endY)",
      )
    }

  /** Scrolls forward (up) or backward (down) and waits for the UI to settle. Fails loudly on a
   *  cancelled/timed-out gesture — see [tap]'s kdoc. */
  fun scroll(forward: Boolean) = dispatchAndAwaitSettleBlocking {
    failIfGestureNotDispatched(TrailblazeAccessibilityService.scroll(forward), "scroll(forward=$forward)")
  }

  /**
   * Throws when [dispatched] is `false` — i.e. the accessibility service's `dispatchGesture()`
   * was cancelled or didn't report completion within its timeout (see
   * `TrailblazeAccessibilityService.internalDispatchGesture`), meaning nothing happened on
   * screen. Every raw gesture dispatch in this class funnels through here so a cancelled/timed-out
   * gesture surfaces as a real `TrailblazeToolResult.Error` (via `AccessibilityTrailRunner
   * .runActions`'s existing exception handling) instead of silently reporting success with zero
   * device effect — the bug this check exists to catch.
   *
   * `internal` (not `private`) so [FailIfGestureNotDispatchedTest] can exercise this pure
   * boolean-check-and-throw logic directly, without instrumentation — mirrors [planActionClickRoute]'s
   * testability rationale.
   */
  internal fun failIfGestureNotDispatched(dispatched: Boolean, description: String) {
    if (!dispatched) {
      error(
        "Gesture dispatch failed or was cancelled ($description) — the accessibility service's " +
          "dispatchGesture() did not report completion within its timeout.",
      )
    }
  }

  // --- Text input ---

  /** Sets text on the focused editable node and waits for the UI to settle. */
  fun inputText(text: String) = dispatchAndAwaitSettleBlocking {
    TrailblazeAccessibilityService.inputText(text)
  }

  /** Erases characters from the focused editable node and waits for the UI to settle. */
  fun eraseText(charactersToErase: Int) = dispatchAndAwaitSettleBlocking {
    TrailblazeAccessibilityService.eraseText(charactersToErase)
  }

  // --- Navigation ---

  /** Presses the back button via accessibility global action and waits for settle. */
  fun pressBack() = dispatchAndAwaitSettleBlocking {
    TrailblazeAccessibilityService.pressBack()
  }

  /** Presses the home button via accessibility global action and waits for settle. */
  fun pressHome() = dispatchAndAwaitSettleBlocking {
    TrailblazeAccessibilityService.pressHome()
  }

  // --- App lifecycle ---

  /**
   * Launches an app via the accessibility service's Context.
   *
   * Uses [TrailblazeAccessibilityService.launchApp] which starts the app with
   * [Intent.FLAG_ACTIVITY_CLEAR_TASK] for a fresh UI start. Shell commands like
   * `pm clear` and `am force-stop` require shell-level permissions that aren't
   * available from the instrumentation app process, so we rely on intent flags instead.
   */
  private fun executeLaunchApp(action: AccessibilityAction.LaunchApp) = dispatchAndAwaitSettleBlocking {
    Console.log("Launching ${action.appId} via Context.startActivity()")
    TrailblazeAccessibilityService.launchApp(action.appId)
  }

  // --- Clipboard ---

  /**
   * Resolves an element via [TrailblazeNodeSelector], reads its text, and sets the clipboard.
   * Mirrors Maestro Orchestra's [CopyTextFromCommand] behavior.
   */
  private fun executeCopyTextFrom(action: AccessibilityAction.CopyTextFrom): ExecutionResult {
    val startTime = Clock.System.now().toEpochMilliseconds()
    while (Clock.System.now().toEpochMilliseconds() - startTime < action.timeoutMs) {
      val tree = getAccessibilityTree()
      if (tree != null) {
        val result = resolveSelectorWithFallback(tree.toTrailblazeNode(), action.nodeSelector)
        when (result) {
          is TrailblazeNodeSelectorResolver.ResolveResult.SingleMatch -> {
            return copyTextAndSetClipboard(result.node, action.nodeSelector)
          }
          is TrailblazeNodeSelectorResolver.ResolveResult.MultipleMatches -> {
            return copyTextAndSetClipboard(pickPreferredMatch(result.nodes), action.nodeSelector)
          }
          is TrailblazeNodeSelectorResolver.ResolveResult.NoMatch -> { /* keep polling */ }
        }
      }
      Thread.sleep(POLL_INTERVAL_MS)
    }
    error("Copy text failed: ${action.nodeSelector.description()} not found within ${action.timeoutMs}ms")
  }

  private fun copyTextAndSetClipboard(
    node: TrailblazeNode,
    selector: TrailblazeNodeSelector,
  ): ExecutionResult {
    val detail = node.driverDetail as? DriverNodeDetail.AndroidAccessibility
    // Match Maestro Orchestra's resolveText() priority: text > hintText > contentDescription
    val text = detail?.text ?: detail?.hintText ?: detail?.contentDescription
      ?: error("Element matched but has no text: ${selector.description()}")
    TrailblazeAccessibilityService.setClipboard(text)
    Console.log("Copied text \"$text\" to clipboard from ${selector.description()}")
    val center = node.centerPoint()
    return ExecutionResult(resolvedX = center?.first, resolvedY = center?.second)
  }

  // --- Links ---

  /** Opens a URL via Intent.ACTION_VIEW, matching MaestroAndroidUiAutomatorDriver.openLink(). */
  private fun executeOpenLink(action: AccessibilityAction.OpenLink) = dispatchAndAwaitSettleBlocking {
    withInstrumentation {
      val intent = Intent(Intent.ACTION_VIEW, Uri.parse(action.link)).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        addCategory(Intent.CATEGORY_BROWSABLE)
      }
      context.startActivity(intent)
    }
  }

  // --- Airplane mode ---

  /** Matches MaestroAndroidUiAutomatorDriver.setAirplaneMode(). */
  private fun executeSetAirplaneMode(enabled: Boolean) {
    val enableOrDisable = if (enabled) "disable" else "enable"
    AdbCommandUtil.execShellCommand("svc wifi $enableOrDisable")
    AdbCommandUtil.execShellCommand("svc data $enableOrDisable")
    AdbCommandUtil.execShellCommand("svc bluetooth $enableOrDisable")
  }

  /** Matches MaestroAndroidUiAutomatorDriver.isSimulatedAirplaneModeEnabled(). */
  private fun isAirplaneModeEnabled(): Boolean {
    return withInstrumentation {
      val wifiManager = context.getSystemService(Context.WIFI_SERVICE) as WifiManager
      val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
      val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
      val isWifiOff = !wifiManager.isWifiEnabled
      val isDataOff = !telephonyManager.isDataEnabled
      val isBluetoothOff = !bluetoothManager.adapter.isEnabled
      isWifiOff && isDataOff && isBluetoothOff
    }
  }

  // --- Scroll until visible ---

  /**
   * Scrolls in [direction] until the element matching [nodeSelector] is visible or timeout.
   * Matches Maestro Orchestra's scrollUntilVisible() behavior.
   */
  private fun executeScrollUntilVisible(action: AccessibilityAction.ScrollUntilVisible): ExecutionResult {
    val (screenWidth, screenHeight) = getScreenDimensions()
    val startTime = Clock.System.now().toEpochMilliseconds()

    while (Clock.System.now().toEpochMilliseconds() - startTime < action.timeoutMs) {
      resolveVisibleTarget(action.nodeSelector)?.let {
        return it
      }
      // action.direction carries *scroll* semantics (what the trail author wrote and what the
      // progress log reports); executeSwipeDirection takes *finger* semantics. Invert here,
      // exactly where Maestro Orchestra does (scrollUntilVisible → direction.toSwipeDirection()).
      executeSwipeDirection(
        scrollToSwipeDirection(action.direction),
        screenWidth,
        screenHeight,
        action.scrollDurationMs,
      )
    }
    // Terminal check: the loop re-tests the deadline before resolving, so a target revealed by
    // the final swipe would otherwise read as a failure.
    resolveVisibleTarget(action.nodeSelector)?.let {
      return it
    }
    error("Scroll until visible failed: ${action.nodeSelector.description()} not found within ${action.timeoutMs}ms")
  }

  /** One resolve pass for [executeScrollUntilVisible]: the target's center, or null if unmatched. */
  private fun resolveVisibleTarget(nodeSelector: TrailblazeNodeSelector): ExecutionResult? {
    val tree = getAccessibilityTree() ?: return null
    return when (val result = resolveSelectorWithFallback(tree.toTrailblazeNode(), nodeSelector)) {
      is TrailblazeNodeSelectorResolver.ResolveResult.SingleMatch -> {
        val center = result.node.centerPoint()
        ExecutionResult(resolvedX = center?.first, resolvedY = center?.second)
      }
      is TrailblazeNodeSelectorResolver.ResolveResult.MultipleMatches -> {
        val center = pickPreferredMatch(result.nodes).centerPoint()
        ExecutionResult(resolvedX = center?.first, resolvedY = center?.second)
      }
      is TrailblazeNodeSelectorResolver.ResolveResult.NoMatch -> null
    }
  }

  // --- Keyboard ---

  /**
   * Dismisses the soft IME via [TrailblazeAccessibilityService.hideKeyboard]. The dispatch
   * step (sending GLOBAL_ACTION_BACK through the accessibility service) is fatal on
   * failure — that means the accessibility service rejected the action, which is real
   * framework misuse.
   *
   * The post-check (waiting for the IME window to actually leave) is best-effort: a
   * Compose `BackHandler` registered on a modal can consume BACK before the IME framework
   * sees it, and the IME stays up. The framework asked for a hide and did everything it
   * can. Log a warning and return — the caller decides what to do next. The downstream
   * safety net for this is [executeTapOnElement]'s pre-tap IME-occlusion check, which
   * catches the silent-mis-tap case at the actual point of impact (the tap), not at the
   * housekeeping that came before it.
   */
  fun hideKeyboard() {
    val dispatched = dispatchAndAwaitSettleBlocking { TrailblazeAccessibilityService.hideKeyboard() }
    if (!dispatched) {
      error("hideKeyboard failed: GLOBAL_ACTION_BACK was rejected by the accessibility service")
    }
    // Post-check polls the cheap in-process windows enumeration for fast-fail, then
    // gates the final answer on the authoritative `dumpsys input_method` signal —
    // closes both the dismissal-animation race (enumeration stale right after BACK)
    // and the windows-null bypass (getServiceWindows() returning null under
    // FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES would otherwise let a stuck keyboard
    // pass the post-check). See `waitForImeDismissed` for the full rationale.
    if (!TrailblazeAccessibilityService.waitForImeDismissed(timeoutMs = HIDE_KEYBOARD_POST_CHECK_TIMEOUT_MS)) {
      Console.log(
        "[hideKeyboard] IME window still present after dismissal attempt — proceeding. " +
          "If a subsequent tap target falls inside the IME's window bounds, the pre-tap " +
          "occlusion check will fail there with a clear error.",
      )
    }
  }

  /** Returns true if an editable field is currently focused. */
  fun isKeyboardVisible(): Boolean = TrailblazeAccessibilityService.isKeyboardVisible()

  // --- Device info ---

  /** Returns screen dimensions as (width, height) in pixels. */
  fun getScreenDimensions(): Pair<Int, Int> = TrailblazeAccessibilityService.getScreenDimensions()

  // --- Accessibility tree ---

  /**
   * Captures the current accessibility tree as a high-fidelity [AccessibilityNode] model.
   * This bypasses Maestro's `TreeNode` and captures the full richness of `AccessibilityNodeInfo`.
   */
  fun getAccessibilityTree(): AccessibilityNode? =
    TrailblazeAccessibilityService.captureMergedScreenTrees().accessibilityNode

  /**
   * Resolves a [TrailblazeNodeSelector] against the live accessibility tree using a
   * filtered-first / unfiltered-fallback strategy.
   *
   * Default: resolve against the [filterImportantForAccessibility]-applied tree, which
   * matches the tree shape the LLM and recording-playback see by default
   * ([AccessibilityServiceScreenState] applies the same filter when [includeAllElements]
   * is false). Without this, intermediate non-important nodes (e.g. Compose layout
   * wrappers) break selectors that use [TrailblazeNodeSelector.containsChild] — the
   * filtered tree promotes children of dropped nodes up, so the selector's
   * direct-child relationship survives, but the unfiltered tree shows the target as
   * a grandchild via the wrapper.
   *
   * Fallback: if the filtered resolution yields [TrailblazeNodeSelectorResolver.ResolveResult.NoMatch],
   * try the unfiltered tree. This preserves the `--all` / [SnapshotDetail.ALL_ELEMENTS]
   * escape hatch — selectors generated from those captures can target nodes that are
   * themselves `isImportantForAccessibility=false`, so they'd never match the filtered tree.
   */
  private fun resolveSelectorWithFallback(
    baseTree: TrailblazeNode,
    selector: TrailblazeNodeSelector,
  ): TrailblazeNodeSelectorResolver.ResolveResult {
    val filtered = baseTree.filterImportantForAccessibility()
    val filteredResult = TrailblazeNodeSelectorResolver.resolve(filtered, selector, templateContext)
    if (filteredResult !is TrailblazeNodeSelectorResolver.ResolveResult.NoMatch) {
      return filteredResult
    }
    // Selector did not match in the default (filtered) tree shape. Try the unfiltered
    // tree to support [SnapshotDetail.ALL_ELEMENTS]-generated selectors that target
    // non-important nodes.
    return TrailblazeNodeSelectorResolver.resolve(baseTree, selector, templateContext)
  }

  // --- Private helpers ---

  /**
   * Pre-tap safety net for the case where the soft IME refused to dismiss (e.g. a Compose
   * modal that consumes BACK before the IME framework sees it). If the resolved tap
   * coordinate falls inside the live IME window's screen bounds, dispatching the tap there
   * would land the touch on the keyboard instead of the intended target — the silent
   * mis-tap codex correctly worried about when we made [hideKeyboard]'s post-check
   * non-fatal.
   *
   * Two layers of detection:
   * 1. `imeWindowBoundsInScreen()` gives us the IME's bounds when accessibility window
   *    enumeration is healthy. We do a precise contains-point check.
   * 2. When `imeWindowBoundsInScreen()` returns null because window enumeration is
   *    degraded (some accessibility-service flag combinations leak null windows lists),
   *    we fall back to `isImeShownAuthoritative()` (dumpsys) — if the IME is up but we
   *    can't measure it, treat the tap as conservatively occluded.
   *
   * On detection, try one more dismissal, then re-check. If still occluded (or still
   * indeterminate-but-shown), fail loudly with a message that names the target and the
   * coordinate. That's the actual product failure ("the framework can't reach this
   * target because the keyboard won't get out of the way") surfaced at the point where
   * it actually matters.
   *
   * When no IME signal exists at all (the common case), this is a no-op.
   */
  private fun failIfTapPointOccludedByIme(x: Int, y: Int, targetDescription: String) {
    val firstSignal = imeOcclusionSignal(x, y)
    if (firstSignal == null) return
    Console.log(
      "[ime-occlusion] resolved tap target ($x, $y) is occluded by the IME ($firstSignal) " +
        "— re-attempting dismissal before failing.",
    )
    try {
      hideKeyboard()
    } catch (e: Exception) {
      // Even GLOBAL_ACTION_BACK refused — propagate so caller sees the real reason.
      error(
        "Tap target '$targetDescription' at ($x, $y) is occluded by the soft IME " +
          "($firstSignal), and the accessibility service refused GLOBAL_ACTION_BACK: ${e.message}",
      )
    }
    val secondSignal = imeOcclusionSignal(x, y)
    if (secondSignal != null) {
      error(
        "Tap target '$targetDescription' at ($x, $y) is occluded by the soft IME " +
          "($secondSignal), which would not dismiss. Tap would land on the keyboard " +
          "instead of the intended target. (Likely cause: a modal screen whose " +
          "BackHandler consumes GLOBAL_ACTION_BACK before the IME framework sees it.)",
      )
    }
    Console.log(
      "[ime-occlusion] retry-dismiss cleared the keyboard; resuming tap dispatch.",
    )
  }

  /**
   * Returns a non-null description of the occlusion signal when (x, y) is occluded by the
   * IME, or null when the tap point is clear. Combines the precise window-bounds check
   * (when available) with the authoritative dumpsys fallback (for the degraded-windows
   * case codex correctly flagged).
   */
  private fun imeOcclusionSignal(x: Int, y: Int): String? {
    val rect = TrailblazeAccessibilityService.imeWindowBoundsInScreen()
    return imeOcclusionSignal(
      imeBounds = rect?.let { TrailblazeNode.Bounds(it.left, it.top, it.right, it.bottom) },
      // Only consulted when bounds are unavailable, so don't pay for dumpsys otherwise.
      imeShownAuthoritative = rect == null && TrailblazeAccessibilityService.isImeShownAuthoritative(),
      x = x,
      y = y,
    )
  }

  /**
   * Reports — without changing tap behavior — when the resolved tap point is covered by
   * something drawn on top of the intended target, naming the occluder.
   *
   * This is the generalization of [failIfTapPointOccludedByIme]: that check only ever asks "is
   * the *keyboard* here?", so an in-app overlay (snackbar, banner, bottom-sheet scrim) that
   * absorbs the tap passes it and the tap is reported successful while nothing happens on
   * screen. [assessTapOcclusion] asks the general question against the same tree the selector
   * just resolved against.
   *
   * Warn-only by design for its first cycle: the paint-order heuristic it relies on cannot be
   * validated against any platform signal (see [assessTapOcclusion]), so this earns its
   * fail-vs-warn decision from a nightly's worth of labelled fires rather than from reasoning.
   * Every fire that is followed by a passing step is a false positive; every one followed by a
   * failing step is a true positive.
   *
   * Called before route selection, so it covers both the ACTION_CLICK and gesture paths.
   */
  private fun warnIfTapPointOccluded(
    root: TrailblazeNode,
    target: TrailblazeNode,
    x: Int,
    y: Int,
    targetDescription: String,
  ) {
    // Read per-dispatch (not cached at JVM start) so an oncall can silence a warn-flood on a
    // running daemon without a restart.
    if (envFlagEnabled("TRAILBLAZE_DISABLE_TAP_OCCLUSION_WARN")) return
    val verdict = assessTapOcclusion(root, target, x, y) ?: return
    Console.log(
      "[tap-occlusion] tap target '$targetDescription' at ($x, $y) is covered by " +
        "${verdict.description} — the tap may land on the overlay instead of the target. " +
        "Reporting only; the tap is still being dispatched.",
    )
  }

  /**
   * Reports — without changing behavior — when a selector resolved onto a text input it never
   * asked for, naming the field the match came from.
   *
   * Applies to assertions as much as taps: `assertVisibleBySelector: Pizza` passes off a search
   * box still holding "pizza" with an empty cart, which is a false *success* rather than a
   * failure, so nothing downstream ever surfaces it.
   *
   * Takes the whole [TrailblazeNodeSelectorResolver.ResolveResult] because the ambiguous case is
   * only visible over the match set: when a bare-text selector hits both the search field and
   * the row beneath it, no single resolved node shows the collision.
   */
  private fun warnIfTargetTypeMismatch(
    selector: TrailblazeNodeSelector,
    result: TrailblazeNodeSelectorResolver.ResolveResult,
  ) {
    if (envFlagEnabled("TRAILBLAZE_DISABLE_TARGET_TYPE_WARN")) return
    val warning = when (result) {
      is TrailblazeNodeSelectorResolver.ResolveResult.SingleMatch ->
        TargetTypeGuard.assessUnrequestedTextInput(selector, result.node)
      is TrailblazeNodeSelectorResolver.ResolveResult.MultipleMatches ->
        TargetTypeGuard.assessAmbiguousTextInput(selector, result.nodes)
      is TrailblazeNodeSelectorResolver.ResolveResult.NoMatch -> null
    } ?: return
    Console.log("[tap-target-type] $warning")
  }

  /**
   * Resolves element via [TrailblazeNodeSelector] and taps on the matched element,
   * with fallback to recorded coordinates.
   */
  private fun executeTapOnElement(action: AccessibilityAction.TapOnElement): ExecutionResult {
    // Auto-dismiss keyboard before element resolution (once, not per-retry).
    // During recording playback, there's no LLM to insert hideKeyboard steps,
    // so the keyboard may obscure the target element after inputText.
    if (isKeyboardVisible()) {
      Console.log("Auto-hiding keyboard before element resolution")
      hideKeyboard()
    }

    // --- Poll for element match (mirrors executeAssertVisible pattern) ---
    val startTime = Clock.System.now().toEpochMilliseconds()
    while (Clock.System.now().toEpochMilliseconds() - startTime < action.timeoutMs) {
      val tree = getAccessibilityTree()
      if (tree != null) {
        // Captured fresh each iteration, so the occlusion hit-test below sees the screen as it
        // is at dispatch time. That matters for animating overlays: the snackbar in the
        // motivating repro slid into place over ~3s, so a cached or pre-animation tree would
        // measure it in the wrong position.
        val unfilteredTree = tree.toTrailblazeNode()
        val result = resolveSelectorWithFallback(unfilteredTree, action.nodeSelector)
        // Once per tap, not once per poll: every branch below that resolves a node returns, and
        // the NoMatch case the loop retries on is not reported.
        warnIfTargetTypeMismatch(action.nodeSelector, result)
        when (result) {
          is TrailblazeNodeSelectorResolver.ResolveResult.SingleMatch -> {
            val center = result.node.centerPoint()
              ?: error("Element matched but has no bounds: ${action.nodeSelector.description()}")
            Console.log("Resolved via TrailblazeNode: ${action.nodeSelector.description()} at (${center.first}, ${center.second})")
            failIfTapPointOccludedByIme(center.first, center.second, action.nodeSelector.description())
            // Against the unfiltered tree: `filterImportantForAccessibility` promotes children of
            // dropped nodes up, collapsing parent/child pairs into siblings, which would weaken
            // the ancestor/descendant exclusion exactly where the tree is most collapsed.
            warnIfTapPointOccluded(
              unfilteredTree, result.node, center.first, center.second, action.nodeSelector.description(),
            )
            val route =
              tapOrLongPressOnResolvedNode(
                result.node, center.first, center.second, action.longPress, action.tapRoute,
              )
            return ExecutionResult(
              resolvedX = center.first,
              resolvedY = center.second,
              dispatchRoute = route,
            )
          }
          is TrailblazeNodeSelectorResolver.ResolveResult.MultipleMatches -> {
            val chosen = pickPreferredMatch(result.nodes)
            val center = chosen.centerPoint()
              ?: error("Matched element has no bounds: ${action.nodeSelector.description()}")
            Console.log(
              "TrailblazeNode selector matched ${result.nodes.size} elements, using preferred (visible) match at (${center.first}, ${center.second})"
            )
            failIfTapPointOccludedByIme(center.first, center.second, action.nodeSelector.description())
            warnIfTapPointOccluded(
              unfilteredTree, chosen, center.first, center.second, action.nodeSelector.description(),
            )
            val route =
              tapOrLongPressOnResolvedNode(
                chosen, center.first, center.second, action.longPress, action.tapRoute,
              )
            return ExecutionResult(
              resolvedX = center.first,
              resolvedY = center.second,
              dispatchRoute = route,
            )
          }
          is TrailblazeNodeSelectorResolver.ResolveResult.NoMatch -> {
            // Element not found yet, will retry after sleep
          }
        }
      }
      // Brief pause to avoid busy-waiting while the UI updates. The accessibility tree
      // is rebuilt each iteration, so polling too fast wastes CPU without benefit.
      Thread.sleep(POLL_INTERVAL_MS)
    }

    // --- Timeout exhausted ---
    Console.log(
      "TrailblazeNode selector found no match after ${action.timeoutMs}ms: ${action.nodeSelector.description()}"
    )

    // --- Fallback to coordinates ---
    if (action.fallbackX != null && action.fallbackY != null) {
      Console.log(
        "Using fallback coordinates (${action.fallbackX}, ${action.fallbackY})"
      )
      // Apply the same pre-tap occlusion check on the coordinate-fallback path that the
      // selector-resolved branches use. Without it, a recorded-coordinate tap on a
      // BackHandler-modal screen would silently land on the still-visible IME.
      failIfTapPointOccludedByIme(
        action.fallbackX,
        action.fallbackY,
        "fallback coordinates for ${action.nodeSelector.description()}",
      )
      tapOrLongPress(action.fallbackX, action.fallbackY, action.longPress)
      return ExecutionResult(resolvedX = action.fallbackX, resolvedY = action.fallbackY)
    } else if (action.optional) {
      // Maestro `optional: true` — selector wasn't found within the timeout and that's
      // expected for best-effort steps (e.g. dismissing a runtime permission dialog that
      // may or may not be present). Skip rather than fail.
      Console.log("Optional tap: skipping (no match within timeout)")
      return ExecutionResult(resolvedX = null, resolvedY = null)
    } else {
      error(
        "Element not found for selector: ${action.nodeSelector.description()}. " +
          "No fallback coordinates available. " +
          "The element may not be visible on screen or the selector may need adjustment."
      )
    }
  }

  private fun tapOrLongPress(x: Int, y: Int, longPress: Boolean) {
    // `tap`/`longPress` already fail loudly (via `failIfGestureNotDispatched`) when the
    // accessibility service's `dispatchGesture()` was cancelled or timed out, so this stays a
    // plain pass-through.
    if (longPress) longPress(x, y) else tap(x, y)
  }

  /**
   * Selector-resolved tap dispatch: prefers `ACTION_CLICK` on the live accessibility node
   * matching [resolvedNode]'s identity when [planActionClickRoute] returns a plan, otherwise
   * falls back to coordinate gesture dispatch. Long-press always uses the gesture path.
   *
   * Scoped here (not on raw [tap]) so coordinate-only taps (`AccessibilityAction.Tap`,
   * `TapRelative`) keep physical-touch semantics where they matter — EditText caret
   * placement, custom touch regions, IME keys under non-application overlays, etc. The
   * routing decision uses the same node the selector resolution chose, keeping it aligned
   * with the rest of Trailblaze's hit-test / `resolveFromTap` machinery (no z-order or
   * deepest-clickable heuristic divergence).
   */
  private fun tapOrLongPressOnResolvedNode(
    resolvedNode: TrailblazeNode,
    centerX: Int,
    centerY: Int,
    longPress: Boolean,
    tapRoute: TapRouteOverride?,
  ): TapDispatchRoute {
    if (actionClickRouteDisabled()) {
      Console.log("[tap-route] kill-switch set, using gesture at ($centerX,$centerY)")
      tapOrLongPress(centerX, centerY, longPress)
      return TapDispatchRoute.GESTURE
    }
    val plan = planActionClickRoute(resolvedNode, longPress, tapRoute)
    if (plan == null) {
      // Surface the gate-relevant fields of the resolved node so an oncall debugging
      // "why did this tap go via gesture?" can map each value to the matching condition
      // in `planActionClickRoute`'s kdoc without re-running the session. The 7-condition
      // gate makes a generic "declined" message uninformative.
      val why = if (tapRoute == TapRouteOverride.GESTURE) {
        "recording pinned gesture"
      } else {
        "gate declined ACTION_CLICK"
      }
      Console.log(
        "[tap-route] gesture at ($centerX,$centerY) — $why " +
          "(${describeNodeForRouteLog(resolvedNode, longPress, tapRoute)})",
      )
      tapOrLongPress(centerX, centerY, longPress)
      return TapDispatchRoute.GESTURE
    }
    return dispatchAndAwaitSettleBlocking {
      val dispatched = TrailblazeAccessibilityService.tapByActionClickOnBounds(
        plan.bounds.toAndroidRect(),
        plan.className,
        plan.resourceId,
      )
      if (dispatched) {
        Console.log("[tap-route] ACTION_CLICK dispatched on ${plan.className ?: "<no-class>"}")
        TapDispatchRoute.ACTION_CLICK
      } else {
        // Live tree didn't carry a node matching the resolved identity (tree mutated between
        // resolve and dispatch, or node no longer advertises ACTION_CLICK). Fall back to the
        // gesture path at the resolved coordinate.
        Console.log(
          "[tap-route] ACTION_CLICK lookup miss for ${plan.className ?: "<no-class>"}, " +
            "gesture fallback at ($centerX,$centerY)",
        )
        // Dispatched directly (not via the `tap()` wrapper) so this shares the ACTION_CLICK
        // attempt's single settle-wait rather than paying a second one. Still routes through
        // `failIfGestureNotDispatched` so a cancelled/timed-out fallback gesture fails loudly
        // instead of silently reporting success with zero device effect.
        failIfGestureNotDispatched(
          TrailblazeAccessibilityService.tap(centerX, centerY),
          "gesture-fallback tap at ($centerX, $centerY) after an ACTION_CLICK lookup miss",
        )
        TapDispatchRoute.GESTURE_AFTER_ACTION_CLICK_MISS
      }
    }
  }

  private fun actionClickRouteDisabled(): Boolean =
    // Kill-switch for the ACTION_CLICK route: forces every selector-resolved tap back to the
    // gesture path. Read on every dispatch (not cached) so an oncall can flip it via env on a
    // running daemon without a restart.
    envFlagEnabled("TRAILBLAZE_DISABLE_ACTION_CLICK_ROUTE")

  /** Asserts an element matching the selector is visible, polling until timeout. */
  private fun executeAssertVisible(action: AccessibilityAction.AssertVisible): ExecutionResult {
    val startTime = Clock.System.now().toEpochMilliseconds()
    while (Clock.System.now().toEpochMilliseconds() - startTime < action.timeoutMs) {
      val tree = getAccessibilityTree()
      if (tree != null) {
        val result = resolveSelectorWithFallback(tree.toTrailblazeNode(), action.nodeSelector)
        warnIfTargetTypeMismatch(action.nodeSelector, result)
        when (result) {
          is TrailblazeNodeSelectorResolver.ResolveResult.SingleMatch -> {
            val center = result.node.centerPoint()
            return ExecutionResult(resolvedX = center?.first, resolvedY = center?.second)
          }
          is TrailblazeNodeSelectorResolver.ResolveResult.MultipleMatches -> {
            val center = pickPreferredMatch(result.nodes).centerPoint()
            return ExecutionResult(resolvedX = center?.first, resolvedY = center?.second)
          }
          is TrailblazeNodeSelectorResolver.ResolveResult.NoMatch -> { /* keep polling */ }
        }
      }
      // Brief pause to avoid busy-waiting while the UI updates.
      Thread.sleep(POLL_INTERVAL_MS)
    }
    error("Assert visible failed: ${action.nodeSelector.description()} not found within ${action.timeoutMs}ms")
  }

  /** Asserts no element matching the selector is visible. */
  private fun executeAssertNotVisible(action: AccessibilityAction.AssertNotVisible): ExecutionResult {
    val (screenWidth, screenHeight) = getScreenDimensions()
    val startTime = Clock.System.now().toEpochMilliseconds()
    while (Clock.System.now().toEpochMilliseconds() - startTime < action.timeoutMs) {
      val tree = getAccessibilityTree()
      if (tree != null) {
        val result = resolveSelectorWithFallback(tree.toTrailblazeNode(), action.nodeSelector)
        if (result is TrailblazeNodeSelectorResolver.ResolveResult.NoMatch) {
          return ExecutionResult(resolvedX = screenWidth / 2, resolvedY = screenHeight / 2)
        }
        // If found, keep polling (it should disappear)
      }
      // Brief pause to avoid busy-waiting while the UI updates.
      Thread.sleep(POLL_INTERVAL_MS)
    }
    error("Assert not visible failed: ${action.nodeSelector.description()} is still visible after ${action.timeoutMs}ms")
  }

  /**
   * Resolves a [AccessibilityAction.Direction] to absolute start/end coordinates and dispatches.
   * Uses the same percentages as Maestro's accessibility driver for consistency:
   * UP/DOWN use center-to-edge (50% center to 10%/90%),
   * LEFT/RIGHT use edge-to-edge (90% to 10%) for full-width swipes.
   */
  private fun executeSwipeDirection(
    direction: AccessibilityAction.Direction,
    screenWidth: Int,
    screenHeight: Int,
    durationMs: Long,
  ) {
    val centerX = (screenWidth * 0.5f).toInt()
    val centerY = (screenHeight * 0.5f).toInt()
    when (direction) {
      AccessibilityAction.Direction.UP ->
        swipe(centerX, centerY, centerX, (screenHeight * 0.1f).toInt(), durationMs)
      AccessibilityAction.Direction.DOWN ->
        swipe(centerX, centerY, centerX, (screenHeight * 0.9f).toInt(), durationMs)
      AccessibilityAction.Direction.LEFT ->
        swipe((screenWidth * 0.9f).toInt(), centerY, (screenWidth * 0.1f).toInt(), centerY, durationMs)
      AccessibilityAction.Direction.RIGHT ->
        swipe((screenWidth * 0.1f).toInt(), centerY, (screenWidth * 0.9f).toInt(), centerY, durationMs)
    }
  }
}

/**
 * Identity a [tapOrLongPressOnResolvedNode] caller will look up in the live a11y tree when
 * routing via `ACTION_CLICK`. Kept on the cross-platform [TrailblazeNode.Bounds] type rather
 * than an `android.graphics.Rect` so [planActionClickRoute] stays unit-testable on the JVM —
 * `Rect` is a stub on the pure-JVM Android jar (its `equals` throws). The dispatch site
 * converts to `Rect` only when calling the service.
 */
internal data class ActionClickPlan(
  val bounds: TrailblazeNode.Bounds,
  val className: String?,
  val resourceId: String?,
)

/**
 * Maps a *scroll* direction to the *finger-swipe* direction that produces it, mirroring Maestro's
 * `ScrollDirection.toSwipeDirection()`: scrolling DOWN (revealing content below the viewport)
 * means the finger swipes UP. Pure function — `internal` so [ScrollToSwipeDirectionTest] can
 * exercise it without instrumentation, mirroring [planActionClickRoute]'s testability rationale.
 *
 * [AccessibilityAction.ScrollUntilVisible.direction] keeps scroll semantics (its description and
 * the [AccessibilityTrailRunner] progress log both read it as "which way the content moves"), so
 * this bridge is applied only at the gesture dispatch point. [AccessibilityAction.Swipe] and
 * [AccessibilityAction.Direction]-based raw swipes are unaffected — their direction is already
 * the finger direction.
 */
internal fun scrollToSwipeDirection(direction: AccessibilityAction.Direction): AccessibilityAction.Direction {
  return when (direction) {
    AccessibilityAction.Direction.UP -> AccessibilityAction.Direction.DOWN
    AccessibilityAction.Direction.DOWN -> AccessibilityAction.Direction.UP
    AccessibilityAction.Direction.LEFT -> AccessibilityAction.Direction.RIGHT
    AccessibilityAction.Direction.RIGHT -> AccessibilityAction.Direction.LEFT
  }
}

/**
 * Returns the [ActionClickPlan] for routing a tap on [node] via `ACTION_CLICK`, or `null`
 * when the gesture path should be used instead. Pure function — exposed `internal` so the
 * routing decision is unit-testable without an instrumentation context.
 *
 * Routes to ACTION_CLICK when ALL hold:
 * - it's a tap (long-press always uses gesture — `ACTION_LONG_CLICK` is its own routing
 *   decision, out of scope for this gate),
 * - [node] has known [TrailblazeNode.bounds] (the live-tree lookup is bounds-keyed),
 * - [node] carries an [DriverNodeDetail.AndroidAccessibility] detail whose `actions`
 *   advertise `ACTION_CLICK`,
 * - the node is enabled — a disabled-but-clickable node's `performAction(ACTION_CLICK)`
 *   returns false silently and the gesture-path fallback is also a no-op, so the caller
 *   would see resolved coords + a "success" outcome with nothing actually tapped. Routing
 *   disabled nodes to gesture from the start lets the caller's normal timeout-retry
 *   surface the un-interactable state,
 * - the node is visible to the user (an `isVisibleToUser=false` selector match is a
 *   background button under an in-app overlay; gesture defers to the OS hit-test which
 *   respects z-order, ACTION_CLICK would bypass that and fire the hidden node directly),
 * - the node is not editable (EditText caret placement requires the touch offset; ACTION_CLICK
 *   merely focuses the field without honoring it),
 * - the node carries its own text or contentDescription, **or** is checkable — distinguishes
 *   interactive **leaf** elements (`ExploreByTouchHelper` virtual buttons that emit a per-button
 *   `contentDescription`, standard `<Button text="Submit"/>`, Compose
 *   `Button { Text("Hello") }` merged-semantics nodes whose accessibility text is the merged
 *   label) from **container wrappers** whose `clickable=true` is set declaratively but whose
 *   real click logic lives elsewhere. Empirically, some downstream apps surface row-shaped
 *   call-to-action buttons as an `android.view.ViewGroup` with `clickable=true` and
 *   `ACTION_CLICK` advertised, but the actual click handler isn't `View.performClick()` —
 *   gesture-path motion injection lands at the right interceptor, `ACTION_CLICK` no-ops
 *   silently. The text/contentDescription requirement filters those wrappers out without
 *   re-introducing the canvas-widget failure mode this routing was originally designed to
 *   fix (virtual views emit their contentDescription as part of the helper's accessibility
 *   contract, so they pass).
 *
 *   A **checkable node that publishes its own checked state** is exempt from that requirement,
 *   because the accessibility contract for such a node is that `ACTION_CLICK` toggles it —
 *   that is how a screen reader activates a checkbox, radio row, or option row, so a stateful
 *   checkable node whose `ACTION_CLICK` did nothing would be unusable with a screen reader.
 *   This exemption is what lets an unmerged-semantics option row route via `ACTION_CLICK` — a
 *   Compose `selectable` row surfaces as a textless `android.view.View` carrying `isCheckable`
 *   plus its state, with the label on a child text node, so it fails the leaf-text check while
 *   being exactly the shape `ACTION_CLICK` handles best. What makes that shape safe is that
 *   `Modifier.selectable` / `toggleable` install the role, the state and the click handler on
 *   the **same** semantics node, so `View.performClick()` reaches the real handler.
 *
 *   The **state** half is load-bearing, not decoration: `isCheckable` alone is a flag any view
 *   may set, and the reasoning above only holds for a node that actually publishes the state
 *   the flag claims. Requiring `isChecked` or a `stateDescription` is what separates the two
 *   populations. Every one of the 12 checkable nodes in the committed downstream android
 *   waypoint fixtures qualifies (each carries `stateDescription` "Selected" or "Not selected";
 *   10 of the 12 are textless, so the exemption is what routes them). A stateless
 *   `isCheckable` container does not, and that shape occurs in the wild: an **accordion option
 *   row** — a textless clickable `android.view.View` with `isCheckable=true` and neither
 *   `isChecked` nor `stateDescription` — answers `ACTION_CLICK` by selecting the option, while
 *   only a real touch expands the row to reveal the sub-options. Routing that semantically
 *   performs a different action than the recording captured, and the recorded follow-up tap on
 *   a sub-option then finds nothing.
 *
 *   Erring toward gesture is the cheap direction here: a stateful node wrongly sent to gesture
 *   still taps correctly, while a stateless container wrongly sent to `ACTION_CLICK` changes
 *   what the tap does. A misrouting node is diagnosable from the `[tap-route]` log and the
 *   whole route is revertible with `TRAILBLAZE_DISABLE_ACTION_CLICK_ROUTE`.
 *
 * [tapRoute] lets a single recorded step pin its own route for the case this gate cannot decide:
 * two rows that need opposite routes presenting identical fields (see [TapRouteOverride]). The pin
 * reaches only the leaf-vs-container judgement above. Every check before it is a precondition for
 * `ACTION_CLICK` being dispatchable at all, so a step that pins `ACTION_CLICK` on a long-press, an
 * editable field, an invisible node, or a node that doesn't advertise the action still routes to
 * gesture rather than dispatching an action the node can't answer.
 */
internal fun planActionClickRoute(
  node: TrailblazeNode,
  longPress: Boolean,
  tapRoute: TapRouteOverride? = null,
): ActionClickPlan? {
  if (longPress) return null
  if (tapRoute == TapRouteOverride.GESTURE) return null
  val bounds = node.bounds ?: return null
  val detail = node.driverDetail as? DriverNodeDetail.AndroidAccessibility ?: return null
  if (ACTION_CLICK_NAME !in detail.actions) return null
  if (!detail.isEnabled) return null
  if (detail.isEditable) return null
  if (!detail.isVisibleToUser) return null
  if (tapRoute != TapRouteOverride.ACTION_CLICK) {
    val publishesCheckedState =
      detail.isCheckable && (detail.isChecked || !detail.stateDescription.isNullOrBlank())
    if (
      detail.text.isNullOrBlank() &&
      detail.contentDescription.isNullOrBlank() &&
      !publishesCheckedState
    ) {
      return null
    }
  }
  return ActionClickPlan(bounds, detail.className, detail.resourceId)
}

internal fun TrailblazeNode.Bounds.toAndroidRect(): Rect = Rect(left, top, right, bottom)

/**
 * Chooses which node to act on when a selector matches more than one node.
 *
 * Merged-window capture (see [TrailblazeAccessibilityService.getCaptureWindowRoots]) can surface
 * the same label from two windows at once: a popup/dialog window draws the on-screen control while
 * the base application window still holds an off-screen, occluded node with identical text (e.g. a
 * catalog tile scrolled behind a filter dropdown). The resolver orders matches by `bounds.top`, so
 * the off-screen node — whose top happens to sort first — would win a blind `first()`, and the tap
 * would land off-screen and silently no-op.
 *
 * Prefer the first match the framework reports as visible to the user; fall back to the resolver's
 * first match when none carry that signal (non-Android details, or every match reports not-visible)
 * so single-window behavior is unchanged.
 */
internal fun pickPreferredMatch(nodes: List<TrailblazeNode>): TrailblazeNode =
  nodes.firstOrNull { node ->
    (node.driverDetail as? DriverNodeDetail.AndroidAccessibility)?.isVisibleToUser == true
  } ?: nodes.first()

/**
 * Builds a one-line diagnostic string of the gate-relevant fields on [node] so a `[tap-route]`
 * decline log entry can name the failing condition without re-running the session. Each field
 * lines up with one of the checks in [planActionClickRoute]'s kdoc — `text=null,
 * contentDescription=null, isCheckable=false` → leaf-text gate fired; the same three with
 * `isCheckable=true, isChecked=false, stateDescription=null` → the checkable exemption was
 * declined for want of published state; `isVisibleToUser=false` → overlay gate; etc. Pure
 * function, kept top-level alongside [planActionClickRoute] for the same JVM-unit-testability
 * reasons.
 */
internal fun describeNodeForRouteLog(
  node: TrailblazeNode,
  longPress: Boolean,
  tapRoute: TapRouteOverride? = null,
): String {
  val detail = node.driverDetail as? DriverNodeDetail.AndroidAccessibility
  return "tapRoute=$tapRoute, longPress=$longPress, hasBounds=${node.bounds != null}, " +
    "className=${detail?.className}, text=${detail?.text}, contentDescription=${detail?.contentDescription}, " +
    "isEnabled=${detail?.isEnabled}, isEditable=${detail?.isEditable}, " +
    "isVisibleToUser=${detail?.isVisibleToUser}, isCheckable=${detail?.isCheckable}, " +
    "isChecked=${detail?.isChecked}, stateDescription=${detail?.stateDescription}, " +
    "hasActionClick=${detail?.actions?.contains(ACTION_CLICK_NAME)}"
}
