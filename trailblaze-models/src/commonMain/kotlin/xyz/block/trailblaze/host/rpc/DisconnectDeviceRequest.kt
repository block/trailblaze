package xyz.block.trailblaze.host.rpc

import kotlinx.serialization.Serializable
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.mcp.android.ondevice.rpc.RpcRequest

/**
 * Closes the daemon's active connection for [trailblazeDeviceId] and releases any
 * associated resources (RPC client, Playwright page, Maestro driver handle).
 * No-op if the device is not currently connected.
 */
@Serializable
data class DisconnectDeviceRequest(
  val trailblazeDeviceId: TrailblazeDeviceId,
) : RpcRequest<DisconnectDeviceResponse>

@Serializable
data class DisconnectDeviceResponse(
  val success: Boolean,
)
