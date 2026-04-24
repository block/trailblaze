package xyz.block.trailblaze.mcp.android.ondevice.rpc

import kotlinx.serialization.Serializable
import xyz.block.trailblaze.api.TrailblazeImageFormat
import xyz.block.trailblaze.api.TrailblazeNode
import xyz.block.trailblaze.api.ViewHierarchyTreeNode

/**
 * RPC request to get the current screen state from the on-device agent.
 * 
 * This provides a synchronous way to capture screen state without executing
 * any tool/action. The response includes the view hierarchy and optional
 * screenshot data.
 * 
 * @param includeScreenshot Whether to include the screenshot bytes in the response.
 *                          Default is true. Set to false for faster, text-only responses.
 * @param screenshotMaxDimension1 Maximum dimension for the longer side of the screenshot.
 *                                Default is 1536. Screenshots are scaled down (never up) to fit.
 * @param screenshotMaxDimension2 Maximum dimension for the shorter side of the screenshot.
 *                                Default is 768. Screenshots are scaled down (never up) to fit.
 * @param screenshotImageFormat Format for the screenshot output (PNG, JPEG, or WEBP).
 *                              Default is WEBP for smaller file sizes.
 * @param screenshotCompressionQuality Compression quality (0.0 to 1.0) for lossy formats (JPEG, WEBP).
 *                                     Default is 0.80.
 * @param includeAnnotatedScreenshot Whether to render and include the set-of-mark annotated
 *                                   screenshot. Annotation costs CPU, memory, and transfer bytes,
 *                                   so callers that only need the clean screenshot (e.g. CLI
 *                                   snapshots, disk logging) should pass false. Default is true
 *                                   to preserve the LLM-oriented screen-capture path.
 * @param includeAllElements Whether to return every node in the accessibility tree, including
 *                           nodes whose `isImportantForAccessibility` flag is false. Default is
 *                           false — we pre-filter on-device so the default response stays small.
 *                           `--all` / [SnapshotDetail.ALL_ELEMENTS] sets this true so the
 *                           downstream compact-list bypass actually sees the unfiltered tree.
 */
@Serializable
data class GetScreenStateRequest(
  val includeScreenshot: Boolean = true,
  val screenshotMaxDimension1: Int = 1536,
  val screenshotMaxDimension2: Int = 768,
  val screenshotImageFormat: TrailblazeImageFormat = TrailblazeImageFormat.WEBP,
  val screenshotCompressionQuality: Float = 0.80f,
  val includeAnnotatedScreenshot: Boolean = true,
  val includeAllElements: Boolean = false,
  /**
   * If true, the handler must return `Failure` when the accessibility service is not bound
   * instead of silently falling back to UiAutomator. Used by readiness polling (waitForReady)
   * for accessibility-driver flows so a UiAutomator fallback can't fake readiness while the
   * accessibility framework is still binding. Default false preserves the existing behavior
   * for normal screen-state queries that can use either backend.
   */
  val requireAndroidAccessibilityService: Boolean = false,
) : RpcRequest<GetScreenStateResponse>

/**
 * Response containing the current screen state.
 */
@Serializable
data class GetScreenStateResponse(
  /** The view hierarchy of the current screen. */
  val viewHierarchy: ViewHierarchyTreeNode,

  /**
   * Base64-encoded clean screenshot bytes (no set-of-mark overlays), or null if not
   * requested or capture failed. Matches [xyz.block.trailblaze.api.ScreenState.screenshotBytes].
   */
  val screenshotBase64: String?,

  /**
   * Base64-encoded screenshot with set-of-mark annotations applied, or null if not requested
   * or capture failed. Matches [xyz.block.trailblaze.api.ScreenState.annotatedScreenshotBytes].
   */
  val annotatedScreenshotBase64: String? = null,

  /** Device screen width in pixels. */
  val deviceWidth: Int,

  /** Device screen height in pixels. */
  val deviceHeight: Int,

  /** Rich node tree from the on-device driver (e.g., accessibility). */
  val trailblazeNodeTree: TrailblazeNode? = null,

  /** Light context string (e.g., app package + activity name). */
  val pageContextSummary: String? = null,

  /**
   * Device classifiers from the on-device agent (e.g., ["android", "phone"]).
   * Allows the host to learn the actual device type without a separate RPC call.
   * Null when the on-device server doesn't provide classifiers (older versions).
   */
  val deviceClassifiers: List<String>? = null,
)
