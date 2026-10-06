package xyz.block.trailblaze.android.rpc

import xyz.block.trailblaze.android.ACCESSIBILITY_SERVICE_NOT_RUNNING
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
 * AndroidAccessibility detail). An unbound service is a failure, never a UiAutomator capture:
 * that tree cannot resolve accessibility selectors (see [unboundServiceFailure]). Migration mode alone keeps the
 * UiAutomator primary, because migrate-trail resolves the recorded selectors against it.
 */
object AccessibilityScreenStateCaptor : OnDeviceScreenStateCaptor {

  /** The readiness poll's signal: `waitForReady` keeps polling while it sees this. Keep it stable. */
  internal const val NOT_YET_BOUND: String = "Accessibility service not yet bound"

  /**
   * What a capture with the service unbound must throw, or null when it may proceed (service
   * running, or migration mode, whose UiAutomator primary is what migrate-trail resolves against).
   *
   * Both answers are the typed not-ready exception, which the handler returns as a one-line failure
   * with no stack trace: a readiness poll hits it up to 120 times on cold start, and the recording
   * mirror re-polls every frame while the service is down. What differs is the message. A
   * readiness poll ([requireService]) gets the stable [NOT_YET_BOUND] it waits on; any other
   * capture gets [ACCESSIBILITY_SERVICE_NOT_RUNNING], because a service that was bound and died
   * mid-run is not a cold start and must say how to recover.
   */
  internal fun unboundServiceFailure(
    serviceRunning: Boolean,
    requireService: Boolean,
    migrationMode: Boolean,
  ): Exception? = when {
    serviceRunning -> null
    requireService -> OnDeviceScreenStateNotReadyException(NOT_YET_BOUND)
    migrationMode -> null
    else -> OnDeviceScreenStateNotReadyException(ACCESSIBILITY_SERVICE_NOT_RUNNING)
  }

  override suspend fun capture(request: GetScreenStateRequest): OnDeviceCapturedScreenState {
    val useAccessibility = TrailblazeAccessibilityService.isServiceRunning()
    val migrationMode = InstrumentationArgUtil.shouldCaptureSecondaryTree()
    unboundServiceFailure(
      serviceRunning = useAccessibility,
      requireService = request.requireAndroidAccessibilityService,
      migrationMode = migrationMode,
    )?.let { throw it }
    Console.log("📱 AccessibilityScreenStateCaptor: Capturing screen state (accessibility=$useAccessibility, screenshot=${request.includeScreenshot}, scale=${request.screenshotMaxDimension1}x${request.screenshotMaxDimension2})")

    // Build scaling config from request parameters
    val scalingConfig = ScreenshotScalingConfig(
      maxDimension1 = request.screenshotMaxDimension1,
      maxDimension2 = request.screenshotMaxDimension2,
      imageFormat = request.screenshotImageFormat,
      compressionQuality = request.screenshotCompressionQuality,
    )

    // The accessibility driver's screen state provides a rich TrailblazeNode tree; the
    // UiAutomator branch is reached only in migration mode with the service unbound.
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
        captureSecondaryTree = migrationMode,
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
      if (migrationMode) {
        MigrationTreeCapture.captureOrNull()
      } else {
        null
      }

    return OnDeviceCapturedScreenState(screenState, driverMigrationTreeNode, capturedAtDeviceMs)
  }
}
