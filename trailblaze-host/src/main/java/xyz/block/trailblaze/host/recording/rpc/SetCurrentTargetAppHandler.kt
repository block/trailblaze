package xyz.block.trailblaze.host.recording.rpc

import xyz.block.trailblaze.host.rpc.SetCurrentTargetAppRequest
import xyz.block.trailblaze.host.rpc.SetCurrentTargetAppResponse
import xyz.block.trailblaze.mcp.RpcHandler
import xyz.block.trailblaze.mcp.android.ondevice.rpc.RpcResult
import xyz.block.trailblaze.ui.TrailblazeDeviceManager

class SetCurrentTargetAppHandler(
  private val deviceManager: TrailblazeDeviceManager,
) : RpcHandler<SetCurrentTargetAppRequest, SetCurrentTargetAppResponse> {

  override suspend fun handle(
    request: SetCurrentTargetAppRequest,
  ): RpcResult<SetCurrentTargetAppResponse> {
    val target = deviceManager.availableAppTargets.firstOrNull { it.id == request.targetAppId }
      ?: return RpcResult.Failure(
        errorType = RpcResult.ErrorType.HTTP_ERROR,
        message = "Unknown target app id: ${request.targetAppId}",
      )
    deviceManager.settingsRepo.targetAppSelected(target)
    return RpcResult.Success(SetCurrentTargetAppResponse(success = true))
  }
}
