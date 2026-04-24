package xyz.block.trailblaze.mcp.utils

import io.ktor.util.decodeBase64Bytes
import kotlinx.coroutines.withTimeoutOrNull
import xyz.block.trailblaze.api.AndroidCompactElementList
import xyz.block.trailblaze.api.AnnotationElement
import xyz.block.trailblaze.api.ScreenState
import xyz.block.trailblaze.api.ScreenshotScalingConfig
import xyz.block.trailblaze.api.TrailblazeNode
import xyz.block.trailblaze.api.ViewHierarchyTreeNode
import xyz.block.trailblaze.devices.TrailblazeDeviceClassifier
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.mcp.TrailblazeMcpBridge
import xyz.block.trailblaze.mcp.android.ondevice.rpc.GetScreenStateResponse

/**
 * Adapter that wraps [GetScreenStateResponse] from RPC to the [ScreenState] interface.
 *
 * This enables on-device instrumentation screen state responses to be used
 * interchangeably with HOST mode screen state throughout the MCP server.
 */
class RpcScreenStateAdapter(
  private val response: GetScreenStateResponse,
) : ScreenState {

  private val _screenshotBytes: ByteArray? by lazy {
    response.screenshotBase64?.decodeBase64Bytes()
  }

  private val _annotatedScreenshotBytes: ByteArray? by lazy {
    response.annotatedScreenshotBase64?.decodeBase64Bytes() ?: _screenshotBytes
  }

  override val screenshotBytes: ByteArray?
    get() = _screenshotBytes

  /**
   * Annotated (set-of-mark) screenshot bytes. When the caller requested
   * `includeAnnotatedScreenshot = false`, the daemon does not render
   * annotation — this getter then falls back to the clean screenshot so the
   * non-null [ScreenState] contract still holds. LLM consumers that depend on
   * actually seeing the set-of-mark overlay must gate on their own
   * `includeAnnotatedScreenshot` flag rather than inspecting these bytes;
   * there is no runtime signal distinguishing "annotation rendered" from
   * "annotation was skipped and we're returning the clean image."
   */
  override val annotatedScreenshotBytes: ByteArray
    get() = _annotatedScreenshotBytes ?: ByteArray(0)

  override val viewHierarchy: ViewHierarchyTreeNode
    get() = response.viewHierarchy

  override val deviceWidth: Int
    get() = response.deviceWidth

  override val deviceHeight: Int
    get() = response.deviceHeight

  /** Cached compact elements result — shared between text representation and annotation elements. */
  private val compactElements by lazy {
    val tree = response.trailblazeNodeTree ?: return@lazy null
    AndroidCompactElementList.build(tree, screenHeight = response.deviceHeight)
  }

  override val trailblazeNodeTree: TrailblazeNode? by lazy {
    val tree = response.trailblazeNodeTree ?: return@lazy null
    val elements = compactElements ?: return@lazy tree
    val nodeIdToRef = elements.refMapping.entries.associate { (ref, nodeId) -> nodeId to ref }
    tree.withRefs(nodeIdToRef)
  }

  override val viewHierarchyTextRepresentation: String? by lazy {
    val elements = compactElements ?: return@lazy null
    val header = response.pageContextSummary ?: ""
    if (header.isNotEmpty()) "$header\n\n${elements.text}" else elements.text
  }

  override val annotationElements: List<AnnotationElement>? by lazy {
    val elements = compactElements ?: return@lazy null
    val nodeIdToRef = elements.refMapping.entries.associate { (ref, nodeId) -> nodeId to ref }
    elements.elementNodeIds.zip(elements.elementBounds).map { (id, bounds) ->
      AnnotationElement(nodeId = id, bounds = bounds, refLabel = nodeIdToRef[id])
    }
  }

  override val trailblazeDevicePlatform: TrailblazeDevicePlatform
    get() = TrailblazeDevicePlatform.ANDROID

  override val deviceClassifiers: List<TrailblazeDeviceClassifier>
    get() = response.deviceClassifiers?.map { TrailblazeDeviceClassifier(it) } ?: emptyList()
}

/**
 * Utility for capturing screen state using the best available method.
 *
 * This centralizes the screen capture logic used by both DeviceManagerToolSet
 * and SubagentOrchestrator, ensuring consistent behavior across the MCP server.
 */
object ScreenStateCaptureUtil {

  /** Default timeout for screen state capture operations */
  private const val CAPTURE_TIMEOUT_MS = 10_000L

  /**
   * Captures the current screen state using the best available method:
   *
   * 1. **On-device instrumentation**: Use RPC to query the on-device agent directly
   * 2. **HOST mode**: Use the direct screen state provider (Maestro driver)
   * 3. **Fallback**: Session-based capture
   *
   * @param mcpBridge The MCP bridge for device communication
   * @param timeoutMs Timeout for the capture operation (default: 10 seconds)
   * @param screenshotScalingConfig Configuration for scaling/compressing screenshots.
   *                                For on-device mode, scaling happens on-device before transfer
   *                                which saves bandwidth and tokens.
   * @return The captured screen state, or null if capture failed
   */
  suspend fun captureScreenState(
    mcpBridge: TrailblazeMcpBridge,
    timeoutMs: Long = CAPTURE_TIMEOUT_MS,
    screenshotScalingConfig: ScreenshotScalingConfig = ScreenshotScalingConfig.DEFAULT,
    fast: Boolean = false,
    includeAnnotatedScreenshot: Boolean = true,
    includeAllElements: Boolean = false,
  ): ScreenState? {
    return withTimeoutOrNull(timeoutMs) {
      // Priority 1: RPC for on-device instrumentation (most reliable for Android)
      if (mcpBridge.isOnDeviceInstrumentation()) {
        mcpBridge.getScreenStateViaRpc(
          includeScreenshot = !fast,
          screenshotScalingConfig = screenshotScalingConfig,
          includeAnnotatedScreenshot = includeAnnotatedScreenshot,
          includeAllElements = includeAllElements,
        )?.let { rpcResponse ->
          return@withTimeoutOrNull RpcScreenStateAdapter(rpcResponse)
        }
      }

      // Priority 2: Direct provider (most reliable for HOST mode)
      val directProvider = mcpBridge.getDirectScreenStateProvider(skipScreenshot = fast)
      directProvider?.let { provider ->
        try {
          return@withTimeoutOrNull provider(screenshotScalingConfig)
        } catch (_: Exception) {
          // Fall through to session-based capture
        }
      }

      // Priority 3: Session-based capture (fallback)
      try {
        mcpBridge.getCurrentScreenState()
      } catch (_: Exception) {
        null
      }
    }
  }
}
