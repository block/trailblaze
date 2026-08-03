package xyz.block.trailblaze.host.ios

import xyz.block.trailblaze.api.TrailblazeNodeSelector

/**
 * Native action vocabulary for host-native iOS-Simulator drivers, completely independent of
 * Maestro. Every [IosDeviceManager] implementation executes this vocabulary.
 *
 * Parallel to [xyz.block.trailblaze.android.accessibility.AccessibilityAction] on Android:
 * the shape each driver dispatches internally, with rich per-action fields. App lifecycle and
 * deep-link opens are modeled here too — implementations delegate those to [SimctlCli].
 */
sealed interface IosDriverAction {
  val description: String

  // --- Gestures ---

  data class Tap(val x: Int, val y: Int) : IosDriverAction {
    override val description get() = "Tap on ($x, $y)"
  }

  data class TapRelative(val percentX: Double, val percentY: Double) : IosDriverAction {
    override val description get() = "Tap on ($percentX%, $percentY%)"
  }

  data class LongPress(val x: Int, val y: Int, val durationMs: Long = 500L) : IosDriverAction {
    override val description get() = "Long press on ($x, $y) for ${durationMs}ms"
  }

  data class Swipe(
    val startX: Int,
    val startY: Int,
    val endX: Int,
    val endY: Int,
    val durationMs: Long = 400L,
  ) : IosDriverAction {
    override val description get() = "Swipe from ($startX, $startY) to ($endX, $endY) in ${durationMs}ms"
  }

  data class SwipeDirection(val direction: Direction, val durationMs: Long = 400L) : IosDriverAction {
    override val description get() = "Swipe ${direction.name} in ${durationMs}ms"
  }

  /**
   * Swipe between two points expressed as percentages of the screen (Maestro's
   * `startRelative`/`endRelative`, e.g. "50%,47%" → 50.0, 47.0). Resolved to absolute
   * coordinates by the executor, which owns the device dimensions.
   */
  data class SwipeRelative(
    val startXPercent: Double,
    val startYPercent: Double,
    val endXPercent: Double,
    val endYPercent: Double,
    val durationMs: Long = 400L,
  ) : IosDriverAction {
    override val description get() =
      "Swipe from ($startXPercent%, $startYPercent%) to ($endXPercent%, $endYPercent%) in ${durationMs}ms"
  }

  data object ScrollUp : IosDriverAction { override val description get() = "Scroll up" }
  data object ScrollDown : IosDriverAction { override val description get() = "Scroll down" }
  data object ScrollLeft : IosDriverAction { override val description get() = "Scroll left" }
  data object ScrollRight : IosDriverAction { override val description get() = "Scroll right" }

  // --- Text input ---

  /** Types text into the currently focused field. Caller is responsible for focusing first. */
  data class InputText(val text: String) : IosDriverAction {
    override val description get() = "Input text \"$text\""
  }

  /** Erases characters by sending [PressKey.BACKSPACE] keycodes. */
  data class EraseText(val characters: Int) : IosDriverAction {
    override val description get() = "Erase $characters characters"
  }

  // --- Hardware buttons ---

  data object PressHome : IosDriverAction { override val description get() = "Press home" }
  data object PressLock : IosDriverAction { override val description get() = "Press lock/power" }
  data object PressSiri : IosDriverAction { override val description get() = "Press Siri" }

  /** Presses a single HID keycode via `axe key`. */
  data class PressKey(val keycode: Int, val label: String) : IosDriverAction {
    override val description get() = "Press $label (keycode $keycode)"

    companion object {
      // USB HID usage ids the iOS Simulator accepts via `axe key`.
      val ENTER = PressKey(40, "Enter")
      val ESCAPE = PressKey(41, "Escape")
      val BACKSPACE = PressKey(42, "Backspace")
      val TAB = PressKey(43, "Tab")
    }
  }

  // --- Element-based actions (use TrailblazeNodeSelector for resolution) ---

  data class TapOnElement(
    val nodeSelector: TrailblazeNodeSelector,
    val longPress: Boolean = false,
    val fallbackX: Int? = null,
    val fallbackY: Int? = null,
    val timeoutMs: Long = DEFAULT_ELEMENT_TIMEOUT_MS,
    /**
     * When true, a timeout-exhausted no-match is treated as success (no-op) instead of an
     * error. Mirrors Maestro's `optional: true` semantics (and the Android accessibility
     * driver's [xyz.block.trailblaze.android.accessibility.AccessibilityAction.TapOnElement])
     * so recorded `mobile_maestro` blocks that best-effort-dismiss transient dialogs keep
     * working under the AXe driver.
     */
    val optional: Boolean = false,
  ) : IosDriverAction {
    override val description get() =
      "${if (longPress) "Long press" else "Tap"} on ${nodeSelector.description()}"
  }

  data class AssertVisible(
    val nodeSelector: TrailblazeNodeSelector,
    val timeoutMs: Long = DEFAULT_ELEMENT_TIMEOUT_MS,
    /** Maestro `optional: true`: a timeout-exhausted no-match is skipped, not failed. */
    val optional: Boolean = false,
  ) : IosDriverAction {
    override val description get() = "Assert visible: ${nodeSelector.description()}"
  }

  data class AssertNotVisible(
    val nodeSelector: TrailblazeNodeSelector,
    val timeoutMs: Long = DEFAULT_ELEMENT_TIMEOUT_MS,
    /** Maestro `optional: true`: a still-visible timeout is skipped, not failed. */
    val optional: Boolean = false,
  ) : IosDriverAction {
    override val description get() = "Assert not visible: ${nodeSelector.description()}"
  }

  // --- App lifecycle (shells out to `xcrun simctl`, not AXe) ---

  /**
   * [stopFirst] mirrors Maestro's `LaunchAppCommand.stopApp` (FORCE_RESTART / REINSTALL launch
   * modes): terminate the app before launching so it cold-starts instead of resuming prior state.
   * [clearState] mirrors `LaunchAppCommand.clearState` (REINSTALL, the tool's default mode):
   * wipe app state via terminate + reinstall-from-installed-bundle before launching.
   * [clearKeychain] mirrors `LaunchAppCommand.clearKeychain`: reset the simulator keychain before
   * launching. Keychain entries survive the [clearState] reinstall, so sign-in flows that rely on
   * this flag to force a signed-out start would otherwise resume the previous session.
   */
  data class LaunchApp(
    val bundleId: String,
    val stopFirst: Boolean = false,
    val clearState: Boolean = false,
    val clearKeychain: Boolean = false,
  ) : IosDriverAction {
    override val description get() = "Launch app $bundleId" +
      when {
        clearState && clearKeychain -> " (clear state + keychain)"
        clearState -> " (clear state)"
        clearKeychain && stopFirst -> " (force restart + clear keychain)"
        clearKeychain -> " (clear keychain)"
        stopFirst -> " (force restart)"
        else -> ""
      }
  }

  data class StopApp(val bundleId: String) : IosDriverAction {
    override val description get() = "Stop app $bundleId"
  }

  /**
   * Standalone `clearState:` (no launch) — wipes app state via terminate + reinstall, same
   * mechanism as [LaunchApp] with `clearState=true`, but leaves the app stopped.
   */
  data class ClearState(val bundleId: String) : IosDriverAction {
    override val description get() = "Clear state for $bundleId"
  }

  /** Standalone `clearKeychain:` (no launch) — resets the whole simulator keychain. */
  data object ClearKeychain : IosDriverAction {
    override val description get() = "Clear keychain"
  }

  data class OpenLink(val url: String) : IosDriverAction {
    override val description get() = "Open link $url"
  }

  // --- Waiting ---

  data class WaitForSettle(val timeoutMs: Long = DEFAULT_ELEMENT_TIMEOUT_MS) : IosDriverAction {
    override val description get() = "Wait for UI to settle (timeout: ${timeoutMs}ms)"
  }

  // --- Take screenshot (no-op — handled by logging) ---

  data object TakeScreenshot : IosDriverAction {
    override val description get() = "Take screenshot (captured by logging)"
  }

  enum class Direction { UP, DOWN, LEFT, RIGHT }

  companion object {
    const val DEFAULT_ELEMENT_TIMEOUT_MS: Long = 5_000L
  }
}
