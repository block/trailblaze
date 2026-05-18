package xyz.block.trailblaze.host.rpc

import kotlinx.serialization.Serializable
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.mcp.android.ondevice.rpc.RpcRequest

/**
 * Runs an arbitrary trail YAML against the connected device. The recording UI's Tool Palette
 * uses this for the "Run on Device" button — same code path that the desktop tab dispatches
 * through `deviceManager.runYaml`. Server-side resolves a session via the device's
 * `getOrCreateSessionResolution`, the trail runs to completion, and we return a boolean.
 *
 * Failure modes (returned as [RpcResult.Failure] → HTTP 5xx):
 *  - No active connection for [trailblazeDeviceId].
 *  - YAML parse failure or trail execution exception (message reflects the cause).
 */
@Serializable
data class RunTrailYamlRequest(
  val trailblazeDeviceId: TrailblazeDeviceId,
  val yaml: String,
) : RpcRequest<RunTrailYamlResponse>

@Serializable
data class RunTrailYamlResponse(
  val success: Boolean,
)
