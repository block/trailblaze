package xyz.block.trailblaze.mcp.handlers

import io.ktor.util.encodeBase64
import xyz.block.trailblaze.android.InstrumentationArgUtil
import xyz.block.trailblaze.android.accessibility.AccessibilityServiceScreenState
import xyz.block.trailblaze.android.accessibility.MigrationTreeCapture
import xyz.block.trailblaze.android.accessibility.TrailblazeAccessibilityService
import xyz.block.trailblaze.android.uiautomator.AndroidOnDeviceUiAutomatorScreenState
import xyz.block.trailblaze.api.ScreenState
import xyz.block.trailblaze.api.ScreenshotScalingConfig
import xyz.block.trailblaze.api.TrailblazeNode
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
    return when (val captured = capture(request)) {
      is RpcResult.Failure -> captured
      is RpcResult.Success -> RpcResult.Success(
        buildResponse(
          request = request,
          screenState = captured.data.screenState,
          deviceClassifiers = deviceClassifiers,
          driverMigrationTreeNode = captured.data.driverMigrationTreeNode,
          capturedAtDeviceMs = captured.data.capturedAtDeviceMs,
        ),
      )
    }
  }

  /** Binary twin of [handle] that keeps screenshot bytes raw instead of base64 encoding them. */
  internal suspend fun handleBinary(request: GetScreenStateRequest): RpcResult<GetScreenStateResponse> {
    return when (val captured = capture(request)) {
      is RpcResult.Failure -> captured
      is RpcResult.Success -> RpcResult.Success(
        buildBinaryResponse(
          request = request,
          screenState = captured.data.screenState,
          deviceClassifiers = deviceClassifiers,
          driverMigrationTreeNode = captured.data.driverMigrationTreeNode,
          capturedAtDeviceMs = captured.data.capturedAtDeviceMs,
        ),
      )
    }
  }

  private suspend fun capture(request: GetScreenStateRequest): RpcResult<CapturedScreenState> {
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
        // Skip the accessibility-event settle wait on the mirror-only fast path — that wait
        // exists to give the tree a stable window to read from, and we're not reading the
        // tree. Saves ~200-500 ms per frame on top of the tree-skip itself.
        if (request.includeTree) {
          TrailblazeAccessibilityService.waitForSettled()
        }
        AccessibilityServiceScreenState(
          screenshotScalingConfig = scalingConfig,
          includeScreenshot = request.includeScreenshot,
          includeAllElements = request.includeAllElements,
          // Forward the migration-mode flag so the on-device capture replaces the
          // accessibility-derived viewHierarchy with a real UiAutomator dump when set.
          // Without this propagation, host-side `captureScreenState()` calls would always
          // get the accessibility-shape projection even when the migration capture is
          // requested via instrumentation args, breaking 100% Maestro-fidelity migration.
          captureSecondaryTree = InstrumentationArgUtil.shouldCaptureSecondaryTree(),
          includeTree = request.includeTree,
        )
      } else {
        AndroidOnDeviceUiAutomatorScreenState(
          screenshotScalingConfig = scalingConfig,
          includeScreenshot = request.includeScreenshot,
          includeTree = request.includeTree,
        )
      }

      // Stamped as soon as the ScreenState constructor returns — i.e. when the (screenshot,
      // tree) pair is final. Device epoch, same clock as on-device session logs, so the host
      // can correlate this capture against other device-clock timestamps.
      val capturedAtDeviceMs = System.currentTimeMillis()

      Console.log("📱 GetScreenStateRequestHandler: Screen captured (${screenState.deviceWidth}x${screenState.deviceHeight})")

      // Side-channel migration tree. Captured separately from [screenState] so the primary
      // tree shape stays canonical for runtime tools/reports — the migration tree rides on
      // the wire response in its own field and is reassembled host-side via
      // [MigrationScreenState] before being persisted on the snapshot log. On the
      // accessibility driver, the primary `trailblazeNodeTree` is already the right shape;
      // we still re-capture (cheap) to keep both code paths uniform and avoid divergence
      // if the primary tree's filtering policy changes.
      val driverMigrationTreeNode: TrailblazeNode? =
        if (InstrumentationArgUtil.shouldCaptureSecondaryTree()) {
          MigrationTreeCapture.captureOrNull()
        } else {
          null
        }

      RpcResult.Success(CapturedScreenState(screenState, driverMigrationTreeNode, capturedAtDeviceMs))
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

  private data class CapturedScreenState(
    val screenState: ScreenState,
    val driverMigrationTreeNode: TrailblazeNode?,
    /** Device-epoch stamp taken when the (screenshot, tree) pair was final. */
    val capturedAtDeviceMs: Long,
  )

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
      driverMigrationTreeNode: TrailblazeNode? = null,
      capturedAtDeviceMs: Long? = null,
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
        driverMigrationTreeNode = driverMigrationTreeNode,
        pageContextSummary = screenState.pageContextSummary,
        deviceClassifiers = classifierStrings,
        capturedAtDeviceMs = capturedAtDeviceMs,
      )
    }

    /** Builds the same domain response without the JSON transport's base64 conversion. */
    internal fun buildBinaryResponse(
      request: GetScreenStateRequest,
      screenState: ScreenState,
      deviceClassifiers: List<TrailblazeDeviceClassifier> = emptyList(),
      driverMigrationTreeNode: TrailblazeNode? = null,
      capturedAtDeviceMs: Long? = null,
    ): GetScreenStateResponse {
      val screenshotBytes = if (request.includeScreenshot) screenState.screenshotBytes else null
      val annotatedScreenshotBytes =
        if (request.includeScreenshot && request.includeAnnotatedScreenshot) {
          screenState.annotatedScreenshotBytes
        } else {
          null
        }
      return GetScreenStateResponse(
        viewHierarchy = screenState.viewHierarchy,
        screenshotBase64 = null,
        annotatedScreenshotBase64 = null,
        deviceWidth = screenState.deviceWidth,
        deviceHeight = screenState.deviceHeight,
        trailblazeNodeTree = screenState.trailblazeNodeTree,
        driverMigrationTreeNode = driverMigrationTreeNode,
        pageContextSummary = screenState.pageContextSummary,
        deviceClassifiers = deviceClassifiers.map { it.classifier }.takeIf { it.isNotEmpty() },
        capturedAtDeviceMs = capturedAtDeviceMs,
      ).apply {
        this.screenshotBytes = screenshotBytes
        this.annotatedScreenshotBytes = annotatedScreenshotBytes
      }
    }
  }
}
