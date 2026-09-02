package xyz.block.trailblaze.mcp.handlers

import io.ktor.util.encodeBase64
import kotlinx.coroutines.CancellationException
import xyz.block.trailblaze.api.ScreenState
import xyz.block.trailblaze.api.TrailblazeNode
import xyz.block.trailblaze.api.ViewHierarchyTreeNode
import xyz.block.trailblaze.mcp.RpcHandler
import xyz.block.trailblaze.mcp.android.ondevice.rpc.GetScreenStateRequest
import xyz.block.trailblaze.mcp.android.ondevice.rpc.GetScreenStateResponse
import xyz.block.trailblaze.mcp.android.ondevice.rpc.OnDeviceCapturedScreenState
import xyz.block.trailblaze.mcp.android.ondevice.rpc.OnDeviceScreenStateCaptor
import xyz.block.trailblaze.mcp.android.ondevice.rpc.OnDeviceScreenStateNotReadyException
import xyz.block.trailblaze.mcp.android.ondevice.rpc.RpcResult
import xyz.block.trailblaze.devices.TrailblazeDeviceClassifier
import xyz.block.trailblaze.util.Console

/**
 * RPC handler for getting the current screen state on-device.
 *
 * HOW a frame is captured is the driver's business — injected as [captor] (the accessibility
 * runner passes `AccessibilityScreenStateCaptor` from trailblaze-android; the in-process
 * ANDROID_TEST driver captures through its own instrumentation-side hierarchy). This handler owns
 * what is driver-independent: the wire shaping (base64 vs binary, which screenshot variants to
 * render) and the mapping of a captor throw onto the [RpcResult.Failure] the host's readiness
 * polling consumes — quietly for the expected [OnDeviceScreenStateNotReadyException], with a
 * logged stack trace for anything else.
 *
 * @param deviceClassifiers Device classifiers to include in the response so the host
 *   can learn the actual device type without a separate RPC call.
 */
class GetScreenStateRequestHandler(
  private val deviceClassifiers: List<TrailblazeDeviceClassifier> = emptyList(),
  private val captor: OnDeviceScreenStateCaptor,
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

  private suspend fun capture(request: GetScreenStateRequest): RpcResult<OnDeviceCapturedScreenState> {
    return try {
      RpcResult.Success(captor.capture(request))
    } catch (e: OnDeviceScreenStateNotReadyException) {
      // Not an error: the capture path is still coming up, and the host's `waitForReady` polls
      // this same request up to 120 times waiting for exactly that. Answer with the captor's
      // message alone — no stack trace to logcat, none on the wire — so a normal cold start
      // stays quiet and the host's retained failure string stays a single line.
      RpcResult.Failure(
        errorType = RpcResult.ErrorType.UNKNOWN_ERROR,
        message = e.message ?: "Screen state capture is not ready yet",
        details = null,
      )
    } catch (e: CancellationException) {
      throw e
    } catch (e: Throwable) {
      // A real capture failure. These are rare and worth the full trace on both surfaces.
      // Throwable, not Exception: a captor built on Espresso/Compose assertions fails with
      // AssertionError, which would otherwise escape past this handler into Ktor and answer the
      // host with a transport-level error instead of an RpcResult it can report.
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
     *
     * The [ScreenState]-derived tree fields ([ScreenState.viewHierarchy],
     * [ScreenState.trailblazeNodeTree], [ScreenState.pageContextSummary]) are likewise only read
     * when the request asked for a tree — the same belt-and-braces this function already applies
     * to `includeScreenshot`, which the captors also honor. The guarantee is about the WIRE, not
     * the device: every captor today builds its hierarchy eagerly, so this cannot save a walk,
     * only the serialization of a tree the caller said it did not want.
     *
     * [driverMigrationTreeNode] is deliberately NOT gated. It is a captor-supplied comparison
     * tree rather than a view of this [screenState], and the dual-tree migration path that reads
     * it asks for it explicitly.
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
        viewHierarchy = if (request.includeTree) screenState.viewHierarchy else ViewHierarchyTreeNode(),
        screenshotBase64 = screenshotBase64,
        annotatedScreenshotBase64 = annotatedScreenshotBase64,
        deviceWidth = screenState.deviceWidth,
        deviceHeight = screenState.deviceHeight,
        trailblazeNodeTree = if (request.includeTree) screenState.trailblazeNodeTree else null,
        driverMigrationTreeNode = driverMigrationTreeNode,
        pageContextSummary = if (request.includeTree) screenState.pageContextSummary else null,
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
        viewHierarchy = if (request.includeTree) screenState.viewHierarchy else ViewHierarchyTreeNode(),
        screenshotBase64 = null,
        annotatedScreenshotBase64 = null,
        deviceWidth = screenState.deviceWidth,
        deviceHeight = screenState.deviceHeight,
        trailblazeNodeTree = if (request.includeTree) screenState.trailblazeNodeTree else null,
        driverMigrationTreeNode = driverMigrationTreeNode,
        pageContextSummary = if (request.includeTree) screenState.pageContextSummary else null,
        deviceClassifiers = deviceClassifiers.map { it.classifier }.takeIf { it.isNotEmpty() },
        capturedAtDeviceMs = capturedAtDeviceMs,
      ).apply {
        this.screenshotBytes = screenshotBytes
        this.annotatedScreenshotBytes = annotatedScreenshotBytes
      }
    }
  }
}
