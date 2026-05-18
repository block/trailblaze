package xyz.block.trailblaze.host.recording.rpc

import xyz.block.trailblaze.host.rpc.GetTargetAppsRequest
import xyz.block.trailblaze.host.rpc.GetTargetAppsResponse
import xyz.block.trailblaze.host.rpc.TargetAppSummary
import xyz.block.trailblaze.mcp.RpcHandler
import xyz.block.trailblaze.mcp.android.ondevice.rpc.RpcResult
import xyz.block.trailblaze.ui.TrailblazeDeviceManager

class GetTargetAppsHandler(
  private val deviceManager: TrailblazeDeviceManager,
) : RpcHandler<GetTargetAppsRequest, GetTargetAppsResponse> {

  override suspend fun handle(
    request: GetTargetAppsRequest,
  ): RpcResult<GetTargetAppsResponse> {
    val summaries = deviceManager.availableAppTargets.map {
      TargetAppSummary(id = it.id, displayName = it.displayName)
    }
    val currentId = deviceManager.settingsRepo.getCurrentSelectedTargetApp()?.id
    return RpcResult.Success(
      GetTargetAppsResponse(
        targetApps = summaries,
        currentTargetAppId = currentId,
      ),
    )
  }
}
