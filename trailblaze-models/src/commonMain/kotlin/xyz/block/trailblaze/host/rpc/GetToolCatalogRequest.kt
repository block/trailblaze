package xyz.block.trailblaze.host.rpc

import kotlinx.serialization.Serializable
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.mcp.android.ondevice.rpc.RpcRequest
import xyz.block.trailblaze.toolcalls.TrailblazeToolDescriptor

/**
 * Returns the set of [TrailblazeToolDescriptor] available for the given driver, scoped to the
 * daemon's currently-selected target app. The daemon delegates to
 * `discoverAvailableTools(deviceManager, driverType)` — same source the desktop recording tab's
 * Tool Palette uses, so the web viewer's palette stays in sync with the desktop's by construction.
 *
 * Caller passes the driver type from the connected device summary; the target app is read from
 * settings on the server side (set via [SetCurrentTargetAppRequest]).
 */
@Serializable
data class GetToolCatalogRequest(
  val trailblazeDriverType: TrailblazeDriverType,
) : RpcRequest<GetToolCatalogResponse>

@Serializable
data class GetToolCatalogResponse(
  val tools: List<TrailblazeToolDescriptor>,
)
