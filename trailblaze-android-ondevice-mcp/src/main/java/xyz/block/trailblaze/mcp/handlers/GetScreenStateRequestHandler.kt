package xyz.block.trailblaze.mcp.handlers

import io.ktor.util.encodeBase64
import xyz.block.trailblaze.android.accessibility.AccessibilityServiceScreenState
import xyz.block.trailblaze.android.accessibility.TrailblazeAccessibilityService
import xyz.block.trailblaze.android.uiautomator.AndroidOnDeviceUiAutomatorScreenState
import xyz.block.trailblaze.api.ScreenState
import xyz.block.trailblaze.api.ScreenshotScalingConfig
import xyz.block.trailblaze.mcp.RpcHandler
import xyz.block.trailblaze.mcp.android.ondevice.rpc.GetScreenStateRequest
import xyz.block.trailblaze.mcp.android.ondevice.rpc.GetScreenStateResponse
import xyz.block.trailblaze.mcp.android.ondevice.rpc.RpcResult
import xyz.block.trailblaze.util.Console

/**
 * RPC handler for getting the current screen state on-device.
 *
 * Chooses the capture method based on the active driver:
 * - **Accessibility driver**: Uses [AccessibilityServiceScreenState] which provides a rich
 *   [TrailblazeNode] tree with [DriverNodeDetail.AndroidAccessibility] detail (isClickable,
 *   isEditable, etc.) — essential for accurate screen summaries.
 * - **UiAutomator/Instrumentation**: Falls back to [AndroidOnDeviceUiAutomatorScreenState].
 */
class GetScreenStateRequestHandler : RpcHandler<GetScreenStateRequest, GetScreenStateResponse> {

  override suspend fun handle(request: GetScreenStateRequest): RpcResult<GetScreenStateResponse> {
    return try {
      val useAccessibility = TrailblazeAccessibilityService.isServiceRunning()
      Console.log("📱 GetScreenStateRequestHandler: Capturing screen state (accessibility=$useAccessibility, screenshot=${request.includeScreenshot}, scale=${request.screenshotMaxDimension1}x${request.screenshotMaxDimension2})")

      // Build scaling config from request parameters
      val scalingConfig = ScreenshotScalingConfig(
        maxDimension1 = request.screenshotMaxDimension1,
        maxDimension2 = request.screenshotMaxDimension2,
        imageFormat = request.screenshotImageFormat,
        compressionQuality = request.screenshotCompressionQuality,
      )

      // Use the accessibility driver's screen state when available — it provides a rich
      // TrailblazeNode tree. Fall back to UiAutomator for instrumentation mode.
      // Wait for the UI to settle first so we capture a stable screen (e.g., after
      // navigation or data loading), not a mid-transition state.
      val screenState: ScreenState = if (useAccessibility) {
        TrailblazeAccessibilityService.waitForSettled()
        AccessibilityServiceScreenState(
          screenshotScalingConfig = scalingConfig,
          setOfMarkEnabled = request.setOfMarkEnabled,
          includeScreenshot = request.includeScreenshot,
        )
      } else {
        AndroidOnDeviceUiAutomatorScreenState(
          screenshotScalingConfig = scalingConfig,
          setOfMarkEnabled = request.setOfMarkEnabled,
          includeScreenshot = request.includeScreenshot,
        )
      }
      
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
          trailblazeNodeTree = screenState.trailblazeNodeTree,
          pageContextSummary = screenState.pageContextSummary,
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
