package xyz.block.trailblaze.host.rpc

import kotlinx.serialization.Serializable
import xyz.block.trailblaze.devices.TrailblazeDeviceId
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
 *
 * [trailblazeDeviceId] is the device the palette is for. With it, scripted tools gated by
 * `trailblaze/requiredClassifiers` are listed only when that device's classifiers satisfy them;
 * without it, the catalog has no device to judge and lists them all.
 */
@Serializable
data class GetToolCatalogRequest(
  val trailblazeDriverType: TrailblazeDriverType,
  val trailblazeDeviceId: TrailblazeDeviceId? = null,
) : RpcRequest<GetToolCatalogResponse> {
  /**
   * The constructor and [copy] from before [trailblazeDeviceId], kept for callers compiled against
   * them. Written out explicitly rather than via `@JvmOverloads`, which commonMain can't use on
   * wasmJs.
   */
  constructor(trailblazeDriverType: TrailblazeDriverType) : this(trailblazeDriverType, trailblazeDeviceId = null)

  @Deprecated("Binary compatibility", level = DeprecationLevel.HIDDEN)
  fun copy(trailblazeDriverType: TrailblazeDriverType = this.trailblazeDriverType): GetToolCatalogRequest =
    GetToolCatalogRequest(trailblazeDriverType, trailblazeDeviceId)
}

@Serializable
data class GetToolCatalogResponse(
  val tools: List<TrailblazeToolDescriptor>,
)
