package xyz.block.trailblaze.host.recording.rpc

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
 * from the request.
 */
class GetToolCatalogHandler(
  private val deviceManager: TrailblazeDeviceManager,
) : RpcHandler<GetToolCatalogRequest, GetToolCatalogResponse> {

  override suspend fun handle(
    request: GetToolCatalogRequest,
  ): RpcResult<GetToolCatalogResponse> {
    val tools = discoverAvailableTools(deviceManager, request.trailblazeDriverType)
    return RpcResult.Success(GetToolCatalogResponse(tools = tools))
  }
}
