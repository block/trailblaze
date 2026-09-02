package xyz.block.trailblaze.android.rpc

import xyz.block.trailblaze.android.InstrumentationArgUtil
import xyz.block.trailblaze.android.accessibility.AccessibilityServiceScreenState
import xyz.block.trailblaze.android.accessibility.MigrationTreeCapture
import xyz.block.trailblaze.android.accessibility.TrailblazeAccessibilityService
import xyz.block.trailblaze.android.uiautomator.AndroidOnDeviceUiAutomatorScreenState
import xyz.block.trailblaze.api.ScreenState
import xyz.block.trailblaze.api.ScreenshotScalingConfig
import xyz.block.trailblaze.api.TrailblazeNode
import xyz.block.trailblaze.mcp.android.ondevice.rpc.GetScreenStateRequest
import xyz.block.trailblaze.mcp.android.ondevice.rpc.OnDeviceCapturedScreenState
import xyz.block.trailblaze.mcp.android.ondevice.rpc.OnDeviceScreenStateCaptor
import xyz.block.trailblaze.mcp.android.ondevice.rpc.OnDeviceScreenStateNotReadyException
import xyz.block.trailblaze.util.Console

/**
 * The accessibility runner's [OnDeviceScreenStateCaptor]: capture through the bound
 * [TrailblazeAccessibilityService] when it is running (a rich [TrailblazeNode] tree with
 * AndroidAccessibility detail), falling back to UiAutomator for instrumentation mode. This is the
 * capture path `GetScreenStateRequestHandler` hardcoded before the captor seam existed — moved
 * here verbatim so the RPC server module carries no driver dependency.
 */
object AccessibilityScreenStateCaptor : OnDeviceScreenStateCaptor {

  override suspend fun capture(request: GetScreenStateRequest): OnDeviceCapturedScreenState {
    val useAccessibility = TrailblazeAccessibilityService.isServiceRunning()
    if (request.requireAndroidAccessibilityService && !useAccessibility) {
      // Readiness polling for accessibility-driver flows must not accept a UiAutomator-fallback
      // success. Throw so the handler surfaces a Failure and `waitForReady` keeps polling until
      // the service actually binds. The message is the poll's signal — keep it stable. The typed
      // exception is what keeps this expected cold-start answer off the handler's stack-trace
      // logging path, which a 60s poll would otherwise hit 120 times.
      throw OnDeviceScreenStateNotReadyException("Accessibility service not yet bound")
    }
    Console.log("📱 AccessibilityScreenStateCaptor: Capturing screen state (accessibility=$useAccessibility, screenshot=${request.includeScreenshot}, scale=${request.screenshotMaxDimension1}x${request.screenshotMaxDimension2})")

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

    Console.log("📱 AccessibilityScreenStateCaptor: Screen captured (${screenState.deviceWidth}x${screenState.deviceHeight})")

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

    return OnDeviceCapturedScreenState(screenState, driverMigrationTreeNode, capturedAtDeviceMs)
  }
}
