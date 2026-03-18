package xyz.block.trailblaze.api

import xyz.block.trailblaze.devices.TrailblazeDeviceClassifier
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform

/**
 * Prefixes for header lines that describe static device characteristics.
 *
 * Used by formatters when emitting header lines and by [ScreenState.pageContextSummary]
 * to filter them out (the device tool already exposes this info and it doesn't change
 * between actions).
 */
enum class DeviceInfoPrefix(val prefix: String) {
  PLATFORM("Platform:"),
  SCREEN("Screen:"),
  DEVICE("Device:"),
  BROWSER("Browser:"),
  VIEWPORT("Viewport:"),
  ;

  /** Formats a complete header line: `"Platform: Android"`. */
  fun line(value: String): String = "$prefix $value"
}

interface ScreenState {
  /**
   * Returns the clean screenshot bytes without any debugging annotations.
   *
   * Implementations may apply scaling (via [ScreenshotScalingConfig]) to produce
   * consistently-sized output for logging and storage. Annotated versions with
   * set-of-mark overlays are available via [annotatedScreenshotBytes].
   *
   * - Always returns the clean, unmarked screenshot
   * - Used for logging, snapshots, and compliance documentation
   * - May be null if screenshot capture failed
   */
  val screenshotBytes: ByteArray?

  /**
   * Returns screenshot bytes with set-of-mark annotations applied.
   * 
   * **Memory Optimization**: This property generates annotated screenshots on-demand
   * without caching. Annotated screenshots are only used for LLM requests and then
   * discarded to minimize memory usage.
   * 
   * - Generates set-of-mark annotations lazily when accessed
   * - If set-of-mark is disabled: Returns the clean screenshot (same as screenshotBytes)
   * - May be null if screenshot capture failed
   * 
   * Defaults to screenshotBytes for backward compatibility with existing implementations.
   */
  val annotatedScreenshotBytes: ByteArray?
    get() = screenshotBytes

  val deviceWidth: Int

  val deviceHeight: Int

  val viewHierarchyOriginal: ViewHierarchyTreeNode

  val viewHierarchy: ViewHierarchyTreeNode

  /**
   * Optional platform-native text representation of the view hierarchy for LLM consumption.
   *
   * When non-null, this text is sent to the LLM instead of JSON-serializing [viewHierarchy].
   * This allows platform-specific drivers (e.g., Playwright) to provide optimized, compact
   * representations that are far smaller than the generic [ViewHierarchyTreeNode] JSON tree.
   *
   * - Returns `null` by default, which signals the LLM prompt builder to fall back to
   *   JSON-serializing [viewHierarchy] (the existing behavior for Maestro-based drivers).
   * - Implementations should return a human-readable text format suitable for LLM consumption.
   */
  val viewHierarchyTextRepresentation: String?
    get() = null

  val trailblazeDevicePlatform: TrailblazeDevicePlatform

  val deviceClassifiers: List<TrailblazeDeviceClassifier>

  /**
   * Optional driver-native view tree using [TrailblazeNode] with rich [DriverNodeDetail].
   *
   * Non-null for drivers that produce native view trees (accessibility, Playwright, Compose).
   * Used by [TrailblazeNodeSelectorGenerator] at recording time and
   * [TrailblazeNodeSelectorResolver] at playback time.
   *
   * Null for Maestro-based drivers that only produce [ViewHierarchyTreeNode].
   */
  val trailblazeNodeTree: TrailblazeNode?
    get() = null

  /**
   * Current-state context for the outer agent, prepended to [screenSummaryAfter] in
   * [ExecutionResult.Success] so it knows where it landed after each action.
   *
   * Extracts the header block (lines before the first `\n\n`) from
   * [viewHierarchyTextRepresentation], then strips static device-info lines whose values
   * never change within a session (platform, screen size, browser engine, viewport dimensions).
   * Those are already accessible via `device(action=INFO)` and add noise here.
   *
   * What remains is pure run-time state:
   * - Android/iOS: `App: com.example.myapp`
   * - Web/Playwright: `URL: https://...`, `Title: ...`, `Scroll: ...`, `Focused: ...`, etc.
   *
   * Returns null when no header is present (e.g. Compose) or when only device-info lines
   * remain after filtering — signals [BridgeUiActionExecutor] to omit the prefix entirely.
   */
  val pageContextSummary: String?
    get() = viewHierarchyTextRepresentation
      ?.substringBefore("\n\n", missingDelimiterValue = "")
      ?.lines()
      ?.filter { line -> DeviceInfoPrefix.entries.none { line.startsWith(it.prefix) } }
      ?.joinToString("\n")
      ?.trim()
      ?.takeIf { it.isNotBlank() }

}
