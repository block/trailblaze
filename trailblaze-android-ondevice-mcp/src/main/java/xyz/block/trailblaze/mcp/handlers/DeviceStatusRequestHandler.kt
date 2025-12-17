package xyz.block.trailblaze.mcp.handlers

import kotlinx.coroutines.Job
import xyz.block.trailblaze.logs.client.TrailblazeLogger
import xyz.block.trailblaze.mcp.RpcHandler
import xyz.block.trailblaze.mcp.android.ondevice.rpc.RpcResult
import xyz.block.trailblaze.mcp.android.ondevice.rpc.models.DeviceStatus.DeviceStatusRequest
import xyz.block.trailblaze.mcp.android.ondevice.rpc.models.DeviceStatus.DeviceStatusResponse

/**
 * Handler for device status requests.
 * Returns the current session status and whether a job is running.
 */
class DeviceStatusRequestHandler(
  private val trailblazeLogger: TrailblazeLogger,
  private val getCurrentJob: () -> Job?
) : RpcHandler<DeviceStatusRequest, DeviceStatusResponse> {

  override suspend fun handle(request: DeviceStatusRequest): RpcResult<DeviceStatusResponse> {
    return try {
      val sessionId = trailblazeLogger.getCurrentSessionId()
      val isRunning = getCurrentJob()?.isActive == true
      RpcResult.Success(DeviceStatusResponse.HasSession(sessionId, isRunning))
    } catch (e: Exception) {
      e.printStackTrace()
      // Return a safe default response when no session exists
      RpcResult.Success(DeviceStatusResponse.NoSession)
    }
  }
}
