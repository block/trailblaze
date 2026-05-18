package xyz.block.trailblaze.host.rpc

import kotlinx.serialization.Serializable
import xyz.block.trailblaze.mcp.android.ondevice.rpc.RpcRequest

/**
 * Updates the daemon's "currently selected target app" — the same value the desktop UI's
 * Target dropdown writes via `settingsRepo.targetAppSelected(...)`. This unblocks the
 * Android/iOS connect path in headless mode, which otherwise fails with
 * `"No target app selected. Pick one in the Target dropdown before connecting."`.
 *
 * Failure modes (returned as [RpcResult.Failure] → HTTP 5xx):
 *  - `targetAppId` doesn't match any known app target.
 */
@Serializable
data class SetCurrentTargetAppRequest(
  val targetAppId: String,
) : RpcRequest<SetCurrentTargetAppResponse>

@Serializable
data class SetCurrentTargetAppResponse(
  val success: Boolean,
)
