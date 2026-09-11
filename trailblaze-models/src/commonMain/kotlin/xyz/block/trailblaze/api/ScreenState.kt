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

/**
 * An element to annotate on a screenshot with a set-of-mark label.
 * Carries the ref label (matching the `[ref]` in the text representation) and bounds.
 */
data class AnnotationElement(
  val nodeId: Long,
  val bounds: TrailblazeNode.Bounds,
  /** The hash ref label to draw on the screenshot (e.g., "y778"). Matches the text output. */
  val refLabel: String? = null,
)

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

  /**
   * The complete, unfiltered view hierarchy tree.
   *
   * Always contains the full tree — filtering is a presentation concern handled by consumers
   * at the point of use (e.g., LLM prompt building, set-of-mark annotations).
   */
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

  /**
   * Re-renders [viewHierarchyTextRepresentation] with the given [details] applied.
   *
   * The default impl ignores [details] and returns the cached text — drivers that build
   * their text representation eagerly (Maestro Android/iOS) compute element details
   * separately in [xyz.block.trailblaze.mcp.newtools.StepToolSet.describeScreenState].
   *
   * Drivers whose text representation is rendered from cached data and can be cheaply
   * re-rendered with different detail levels (e.g. Playwright, where the compact ARIA
   * element list and bounds resolver are already available on the live [Page]) should
   * override and produce a fresh string. This is the path the snapshot CLI uses to
   * surface `--bounds` / `--offscreen` / `--all` to web platforms — without it, those
   * flags get silently dropped after the screen state is captured.
   */
  fun viewHierarchyTextRepresentation(details: Set<SnapshotDetail>): String? =
    viewHierarchyTextRepresentation

  /**
   * Elements to annotate on screenshots with set-of-mark labels.
   *
   * Returns a list of (nodeId, bounds) pairs corresponding to the elements emitted
   * in [viewHierarchyTextRepresentation] with `[nID]` refs. When non-null, the SoM
   * annotator uses these instead of deriving elements from [viewHierarchy], ensuring
   * that the numbers drawn on the screenshot exactly match the IDs in the text.
   *
   * Null by default — implementations should override when [viewHierarchyTextRepresentation]
   * uses element IDs from [trailblazeNodeTree] (Android, iOS) rather than [viewHierarchy].
   */
  val annotationElements: List<AnnotationElement>?
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
   * Optional structural-coverage assessment of the captured hierarchy at the moment this screen
   * state was built. Populated on Android (where the on-device settle gate watches for partial /
   * truncated accessibility trees); null on every other driver and on Android captures where the
   * gate couldn't form an opinion (gate disabled, window root unreadable). Logs that carry a
   * captured screen state surface this so consumers can spot tree-truncation trends without
   * grepping `[capture-coverage]` lines out of logcat.
   */
  val captureCoverage: CaptureCoverage?
    get() = null

  /**
   * How many nodes this capture asked the device for and did not get back while building
   * [trailblazeNodeTree], or `null` when the driver cannot measure it. Scoped to that tree on
   * purpose — a driver that builds several projections of one screen can lose a node in one and
   * not another, so this describes the tree the consumers of this signal actually resolve
   * against.
   *
   * On Android every node in an accessibility capture is a live fetch from the app, and an app
   * whose main thread is blocked answers those fetches with null — so a tree can be missing whole
   * subtrees while looking perfectly well-formed. `0` means the capture holds every node the
   * device advertised; anything greater means a subtree is missing and a consumer MUST NOT read
   * "not in this tree" as "not on screen".
   *
   * `null` means unknown, not complete: iOS, Playwright and Compose captures have no equivalent
   * count and must not be made to claim one. Every consumer treats unknown exactly as it treated
   * the world before this signal existed, so a driver that cannot measure completeness keeps its
   * current behaviour. Read it through [isCaptureKnownPartial] rather than comparing by hand, so
   * the unknown case can't be accidentally folded into "partial".
   */
  val droppedNodeFetches: Int?
    get() = null

  /**
   * True only when this capture is KNOWN to have lost nodes ([droppedNodeFetches] greater than
   * zero). Unknown completeness (`null`) is false: an absence verdict drawn from a driver that
   * cannot measure completeness is exactly as trustworthy as it was before.
   */
  val isCaptureKnownPartial: Boolean
    get() = (droppedNodeFetches ?: 0) > 0

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
