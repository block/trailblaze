package xyz.block.trailblaze.host.rpc

import kotlinx.serialization.Serializable
import xyz.block.trailblaze.mcp.android.ondevice.rpc.RpcRequest

/**
 * Returns the list of [TargetAppSummary] entries known to the daemon plus the currently
 * selected one. Headless surfaces (the web `/devices` viewer) need this to populate the
 * target-app dropdown that the desktop UI reads directly from `availableAppTargets`.
 *
 * The summaries are minimal on purpose — only the fields the picker UI needs.
 * `TrailblazeHostAppTarget` itself isn't serializable (it's an abstract class with
 * platform-specific behavior).
 */
@Serializable
object GetTargetAppsRequest : RpcRequest<GetTargetAppsResponse>

@Serializable
data class GetTargetAppsResponse(
  val targetApps: List<TargetAppSummary>,
  val currentTargetAppId: String?,
)

@Serializable
data class TargetAppSummary(
  val id: String,
  val displayName: String,
)
