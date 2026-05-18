package xyz.block.trailblaze.host.rpc

import kotlinx.serialization.Serializable
import xyz.block.trailblaze.api.TrailblazeNode
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.mcp.android.ondevice.rpc.RpcRequest

/**
 * Polls a single frame from the daemon's active connection for [trailblazeDeviceId].
 *
 * The device must have been connected via [ConnectToDeviceRequest] first. Each call
 * captures a fresh screenshot; the web client is expected to call this repeatedly
 * (e.g. every 200 ms) to render the live view. Returns null [screenshotBase64] when
 * a frame capture fails transiently — the client should retry rather than disconnect.
 *
 * Set [includeTree] to true to ask the daemon to also include the current accessibility
 * tree atomically alongside the screenshot. The mirror frame loop leaves this `false` so
 * the live viewer stays fast; the recorder's tap-time path sets it `true` so selector
 * generation operates on a (screenshot, tree) pair captured in a single on-device call.
 */
@Serializable
data class GetHostDeviceScreenRequest(
  val trailblazeDeviceId: TrailblazeDeviceId,
  /**
   * When true, the response includes [GetHostDeviceScreenResponse.trailblazeNodeTree]
   * captured atomically with the screenshot. Default `false` keeps the mirror loop cheap.
   */
  val includeTree: Boolean = false,
) : RpcRequest<GetHostDeviceScreenResponse>

@Serializable
data class GetHostDeviceScreenResponse(
  /** Base64-encoded screenshot bytes, or null on transient capture failure. */
  val screenshotBase64: String?,
  val deviceWidth: Int,
  val deviceHeight: Int,
  /**
   * Rich driver-typed accessibility tree, captured atomically with [screenshotBase64].
   * Null when the caller passed `includeTree = false` (mirror loop) or when the driver
   * doesn't expose one (Compose desktop today). The wasm recorder feeds this into
   * [xyz.block.trailblaze.api.TrailblazeNodeSelectorGenerator.resolveFromTap] at tap-time
   * to convert a tap coordinate into a stable `tapOnElementBySelector`.
   */
  val trailblazeNodeTree: TrailblazeNode? = null,
)
