package xyz.block.trailblaze.host.axe

import xyz.block.trailblaze.api.TrailblazeNodeSelector

/**
 * Native action types for the AXe iOS-Simulator driver, completely independent of Maestro.
 *
 * Parallel to [xyz.block.trailblaze.android.accessibility.AccessibilityAction] on Android:
 * the shape each driver dispatches internally, with rich per-action fields. Anything that
 * AXe's CLI can't do (app lifecycle, deep link opens) is still modeled here — execution
 * delegates to [SimctlCli] rather than [AxeCli].
 */
sealed interface AxeAction {
  val description: String

  // --- Gestures ---

  data class Tap(val x: Int, val y: Int) : AxeAction {
    override val description get() = "Tap on ($x, $y)"
  }

  data class TapRelative(val percentX: Double, val percentY: Double) : AxeAction {
    override val description get() = "Tap on ($percentX%, $percentY%)"
  }

  data class LongPress(val x: Int, val y: Int, val durationMs: Long = 500L) : AxeAction {
    override val description get() = "Long press on ($x, $y) for ${durationMs}ms"
  }

  data class Swipe(
    val startX: Int,
    val startY: Int,
    val endX: Int,
    val endY: Int,
    val durationMs: Long = 400L,
  ) : AxeAction {
    override val description get() = "Swipe from ($startX, $startY) to ($endX, $endY) in ${durationMs}ms"
  }

  data class SwipeDirection(val direction: Direction, val durationMs: Long = 400L) : AxeAction {
    override val description get() = "Swipe ${direction.name} in ${durationMs}ms"
  }

  data object ScrollUp : AxeAction { override val description get() = "Scroll up" }
  data object ScrollDown : AxeAction { override val description get() = "Scroll down" }
  data object ScrollLeft : AxeAction { override val description get() = "Scroll left" }
  data object ScrollRight : AxeAction { override val description get() = "Scroll right" }

  // --- Text input ---

  /** Types text into the currently focused field. Caller is responsible for focusing first. */
  data class InputText(val text: String) : AxeAction {
    override val description get() = "Input text \"$text\""
  }

  /** Erases characters by sending backspace keycodes (AXe keycode 42 = Delete/Backspace). */
  data class EraseText(val characters: Int) : AxeAction {
    override val description get() = "Erase $characters characters"
  }

  // --- Hardware buttons ---

  data object PressHome : AxeAction { override val description get() = "Press home" }
  data object PressLock : AxeAction { override val description get() = "Press lock/power" }
  data object PressSiri : AxeAction { override val description get() = "Press Siri" }

  // --- Element-based actions (use TrailblazeNodeSelector for resolution) ---

  data class TapOnElement(
    val nodeSelector: TrailblazeNodeSelector,
    val longPress: Boolean = false,
    val fallbackX: Int? = null,
    val fallbackY: Int? = null,
    val timeoutMs: Long = DEFAULT_ELEMENT_TIMEOUT_MS,
  ) : AxeAction {
    override val description get() =
      "${if (longPress) "Long press" else "Tap"} on ${nodeSelector.description()}"
  }

  data class AssertVisible(
    val nodeSelector: TrailblazeNodeSelector,
    val timeoutMs: Long = DEFAULT_ELEMENT_TIMEOUT_MS,
  ) : AxeAction {
    override val description get() = "Assert visible: ${nodeSelector.description()}"
  }

  data class AssertNotVisible(
    val nodeSelector: TrailblazeNodeSelector,
    val timeoutMs: Long = DEFAULT_ELEMENT_TIMEOUT_MS,
  ) : AxeAction {
    override val description get() = "Assert not visible: ${nodeSelector.description()}"
  }

  // --- App lifecycle (shells out to `xcrun simctl`, not AXe) ---

  data class LaunchApp(val bundleId: String) : AxeAction {
    override val description get() = "Launch app $bundleId"
  }

  data class StopApp(val bundleId: String) : AxeAction {
    override val description get() = "Stop app $bundleId"
  }

  data class OpenLink(val url: String) : AxeAction {
    override val description get() = "Open link $url"
  }

  // --- Waiting ---

  data class WaitForSettle(val timeoutMs: Long = DEFAULT_ELEMENT_TIMEOUT_MS) : AxeAction {
    override val description get() = "Wait for UI to settle (timeout: ${timeoutMs}ms)"
  }

  // --- Take screenshot (no-op — handled by logging) ---

  data object TakeScreenshot : AxeAction {
    override val description get() = "Take screenshot (captured by logging)"
  }

  enum class Direction { UP, DOWN, LEFT, RIGHT }

  companion object {
    const val DEFAULT_ELEMENT_TIMEOUT_MS: Long = 5_000L
  }
}
