package xyz.block.trailblaze.host.recording.rpc

import xyz.block.trailblaze.devices.TrailblazeDeviceClassifier
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.host.devices.HostProbedDeviceClassifiers
import xyz.block.trailblaze.host.recording.discoverAvailableTools
import xyz.block.trailblaze.host.rpc.GetToolCatalogRequest
import xyz.block.trailblaze.host.rpc.GetToolCatalogResponse
import xyz.block.trailblaze.mcp.RpcHandler
import xyz.block.trailblaze.mcp.android.ondevice.rpc.RpcResult
import xyz.block.trailblaze.ui.TrailblazeDeviceManager

/**
 * Delegates to [discoverAvailableTools] — same source the desktop recording tab's Tool Palette
 * uses, so the web viewer's palette stays in sync by construction. The target app is read from
 * the daemon's current settings (set via [SetCurrentTargetAppRequest]); the driver type comes
 * from the request. A request naming its device is classified, so the palette lists only the
 * `trailblaze/requiredClassifiers` tools that device can load.
 */
class GetToolCatalogHandler(
  private val deviceManager: TrailblazeDeviceManager,
  private val classifyDevice: suspend (TrailblazeDeviceId) -> List<TrailblazeDeviceClassifier> = {
    HostProbedDeviceClassifiers.forDevice(it)
  },
) : RpcHandler<GetToolCatalogRequest, GetToolCatalogResponse> {

  override suspend fun handle(
    request: GetToolCatalogRequest,
  ): RpcResult<GetToolCatalogResponse> {
    val classifiers = request.trailblazeDeviceId?.let { classifyDevice(it) }
    val tools = discoverAvailableTools(deviceManager, request.trailblazeDriverType, classifiers)
    return RpcResult.Success(GetToolCatalogResponse(tools = tools))
  }
}
