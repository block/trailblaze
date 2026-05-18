package xyz.block.trailblaze.host.rpc.ws

import kotlinx.serialization.Serializable
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.mcp.android.ondevice.rpc.RpcRequest

/**
 * Client → server. Asks the daemon to begin emitting [FrameEvent] pushes for
 * [trailblazeDeviceId] over the same WebSocket. The device must already be connected
 * via [xyz.block.trailblaze.host.rpc.ConnectToDeviceRequest].
 *
 * [intervalMs] hints at the producer cadence (the daemon may capture more slowly if a
 * frame takes longer than this). Defaults to 200 ms to match the HTTP-poll cadence the
 * web client used to drive.
 *
 * Only one subscription per device per socket is tracked. Sending [SubscribeFramesRequest]
 * a second time for the same device replaces the previous subscription with the new
 * [intervalMs]. The subscription is automatically torn down when the socket closes.
 */
@Serializable
data class SubscribeFramesRequest(
  val trailblazeDeviceId: TrailblazeDeviceId,
  val intervalMs: Long = 200,
) : RpcRequest<SubscribeFramesResponse>

@Serializable
data class SubscribeFramesResponse(
  /** Reflected back so the client can confirm which device is being streamed. */
  val trailblazeDeviceId: TrailblazeDeviceId,
  /** Initial frame dimensions (same values [xyz.block.trailblaze.host.rpc.GetHostDeviceScreenResponse] carries). */
  val deviceWidth: Int,
  val deviceHeight: Int,
)

/**
 * Client → server. Stops [FrameEvent] pushes for [trailblazeDeviceId] on this socket.
 * No-op if no subscription is active.
 */
@Serializable
data class UnsubscribeFramesRequest(
  val trailblazeDeviceId: TrailblazeDeviceId,
) : RpcRequest<UnsubscribeFramesResponse>

@Serializable
data class UnsubscribeFramesResponse(
  val trailblazeDeviceId: TrailblazeDeviceId,
)

/**
 * Server → client unsolicited event (carried by [RpcWsEnvelope.Event] with
 * `path = "/event/frame"`). Each event represents one freshly captured device frame.
 *
 * [screenshotBase64] is base64-encoded image bytes. On the Android path, the bytes are JPEG
 * (the daemon decodes the device's H.264 stream once and re-encodes as MJPEG via a long-lived
 * ffmpeg sidecar; one frame = one JPEG). On iOS and Playwright paths, the bytes are whatever
 * the per-platform screenshot RPC returns (currently PNG/WebP — see comments in
 * [xyz.block.trailblaze.host.recording.rpc.DeviceApiEndpoint.startFrameSubscription]). Browsers
 * sniff the format from the byte magic, so the wasm client renders identically regardless.
 */
@Serializable
data class FrameEvent(
  val trailblazeDeviceId: TrailblazeDeviceId,
  val screenshotBase64: String,
  val deviceWidth: Int,
  val deviceHeight: Int,
  /** Producer-side capture timestamp (epoch millis), useful for client-side dropped-frame metrics. */
  val capturedAtMillis: Long,
) {
  companion object {
    /** Stable path discriminator used in the [RpcWsEnvelope.Event.path] field. */
    const val EVENT_PATH = "/event/frame"
  }
}
