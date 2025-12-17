package xyz.block.trailblaze.mcp.android.ondevice.rpc.models

import kotlinx.serialization.Serializable
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.mcp.android.ondevice.rpc.RpcRequest

object DeviceStatus {
  /**
   * Request to get the current device status.
   * This is an empty object as no parameters are needed for this request.
   */
  @Serializable
  data class DeviceStatusRequest(
    val trailblazeDeviceId: TrailblazeDeviceId
  ) : RpcRequest<DeviceStatusResponse>


  @Serializable
  sealed interface DeviceStatusResponse {
    val isRunning: Boolean

    @Serializable
    object NoSession : DeviceStatusResponse {
      override val isRunning: Boolean = false
    }

    @Serializable
    data class HasSession(
      val sessionId: String,
      override val isRunning: Boolean,
    ) : DeviceStatusResponse
  }
}
