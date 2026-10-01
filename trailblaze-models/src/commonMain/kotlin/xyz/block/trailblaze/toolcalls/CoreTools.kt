package xyz.block.trailblaze.toolcalls

import kotlinx.serialization.Serializable
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform

/**
 * Core tool names used throughout Trailblaze.
 *
 * These constants replace string literals for tool names, enabling:
 * - Type-safe references throughout the codebase
 * - Easy refactoring and renaming
 * - Centralized documentation of available tools
 *
 * ## Platform Considerations
 *
 * Tools are designed to be platform-agnostic where possible. The same
 * tool name (e.g., [NAVIGATE_BACK]) maps to platform-specific behavior:
 * - Android: Back button / gesture
 * - iOS: Edge swipe or dismiss gesture
 *
 * The actual implementation handles platform differences; callers use
 * the same tool name across platforms.
 *
 * @see CoreToolMetadata for extended metadata about each tool
 */
object CoreTools {

  // =========================================================================
  // Navigation Tools
  // =========================================================================

  /**
   * Navigate back to the previous screen.
   *
   * Platform behavior:
   * - Android: Simulates back button press
   * - iOS: Performs dismiss gesture or back navigation
   */
  const val NAVIGATE_BACK = "navigateBack"

  /**
   * Go to the device home screen.
   *
   * Platform behavior:
   * - Android: Simulates home button press
   * - iOS: Performs home gesture
   */
  const val GO_HOME = "goHome"

  // =========================================================================
  // Interaction Tools
  // =========================================================================

  /**
   * Tap on a UI element identified by selector.
   */
  const val TAP_ON_ELEMENT = "tapOnElement"

  /**
   * Tap on a UI element by its stable ref ID from the snapshot.
   */
  const val TAP = "tap"

  /**
   * Tap at specific screen coordinates.
   */
  const val TAP_ON_POINT = "tapOnPoint"

  /**
   * Long press on a UI element.
   */
  const val LONG_PRESS = "longPress"

  /**
   * Swipe in a direction.
   */
  const val SWIPE = "swipe"

  /**
   * Scroll until text is visible.
   */
  const val SCROLL_UNTIL_VISIBLE = "scrollUntilTextIsVisible"

  // =========================================================================
  // Input Tools
  // =========================================================================

  /**
   * Input text into the focused field.
   */
  const val INPUT_TEXT = "inputText"

  /**
   * Erase text from the focused field.
   */
  const val ERASE_TEXT = "eraseText"

  /**
   * Hide the on-screen keyboard.
   *
   * Platform behavior:
   * - Android: Sends hide keyboard command
   * - iOS: Taps outside input field or sends dismiss
   */
  const val HIDE_KEYBOARD = "hideKeyboard"

  /**
   * Press a specific key.
   */
  const val PRESS_KEY = "pressKey"

  // =========================================================================
  // App Lifecycle Tools
  // =========================================================================

  /**
   * Launch an app by package ID / bundle ID, with a launch mode. Deprecated in favor of
   * [OPEN_APP] plus a target's trailhead; kept for recorded trails.
   */
  const val LAUNCH_APP = "launchApp"

  /**
   * Open an app, or bring it to the front, without changing its state; returns once it is on screen.
   */
  const val OPEN_APP = "openApp"

  /**
   * Stop/kill an app.
   */
  const val STOP_APP = "stopApp"

  /**
   * Open a URL in the default browser or app.
   */
  const val OPEN_URL = "openUrl"

  // =========================================================================
  // Utility Tools
  // =========================================================================

  /**
   * Settle until the UI goes quiet, bounded by a caller-supplied ceiling. Returns as soon as the
   * UI is idle, so on a static screen it returns in ~150ms rather than the full duration. Use
   * [SLEEP] when wall-clock time genuinely has to elapse.
   */
  const val WAIT = "wait"

  /**
   * Wait until the accessibility tree changes from its entry baseline, then settles.
   * Android-only today; other drivers degrade to a fixed timed wait.
   */
  const val WAIT_FOR_CHANGE = "waitForChange"

  /**
   * Block for a fixed wall-clock duration, never returning early. The one tool that actually
   * consumes the time it is asked for — unlike [WAIT] / [WAIT_FOR_CHANGE], which are settles.
   */
  const val SLEEP = "sleep"

  /**
   * Control network connectivity.
   */
  const val NETWORK_CONNECTION = "networkConnection"

  // =========================================================================
  // Assertion/Verification Tools
  // =========================================================================

  /**
   * Assert that text is visible on screen.
   */
  const val ASSERT_VISIBLE_WITH_TEXT = "assertVisibleWithText"

  /**
   * Assert that text is NOT visible on screen.
   */
  const val ASSERT_NOT_VISIBLE_WITH_TEXT = "assertNotVisibleWithText"

  /**
   * Assert with AI analysis.
   */
  const val ASSERT_WITH_AI = "assertWithAi"

  // =========================================================================
  // Status/Objective Tools
  // =========================================================================

  /**
   * Report objective status.
   */
  const val OBJECTIVE_STATUS = "objectiveStatus"

  // =========================================================================
  // Legacy Tool Names (for compatibility)
  // =========================================================================

  /**
   * Legacy name for [NAVIGATE_BACK].
   * @deprecated Use [NAVIGATE_BACK] instead for platform-agnostic code.
   */
  @Deprecated("Use NAVIGATE_BACK for platform-agnostic code", ReplaceWith("NAVIGATE_BACK"))
  const val PRESS_BACK = "pressBack"

  // =========================================================================
  // Tool Name Classification Helpers
  // =========================================================================
  //
  // Different tool sets may use different names for the same action.
  // For example, "type" (AndroidWorld) and "inputText" (Trailblaze) both
  // represent text input. These helpers let shared code classify tool actions
  // without depending on specific tool set modules.

  /** Well-known tool names that represent text input actions. */
  private val TEXT_INPUT_NAMES = setOf(
    INPUT_TEXT,       // "inputText"   — Trailblaze core
    "type",           // AndroidWorld canonical
    "type_into",      // Trailblaze compound: tap + type + dismiss keyboard
  )

  /** Well-known tool names that represent tap/click actions. */
  private val TAP_NAMES = setOf(
    TAP,                       // "tap"                      — Trailblaze core (ref-based)
    LONG_PRESS,                // "longPress"                — Trailblaze core (ref-based hold)
    TAP_ON_POINT,              // "tapOnPoint"              — Trailblaze core
    TAP_ON_ELEMENT,            // "tapOnElement"             — Trailblaze core
    "click",                   // AndroidWorld canonical
  )

  /** Returns true if [toolName] is a tap/click action. */
  fun isTapAction(toolName: String): Boolean =
    TAP_NAMES.any { it.equals(toolName, ignoreCase = true) }
}

/**
 * Execution modes for tool availability.
 *
 * Determines where a tool can run based on the deployment context.
 */
@Serializable
enum class ToolExecutionMode {
  /**
   * Tool can run in host mode (desktop controlling device).
   *
   * Host mode is when the Trailblaze agent runs on a desktop machine
   * and controls devices remotely via ADB, XCTest, etc.
   */
  HOST,

  /**
   * Tool can run on-device (inside test APK/app bundle).
   *
   * On-device mode is when the Trailblaze agent runs directly on the
   * mobile device, typically in test farm scenarios like Firebase Test Lab.
   */
  ON_DEVICE,
}

/**
 * Source of a tool's implementation.
 *
 * Indicates where the tool comes from and how it's executed.
 */
@Serializable
enum class ToolSource {
  /**
   * Built-in Trailblaze tool implemented in Kotlin.
   * Always available, platform differences handled internally.
   */
  BUILTIN,

  /**
   * Tool contributed via MCP from an external server.
   * Availability depends on MCP server connection.
   */
  MCP_EXTERNAL,

  /**
   * Tool contributed via Python script.
   * Only available in host mode (Python not available on-device).
   */
  PYTHON_SCRIPT,

  /**
   * Tool contributed via shell command.
   * May have platform-specific limitations.
   */
  SHELL_COMMAND,
}

/**
 * Metadata about a tool's capabilities and compatibility.
 *
 * This information is used by:
 * - The agent to filter available tools based on current context
 * - UI to show tool compatibility information
 *
 * @property name The tool name (should match a constant in [CoreTools])
 * @property supportedPlatforms Platforms this tool works on
 * @property supportedExecutionModes Execution modes this tool supports
 * @property source Where this tool implementation comes from
 * @property requiresNetwork Whether this tool requires network access
 * @property description Human-readable description
 */
@Serializable
data class ToolCompatibility(
  /** The tool name */
  val name: String,

  /** Platforms this tool supports */
  val supportedPlatforms: Set<TrailblazeDevicePlatform>,

  /** Execution modes this tool supports */
  val supportedExecutionModes: Set<ToolExecutionMode>,

  /** Source of this tool's implementation */
  val source: ToolSource = ToolSource.BUILTIN,

  /** Whether this tool requires network access */
  val requiresNetwork: Boolean = false,

  /** Human-readable description */
  val description: String? = null,
) {
  /**
   * Checks if this tool is compatible with the given context.
   *
   * @param platform The target platform
   * @param executionMode The execution mode
   * @param networkAvailable Whether network is available
   * @return true if the tool can be used in this context
   */
  fun isCompatibleWith(
    platform: TrailblazeDevicePlatform,
    executionMode: ToolExecutionMode,
    networkAvailable: Boolean = true,
  ): Boolean {
    if (platform !in supportedPlatforms) return false
    if (executionMode !in supportedExecutionModes) return false
    if (requiresNetwork && !networkAvailable) return false
    return true
  }

  companion object {
    /**
     * Creates compatibility for a host-only tool (e.g., Python scripts).
     */
    fun hostOnly(
      name: String,
      source: ToolSource,
      platforms: Set<TrailblazeDevicePlatform> = setOf(
        TrailblazeDevicePlatform.ANDROID,
        TrailblazeDevicePlatform.IOS,
      ),
      description: String? = null,
    ) = ToolCompatibility(
      name = name,
      supportedPlatforms = platforms,
      supportedExecutionModes = setOf(ToolExecutionMode.HOST),
      source = source,
      description = description,
    )
  }
}
