package xyz.block.trailblaze.host.rpc

import kotlinx.serialization.Serializable
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.mcp.android.ondevice.rpc.RpcRequest

/**
 * Drives a connected web device (Playwright) to navigate to [url]. Required for the web
 * `/devices` viewer to have any real utility — without it the browser opens to a blank
 * `about:blank` and there's no way to drive it.
 *
 * Failure modes (returned as [RpcResult.Failure] → HTTP 5xx):
 *  - No active connection for [trailblazeDeviceId].
 *  - The stream isn't a `WebDeviceScreenStream` (caller targeted a non-web device).
 *  - Playwright navigation threw (bad URL, timeout, etc.).
 */
@Serializable
data class NavigateWebUrlRequest(
  val trailblazeDeviceId: TrailblazeDeviceId,
  val url: String,
) : RpcRequest<NavigateWebUrlResponse>

@Serializable
data class NavigateWebUrlResponse(
  val success: Boolean,
)
