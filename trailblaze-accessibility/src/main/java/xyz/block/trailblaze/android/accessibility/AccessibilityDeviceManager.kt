package xyz.block.trailblaze.android.accessibility

import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.net.wifi.WifiManager
import android.telephony.TelephonyManager
import kotlinx.datetime.Clock
import xyz.block.trailblaze.AdbCommandUtil
import xyz.block.trailblaze.InstrumentationUtil.withInstrumentation
import xyz.block.trailblaze.api.DriverNodeDetail
import xyz.block.trailblaze.api.ScreenState
import xyz.block.trailblaze.api.TrailblazeNode
import xyz.block.trailblaze.api.TrailblazeNodeSelector
import xyz.block.trailblaze.api.TrailblazeNodeSelectorResolver
import xyz.block.trailblaze.devices.TrailblazeDeviceClassifier
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
) {

  companion object {
    /** Polling interval for element resolution loops. Balances responsiveness with CPU usage. */
    private const val POLL_INTERVAL_MS = 100L
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
    )
  }

  /**
   * Captures screen state for logging without waiting for settle. Useful for recording the
   * immediate state after an action without the settle overhead.
   */
  fun captureScreenStateForLogging(): ScreenState {
    return AccessibilityServiceScreenState(
      deviceClassifiers = deviceClassifiers,
    )
  }

  /**
   * Waits for the UI to settle using accessibility event-based detection.
   *
   * Equivalent to `PlaywrightBrowserManager.waitForPageReady()` — uses a debounce on
   * accessibility events rather than DOM mutations.
   */
  fun waitForReady(timeoutMs: Long = 5_000L) {
    TrailblazeAccessibilityService.waitForSettled(timeoutMs = timeoutMs)
  }

  // --- Action dispatch ---

  /**
   * Result of executing an accessibility action, containing resolved coordinates
   * for logging and visualization.
   */
  data class ExecutionResult(
    val resolvedX: Int? = null,
    val resolvedY: Int? = null,
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

  /** Taps at the given coordinates and waits for the UI to settle. */
  fun tap(x: Int, y: Int) {
    TrailblazeAccessibilityService.tap(x, y)
    TrailblazeAccessibilityService.waitForSettled()
  }

  /** Long-presses at the given coordinates and waits for the UI to settle. */
  fun longPress(x: Int, y: Int, durationMs: Long = 500L) {
    TrailblazeAccessibilityService.longPress(x, y, durationMs)
    TrailblazeAccessibilityService.waitForSettled()
  }

  /** Swipes between two points and waits for the UI to settle. */
  fun swipe(startX: Int, startY: Int, endX: Int, endY: Int, durationMs: Long) {
    TrailblazeAccessibilityService.directionalSwipe(durationMs, startX, startY, endX, endY)
    TrailblazeAccessibilityService.waitForSettled()
  }

  /** Scrolls forward (up) or backward (down) and waits for the UI to settle. */
  fun scroll(forward: Boolean) {
    TrailblazeAccessibilityService.scroll(forward)
    TrailblazeAccessibilityService.waitForSettled()
  }

  // --- Text input ---

  /** Sets text on the focused editable node and waits for the UI to settle. */
  fun inputText(text: String) {
    TrailblazeAccessibilityService.inputText(text)
    TrailblazeAccessibilityService.waitForSettled()
  }

  /** Erases characters from the focused editable node and waits for the UI to settle. */
  fun eraseText(charactersToErase: Int) {
    TrailblazeAccessibilityService.eraseText(charactersToErase)
    TrailblazeAccessibilityService.waitForSettled()
  }

  // --- Navigation ---

  /** Presses the back button via accessibility global action and waits for settle. */
  fun pressBack() {
    TrailblazeAccessibilityService.pressBack()
    TrailblazeAccessibilityService.waitForSettled()
  }

  /** Presses the home button via accessibility global action and waits for settle. */
  fun pressHome() {
    TrailblazeAccessibilityService.pressHome()
    TrailblazeAccessibilityService.waitForSettled()
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
  private fun executeLaunchApp(action: AccessibilityAction.LaunchApp) {
    Console.log("Launching ${action.appId} via Context.startActivity()")
    TrailblazeAccessibilityService.launchApp(action.appId)
    // Wait for the app to render and settle
    TrailblazeAccessibilityService.waitForSettled(timeoutMs = 10_000L)
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
        val trailblazeTree = tree.toTrailblazeNode()
        val result = TrailblazeNodeSelectorResolver.resolve(trailblazeTree, action.nodeSelector)
        when (result) {
          is TrailblazeNodeSelectorResolver.ResolveResult.SingleMatch -> {
            return copyTextAndSetClipboard(result.node, action.nodeSelector)
          }
          is TrailblazeNodeSelectorResolver.ResolveResult.MultipleMatches -> {
            return copyTextAndSetClipboard(result.nodes.first(), action.nodeSelector)
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
  private fun executeOpenLink(action: AccessibilityAction.OpenLink) {
    withInstrumentation {
      val intent = Intent(Intent.ACTION_VIEW, Uri.parse(action.link)).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        addCategory(Intent.CATEGORY_BROWSABLE)
      }
      context.startActivity(intent)
    }
    TrailblazeAccessibilityService.waitForSettled(timeoutMs = 10_000L)
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
      val tree = getAccessibilityTree()
      if (tree != null) {
        val trailblazeTree = tree.toTrailblazeNode()
        val result = TrailblazeNodeSelectorResolver.resolve(trailblazeTree, action.nodeSelector)
        when (result) {
          is TrailblazeNodeSelectorResolver.ResolveResult.SingleMatch -> {
            val center = result.node.centerPoint()
            return ExecutionResult(resolvedX = center?.first, resolvedY = center?.second)
          }
          is TrailblazeNodeSelectorResolver.ResolveResult.MultipleMatches -> {
            val center = result.nodes.first().centerPoint()
            return ExecutionResult(resolvedX = center?.first, resolvedY = center?.second)
          }
          is TrailblazeNodeSelectorResolver.ResolveResult.NoMatch -> { /* scroll and retry */ }
        }
      }
      executeSwipeDirection(action.direction, screenWidth, screenHeight, action.scrollDurationMs)
    }
    error("Scroll until visible failed: ${action.nodeSelector.description()} not found within ${action.timeoutMs}ms")
  }

  // --- Keyboard ---

  /**
   * Hides the keyboard by clearing focus on the active editable field.
   * Prefers ACTION_CLEAR_FOCUS (safe, won't navigate away), falling back to
   * pressBack() only if focus-clearing didn't dismiss the keyboard.
   *
   * Note: [isKeyboardVisible] is a heuristic that can produce false positives
   * (editable focus retained without keyboard). The pressBack() fallback could
   * navigate away in that case, but this is rare and mirrors Maestro's behavior.
   */
  fun hideKeyboard() {
    TrailblazeAccessibilityService.hideKeyboard()
    TrailblazeAccessibilityService.waitForSettled()
    // Note: intentionally NOT using pressBack() as a fallback here. The isKeyboardVisible()
    // heuristic (based on editable focus) can produce false positives, and pressBack() may
    // navigate away from the current screen — which is far more destructive than leaving
    // the keyboard open. If the keyboard persists, the next element tap will still work
    // since the accessibility tree includes elements regardless of keyboard state.
    if (isKeyboardVisible()) {
      Console.log("WARNING: Keyboard may still be visible after hideKeyboard()")
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
  fun getAccessibilityTree(): AccessibilityNode? {
    val rootNodeInfo = TrailblazeAccessibilityService.getRootNodeInfo() ?: return null
    return try {
      rootNodeInfo.toAccessibilityNode()
    } finally {
      rootNodeInfo.recycle()
    }
  }

  // --- Private helpers ---

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
        val trailblazeTree = tree.toTrailblazeNode()
        val result = TrailblazeNodeSelectorResolver.resolve(trailblazeTree, action.nodeSelector)
        when (result) {
          is TrailblazeNodeSelectorResolver.ResolveResult.SingleMatch -> {
            val center = result.node.centerPoint()
              ?: error("Element matched but has no bounds: ${action.nodeSelector.description()}")
            Console.log("Resolved via TrailblazeNode: ${action.nodeSelector.description()} at (${center.first}, ${center.second})")
            tapOrLongPress(center.first, center.second, action.longPress)
            return ExecutionResult(resolvedX = center.first, resolvedY = center.second)
          }
          is TrailblazeNodeSelectorResolver.ResolveResult.MultipleMatches -> {
            val first = result.nodes.first()
            val center = first.centerPoint()
              ?: error("First matched element has no bounds: ${action.nodeSelector.description()}")
            Console.log(
              "TrailblazeNode selector matched ${result.nodes.size} elements, using first at (${center.first}, ${center.second})"
            )
            tapOrLongPress(center.first, center.second, action.longPress)
            return ExecutionResult(resolvedX = center.first, resolvedY = center.second)
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
      tapOrLongPress(action.fallbackX, action.fallbackY, action.longPress)
      return ExecutionResult(resolvedX = action.fallbackX, resolvedY = action.fallbackY)
    } else {
      error(
        "Element not found for selector: ${action.nodeSelector.description()}. " +
          "No fallback coordinates available. " +
          "The element may not be visible on screen or the selector may need adjustment."
      )
    }
  }

  private fun tapOrLongPress(x: Int, y: Int, longPress: Boolean) {
    if (longPress) longPress(x, y) else tap(x, y)
  }

  /** Asserts an element matching the selector is visible, polling until timeout. */
  private fun executeAssertVisible(action: AccessibilityAction.AssertVisible): ExecutionResult {
    val startTime = Clock.System.now().toEpochMilliseconds()
    while (Clock.System.now().toEpochMilliseconds() - startTime < action.timeoutMs) {
      val tree = getAccessibilityTree()
      if (tree != null) {
        val trailblazeTree = tree.toTrailblazeNode()
        val result = TrailblazeNodeSelectorResolver.resolve(trailblazeTree, action.nodeSelector)
        when (result) {
          is TrailblazeNodeSelectorResolver.ResolveResult.SingleMatch -> {
            val center = result.node.centerPoint()
            return ExecutionResult(resolvedX = center?.first, resolvedY = center?.second)
          }
          is TrailblazeNodeSelectorResolver.ResolveResult.MultipleMatches -> {
            val center = result.nodes.first().centerPoint()
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
        val trailblazeTree = tree.toTrailblazeNode()
        val result = TrailblazeNodeSelectorResolver.resolve(trailblazeTree, action.nodeSelector)
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
