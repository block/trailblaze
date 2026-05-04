package xyz.block.trailblaze.android.accessibility

import maestro.KeyCode
import xyz.block.trailblaze.api.TrailblazeNodeSelector

/**
 * Default timeout for actions that poll the accessibility tree waiting for an element to appear
 * or the UI to settle. 5 seconds balances responsiveness with giving animations, network
 * requests, and screen transitions enough time to complete on mid-range devices.
 */
const val DEFAULT_ELEMENT_TIMEOUT_MS = 5_000L

/**
 * Native action types for the accessibility driver, completely independent of Maestro.
 *
 * These represent the gestures and interactions that can be performed purely through the
 * Android accessibility framework. They are the accessibility equivalent of Playwright's
 * tool types — framework-native actions that don't depend on any external test runner.
 *
 * Each action has:
 * - [description]: human-readable description for logging
 */
sealed interface AccessibilityAction {
  val description: String

  // --- Gestures ---

  data class Tap(val x: Int, val y: Int) : AccessibilityAction {
    override val description get() = "Tap on ($x, $y)"
  }

  data class TapRelative(val percentX: Int, val percentY: Int) : AccessibilityAction {
    override val description get() = "Tap on ($percentX%, $percentY%)"
  }

  data class LongPress(val x: Int, val y: Int, val durationMs: Long = 500L) :
    AccessibilityAction {
    override val description get() = "Long press on ($x, $y) for ${durationMs}ms"
  }

  data class Swipe(
    val startX: Int,
    val startY: Int,
    val endX: Int,
    val endY: Int,
    val durationMs: Long = 400L,
  ) : AccessibilityAction {
    override val description
      get() = "Swipe from ($startX, $startY) to ($endX, $endY) in ${durationMs}ms"
  }

  data class SwipeDirection(
    val direction: Direction,
    val durationMs: Long = 400L,
  ) : AccessibilityAction {
    override val description get() = "Swipe ${direction.name} in ${durationMs}ms"
  }

  data object ScrollForward : AccessibilityAction {
    override val description get() = "Scroll forward"
  }

  data object ScrollBackward : AccessibilityAction {
    override val description get() = "Scroll backward"
  }

  // --- Text input ---

  data class InputText(val text: String) : AccessibilityAction {
    override val description get() = "Input text \"$text\""
  }

  data class EraseText(val characters: Int) : AccessibilityAction {
    override val description get() = "Erase $characters characters"
  }

  // --- Navigation ---

  data object PressBack : AccessibilityAction {
    override val description get() = "Press back"
  }

  data object PressHome : AccessibilityAction {
    override val description get() = "Press home"
  }

  /**
   * Sends a key event via ADB shell (`input keyevent`).
   *
   * Used for keys that have no native accessibility global-action equivalent (e.g. ENTER, TAB).
   * BACK and HOME should still use [PressBack]/[PressHome] which use the more reliable
   * `performGlobalAction` path.
   */
  data class PressKey(val code: KeyCode) : AccessibilityAction {
    override val description get() = "Press key ${code.name}"
  }

  data object HideKeyboard : AccessibilityAction {
    override val description get() = "Hide keyboard"
  }

  // --- Element-based actions ---

  /**
   * Taps on an element matching the given [nodeSelector] in the current view hierarchy.
   *
   * Uses [TrailblazeNodeSelector] for rich, driver-native matching against the full
   * [TrailblazeNode] tree with driver-specific properties (className, inputType,
   * collectionItemInfo, labeledByText, etc.).
   *
   * Polls the accessibility tree up to [timeoutMs] for the element to appear, handling
   * UI transitions and animations. If still not found after the timeout, falls back to
   * [fallbackX]/[fallbackY] coordinates or throws an error.
   */
  data class TapOnElement(
    val nodeSelector: TrailblazeNodeSelector,
    val longPress: Boolean = false,
    val fallbackX: Int? = null,
    val fallbackY: Int? = null,
    val timeoutMs: Long = DEFAULT_ELEMENT_TIMEOUT_MS,
  ) : AccessibilityAction {
    override val description: String
      get() = "${if (longPress) "Long press" else "Tap"} on ${nodeSelector.description()}"
  }

  /**
   * Asserts that an element matching the given [nodeSelector] is visible in the current hierarchy.
   * Waits up to [timeoutMs] for the element to appear, polling the accessibility tree.
   */
  data class AssertVisible(
    val nodeSelector: TrailblazeNodeSelector,
    val timeoutMs: Long = DEFAULT_ELEMENT_TIMEOUT_MS,
  ) : AccessibilityAction {
    override val description: String
      get() = "Assert visible: ${nodeSelector.description()}"
  }

  /**
   * Asserts that no element matching the given [nodeSelector] is visible in the current hierarchy.
   */
  data class AssertNotVisible(
    val nodeSelector: TrailblazeNodeSelector,
    val timeoutMs: Long = DEFAULT_ELEMENT_TIMEOUT_MS,
  ) : AccessibilityAction {
    override val description: String
      get() = "Assert not visible: ${nodeSelector.description()}"
  }

  // --- Clipboard ---

  data class SetClipboard(val text: String) : AccessibilityAction {
    override val description get() = "Set clipboard to \"$text\""
  }

  data object PasteText : AccessibilityAction {
    override val description get() = "Paste text from clipboard"
  }

  data class CopyTextFrom(
    val nodeSelector: TrailblazeNodeSelector,
    val timeoutMs: Long = DEFAULT_ELEMENT_TIMEOUT_MS,
  ) : AccessibilityAction {
    override val description: String
      get() = "Copy text from ${nodeSelector.description()}"
  }

  // --- App lifecycle ---

  /**
   * Launches an app by package ID via the accessibility service's Context.
   *
   * Uses [Intent.FLAG_ACTIVITY_CLEAR_TASK] for a fresh UI start. When used as part of
   * [LaunchAppCommand] processing, stop/clear/permissions are handled separately via ADB
   * by [AccessibilityTrailblazeAgent.executeLaunchAppViaAdb] before this action runs.
   */
  data class LaunchApp(
    val appId: String,
  ) : AccessibilityAction {
    override val description get() = "Launch app $appId"
  }

  data class StopApp(val appId: String) : AccessibilityAction {
    override val description get() = "Stop app $appId"
  }

  data class KillApp(val appId: String) : AccessibilityAction {
    override val description get() = "Kill app $appId"
  }

  data class ClearState(val appId: String) : AccessibilityAction {
    override val description get() = "Clear state for $appId"
  }

  // --- Navigation ---

  data class OpenLink(val link: String) : AccessibilityAction {
    override val description get() = "Open link $link"
  }

  // --- Device settings ---

  /** Sets the device orientation. [rotation] matches ADB values: 0=portrait, 1=landscape_left, 2=upside_down, 3=landscape_right. */
  data class SetOrientation(val rotation: Int) : AccessibilityAction {
    override val description get() = "Set orientation to rotation=$rotation"
  }

  data class SetAirplaneMode(val enabled: Boolean) : AccessibilityAction {
    override val description get() = "${if (enabled) "Enable" else "Disable"} airplane mode"
  }

  data object ToggleAirplaneMode : AccessibilityAction {
    override val description get() = "Toggle airplane mode"
  }

  // --- Scroll until visible ---

  data class ScrollUntilVisible(
    val nodeSelector: TrailblazeNodeSelector,
    val direction: Direction,
    val timeoutMs: Long,
    val scrollDurationMs: Long = 400L,
  ) : AccessibilityAction {
    override val description: String
      get() = "Scroll ${direction.name} until ${nodeSelector.description()} is visible"
  }

  // --- Waiting ---

  data class WaitForSettle(val timeoutMs: Long = DEFAULT_ELEMENT_TIMEOUT_MS) : AccessibilityAction {
    override val description get() = "Wait for UI to settle (timeout: ${timeoutMs}ms)"
  }

  /** Swipe/scroll directions for accessibility gestures. */
  enum class Direction {
    UP,
    DOWN,
    LEFT,
    RIGHT,
  }
}
