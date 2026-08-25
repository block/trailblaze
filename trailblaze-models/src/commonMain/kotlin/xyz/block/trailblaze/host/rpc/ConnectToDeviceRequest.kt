package xyz.block.trailblaze.host.rpc

import kotlinx.serialization.Serializable
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.mcp.android.ondevice.rpc.RpcRequest

/**
 * Requests the daemon to open a live connection to the given device and hold it for
 * subsequent [GetHostDeviceScreenRequest] / [DeviceInteractionRequest] calls.
 *
 * Connection establishment mirrors the desktop recording tab: Android installs/reuses
 * the on-device instrumentation runner, iOS bootstraps Maestro, Web launches or reuses
 * a headless Playwright browser. Idempotent — reconnecting to an already-connected
 * device returns the existing dimensions immediately.
 *
 * Connect failures bubble up through HTTP 5xx + `RpcErrorResponse`, matching the
 * other endpoints in this module — no in-band error variant is needed in the response.
 */
@Serializable
data class ConnectToDeviceRequest(
  val trailblazeDeviceId: TrailblazeDeviceId,
  /**
   * The app target this connection is for, when the caller knows it. The Android connect
   * installs that target's instrumentation runner and the web connect loads its custom tools,
   * so a caller running a trail against a declared target has to be able to say which one -
   * omitting it binds whatever target the daemon has selected, which is the wrong app whenever
   * the two disagree. An id this daemon doesn't have registered is a connect failure, never a
   * silent fall back to the selected target.
   */
  val targetAppId: String? = null,
) : RpcRequest<ConnectToDeviceResponse>

@Serializable
data class ConnectToDeviceResponse(
  val deviceWidth: Int,
  val deviceHeight: Int,
)
