package xyz.block.trailblaze.android.accessibility

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

  // --- App lifecycle ---

  /**
   * Launches an app by package ID via the accessibility service's Context.
   *
   * This action only handles the UI launch (via [Intent.FLAG_ACTIVITY_CLEAR_TASK]).
   * `clearState` and `stopApp` are intentionally omitted because `pm clear` and
   * `am force-stop` require shell-level permissions that an accessibility service doesn't
   * have. Those operations are handled separately via ADB by
   * [AccessibilityTrailblazeAgent.executeLaunchAppViaAdb].
   */
  data class LaunchApp(
    val appId: String,
  ) : AccessibilityAction {
    override val description get() = "Launch app $appId"
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
