package xyz.block.trailblaze.mcp.android.ondevice.rpc

import kotlinx.serialization.Serializable

/**
 * RPC request to block until the on-device accessibility service is connected.
 *
 * The host enables the accessibility service in ADB settings, then sends this request
 * to the on-device server. The server blocks using the reliable in-process check
 * ([TrailblazeAccessibilityService.isServiceRunning]) until the service is bound,
 * avoiding host-side `dumpsys accessibility` parsing which is unreliable on API 35+.
 */
@Serializable
data class EnsureAccessibilityReadyRequest(
  val timeoutMs: Long = 15_000,
) : RpcRequest<EnsureAccessibilityReadyResponse>

@Serializable
data class EnsureAccessibilityReadyResponse(
  val ready: Boolean,
)
