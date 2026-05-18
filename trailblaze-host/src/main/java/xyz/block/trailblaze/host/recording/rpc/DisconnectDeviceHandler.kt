package xyz.block.trailblaze.host.recording.rpc

import xyz.block.trailblaze.host.rpc.DisconnectDeviceRequest
import xyz.block.trailblaze.host.rpc.DisconnectDeviceResponse
import xyz.block.trailblaze.mcp.RpcHandler
import xyz.block.trailblaze.mcp.android.ondevice.rpc.RpcResult

/**
 * Removes the device's entry from [HostDeviceSessionManager] and closes the underlying
 * stream if it implements [AutoCloseable]. No-op if the device was not connected.
 */
class DisconnectDeviceHandler(
  private val sessionManager: HostDeviceSessionManager,
) : RpcHandler<DisconnectDeviceRequest, DisconnectDeviceResponse> {

  override suspend fun handle(
    request: DisconnectDeviceRequest,
  ): RpcResult<DisconnectDeviceResponse> {
    sessionManager.remove(request.trailblazeDeviceId)
    return RpcResult.Success(DisconnectDeviceResponse(success = true))
  }
}
