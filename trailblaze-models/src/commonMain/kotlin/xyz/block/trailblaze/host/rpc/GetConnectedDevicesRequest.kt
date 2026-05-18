package xyz.block.trailblaze.host.rpc

import kotlinx.serialization.Serializable
import xyz.block.trailblaze.devices.TrailblazeConnectedDeviceSummary
import xyz.block.trailblaze.mcp.android.ondevice.rpc.RpcRequest

/**
 * Lists all devices currently visible to the Trailblaze daemon — same filter set as
 * `trailblaze device list` and the desktop recording tab dropdown.
 *
 * No request parameters: the server applies its own filtering rules (drop Revyl cloud
 * devices, drop hidden platforms) and always synthesizes the Playwright-native placeholder
 * so the client doesn't need to know about driver-type specifics.
 */
@Serializable
object GetConnectedDevicesRequest : RpcRequest<GetConnectedDevicesResponse>

@Serializable
data class GetConnectedDevicesResponse(
  val devices: List<TrailblazeConnectedDeviceSummary>,
)
