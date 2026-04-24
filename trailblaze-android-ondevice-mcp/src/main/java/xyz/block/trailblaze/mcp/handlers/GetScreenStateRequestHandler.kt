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
import xyz.block.trailblaze.devices.TrailblazeDeviceClassifier
import xyz.block.trailblaze.util.Console

/**
 * RPC handler for getting the current screen state on-device.
 *
 * Chooses the capture method based on the active driver:
 * - **Accessibility driver**: Uses [AccessibilityServiceScreenState] which provides a rich
 *   [TrailblazeNode] tree with [DriverNodeDetail.AndroidAccessibility] detail (isClickable,
 *   isEditable, etc.) — essential for accurate screen summaries.
 * - **UiAutomator/Instrumentation**: Falls back to [AndroidOnDeviceUiAutomatorScreenState].
 *
 * @param deviceClassifiers Device classifiers to include in the response so the host
 *   can learn the actual device type without a separate RPC call.
 */
class GetScreenStateRequestHandler(
  private val deviceClassifiers: List<TrailblazeDeviceClassifier> = emptyList(),
) : RpcHandler<GetScreenStateRequest, GetScreenStateResponse> {

  override suspend fun handle(request: GetScreenStateRequest): RpcResult<GetScreenStateResponse> {
    return try {
      val useAccessibility = TrailblazeAccessibilityService.isServiceRunning()
      if (request.requireAndroidAccessibilityService && !useAccessibility) {
        // Readiness polling for accessibility-driver flows must not accept a UiAutomator-fallback
        // success. Surface this as a Failure so `waitForReady` keeps polling until the service
        // actually binds.
        return RpcResult.Failure(
          errorType = RpcResult.ErrorType.UNKNOWN_ERROR,
          message = "Accessibility service not yet bound",
          details = "requireAndroidAccessibilityService=true but TrailblazeAccessibilityService.isServiceRunning() is false",
        )
      }
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
          includeScreenshot = request.includeScreenshot,
          includeAllElements = request.includeAllElements,
        )
      } else {
        AndroidOnDeviceUiAutomatorScreenState(
          screenshotScalingConfig = scalingConfig,
          includeScreenshot = request.includeScreenshot,
        )
      }

      Console.log("📱 GetScreenStateRequestHandler: Screen captured (${screenState.deviceWidth}x${screenState.deviceHeight})")

      RpcResult.Success(buildResponse(request, screenState, deviceClassifiers))
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

  companion object {
    /**
     * Builds the wire response from a captured [ScreenState] and the incoming
     * [request]. Annotation is expensive to render and inflates the transfer,
     * so [ScreenState.annotatedScreenshotBytes] is only read when the caller
     * explicitly asks for it — LLM paths need it, CLI snapshots and disk
     * logging don't. Pure so it can be unit-tested without the Android
     * framework.
     */
    internal fun buildResponse(
      request: GetScreenStateRequest,
      screenState: ScreenState,
      deviceClassifiers: List<TrailblazeDeviceClassifier> = emptyList(),
    ): GetScreenStateResponse {
      val screenshotBase64 = if (request.includeScreenshot) {
        screenState.screenshotBytes?.encodeBase64()
      } else {
        null
      }
      val annotatedScreenshotBase64 =
        if (request.includeScreenshot && request.includeAnnotatedScreenshot) {
          screenState.annotatedScreenshotBytes?.encodeBase64()
        } else {
          null
        }
      val classifierStrings = deviceClassifiers
        .map { it.classifier }
        .takeIf { it.isNotEmpty() }
      return GetScreenStateResponse(
        viewHierarchy = screenState.viewHierarchy,
        screenshotBase64 = screenshotBase64,
        annotatedScreenshotBase64 = annotatedScreenshotBase64,
        deviceWidth = screenState.deviceWidth,
        deviceHeight = screenState.deviceHeight,
        trailblazeNodeTree = screenState.trailblazeNodeTree,
        pageContextSummary = screenState.pageContextSummary,
        deviceClassifiers = classifierStrings,
      )
    }
  }
}
