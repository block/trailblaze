package xyz.block.trailblaze.host.recording.rpc

import io.ktor.util.encodeBase64
import xyz.block.trailblaze.host.rpc.GetHostDeviceScreenRequest
import xyz.block.trailblaze.host.rpc.GetHostDeviceScreenResponse
import xyz.block.trailblaze.mcp.RpcHandler
import xyz.block.trailblaze.mcp.android.ondevice.rpc.RpcResult

/**
 * Captures a single frame from the device's active [HostDeviceSessionManager] entry and
 * returns it as base64. The web client calls this repeatedly (typically every 200 ms) to
 * render the live view.
 *
 * Returns a null [screenshotBase64] on transient capture failures — the caller should retry
 * rather than disconnect, matching the desktop frame-loop's own resilience to dropped frames.
 */
class GetHostDeviceScreenHandler(
  private val sessionManager: HostDeviceSessionManager,
) : RpcHandler<GetHostDeviceScreenRequest, GetHostDeviceScreenResponse> {

  override suspend fun handle(
    request: GetHostDeviceScreenRequest,
  ): RpcResult<GetHostDeviceScreenResponse> {
    val stream = sessionManager.get(request.trailblazeDeviceId)
      ?: return RpcResult.Failure(
        errorType = RpcResult.ErrorType.HTTP_ERROR,
        message = "Device not connected: ${request.trailblazeDeviceId.toFullyQualifiedDeviceId()}. " +
          "Call ConnectToDeviceRequest first.",
      )

    // Two regimes, gated on `request.includeTree`:
    //  - **Mirror fast path** (the live `/devices` viewer's HTTP-fallback poll): tree-less.
    //    Android's [DeviceScreenStream.getMirrorScreenshot] override skips the
    //    accessibility-tree walk + JSON serialize, dropping per-frame cost from
    //    ~100-300 ms to ~30-60 ms.
    //  - **Recorder tap-time path**: caller needs the (screenshot, tree) pair captured in a
    //    single on-device call so the wasm-side selector generator
    //    ([TrailblazeNodeSelectorGenerator.resolveFromTap]) operates on a tree that's truly
    //    synchronous with the displayed screenshot. We go through [getScreenshot] (the
    //    atomic path) and pull the tree from the just-updated cache.
    val screenshotBytes: ByteArray?
    val trailblazeNodeTree: xyz.block.trailblaze.api.TrailblazeNode?
    if (request.includeTree) {
      screenshotBytes = stream.getScreenshot().takeIf { it.isNotEmpty() }
      // [getScreenshot] populated the stream's internal lastResponse cache atomically;
      // [getTrailblazeNodeTree] now returns the tree that paired with this screenshot.
      trailblazeNodeTree = stream.getTrailblazeNodeTree()
    } else {
      screenshotBytes = stream.getMirrorScreenshot().takeIf { it.isNotEmpty() }
      trailblazeNodeTree = null
    }
    return RpcResult.Success(
      GetHostDeviceScreenResponse(
        screenshotBase64 = screenshotBytes?.encodeBase64(),
        deviceWidth = stream.deviceWidth,
        deviceHeight = stream.deviceHeight,
        trailblazeNodeTree = trailblazeNodeTree,
      ),
    )
  }
}
