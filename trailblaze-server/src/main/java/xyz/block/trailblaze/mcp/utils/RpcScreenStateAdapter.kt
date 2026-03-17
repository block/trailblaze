package xyz.block.trailblaze.mcp.utils

import io.ktor.util.decodeBase64Bytes
import kotlinx.coroutines.withTimeoutOrNull
import xyz.block.trailblaze.api.ScreenState
import xyz.block.trailblaze.api.ScreenshotScalingConfig
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

  override val screenshotBytes: ByteArray?
    get() = _screenshotBytes

  override val annotatedScreenshotBytes: ByteArray
    get() = _screenshotBytes ?: ByteArray(0)

  override val viewHierarchy: ViewHierarchyTreeNode
    get() = response.viewHierarchy

  override val viewHierarchyOriginal: ViewHierarchyTreeNode
    get() = response.viewHierarchy

  override val deviceWidth: Int
    get() = response.deviceWidth

  override val deviceHeight: Int
    get() = response.deviceHeight

  override val trailblazeDevicePlatform: TrailblazeDevicePlatform
    get() = TrailblazeDevicePlatform.ANDROID

  override val deviceClassifiers: List<TrailblazeDeviceClassifier>
    get() = emptyList()
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
  ): ScreenState? {
    return withTimeoutOrNull(timeoutMs) {
      // Priority 1: RPC for on-device instrumentation (most reliable for Android)
      if (mcpBridge.isOnDeviceInstrumentation()) {
        mcpBridge.getScreenStateViaRpc(
          includeScreenshot = true,
          filterViewHierarchy = false,
          screenshotScalingConfig = screenshotScalingConfig,
        )?.let { rpcResponse ->
          return@withTimeoutOrNull RpcScreenStateAdapter(rpcResponse)
        }
      }

      // Priority 2: Direct provider (most reliable for HOST mode)
      val directProvider = mcpBridge.getDirectScreenStateProvider()
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
