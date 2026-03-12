package xyz.block.trailblaze.mcp.handlers

import io.ktor.util.encodeBase64
import xyz.block.trailblaze.android.uiautomator.AndroidOnDeviceUiAutomatorScreenState
import xyz.block.trailblaze.api.ScreenshotScalingConfig
import xyz.block.trailblaze.mcp.RpcHandler
import xyz.block.trailblaze.mcp.android.ondevice.rpc.GetScreenStateRequest
import xyz.block.trailblaze.mcp.android.ondevice.rpc.GetScreenStateResponse
import xyz.block.trailblaze.mcp.android.ondevice.rpc.RpcResult
import xyz.block.trailblaze.util.Console

/**
 * RPC handler for getting the current screen state on-device.
 * 
 * This handler captures the current screen state synchronously using UiAutomator,
 * without needing to execute any tool or action. It's specifically designed for
 * MCP subagent use cases where the client needs quick access to screen state
 * between tool executions.
 * 
 * Key features:
 * - No session/tool execution required - direct screen capture
 * - Configurable screenshot inclusion for performance
 * - Configurable view hierarchy filtering for context optimization
 * - Configurable screenshot scaling for bandwidth optimization
 * - Works with the same AndroidOnDeviceUiAutomatorScreenState used by tools
 */
class GetScreenStateRequestHandler : RpcHandler<GetScreenStateRequest, GetScreenStateResponse> {
  
  override suspend fun handle(request: GetScreenStateRequest): RpcResult<GetScreenStateResponse> {
    return try {
      Console.log("📱 GetScreenStateRequestHandler: Capturing screen state (screenshot=${request.includeScreenshot}, filter=${request.filterViewHierarchy}, scale=${request.screenshotMaxDimension1}x${request.screenshotMaxDimension2})")
      
      // Build scaling config from request parameters
      val scalingConfig = ScreenshotScalingConfig(
        maxDimension1 = request.screenshotMaxDimension1,
        maxDimension2 = request.screenshotMaxDimension2,
        imageFormat = request.screenshotImageFormat,
        compressionQuality = request.screenshotCompressionQuality,
      )
      
      // Capture screen state using the same mechanism tools use
      val screenState = AndroidOnDeviceUiAutomatorScreenState(
        filterViewHierarchy = request.filterViewHierarchy,
        screenshotScalingConfig = scalingConfig,
        setOfMarkEnabled = request.setOfMarkEnabled,
        includeScreenshot = request.includeScreenshot,
      )
      
      // Get screenshot bytes and encode to base64 if requested
      val screenshotBase64 = if (request.includeScreenshot) {
        val bytes = if (request.setOfMarkEnabled) {
          screenState.annotatedScreenshotBytes
        } else {
          screenState.screenshotBytes
        }
        bytes?.encodeBase64()
      } else {
        null
      }

      Console.log("📱 GetScreenStateRequestHandler: Screen captured (${screenState.deviceWidth}x${screenState.deviceHeight})")
      
      RpcResult.Success(
        GetScreenStateResponse(
          viewHierarchy = screenState.viewHierarchy,
          screenshotBase64 = screenshotBase64,
          deviceWidth = screenState.deviceWidth,
          deviceHeight = screenState.deviceHeight,
        )
      )
    } catch (e: Exception) {
      Console.log("❌ GetScreenStateRequestHandler: Failed to capture screen state: ${e.message}")
      e.printStackTrace()
      RpcResult.Failure(
        errorType = RpcResult.ErrorType.UNKNOWN_ERROR,
        message = "Failed to capture screen state: ${e.message}",
        details = e.stackTraceToString(),
      )
    }
  }
}
