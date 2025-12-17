package xyz.block.trailblaze.mcp.android.ondevice.rpc.models

import kotlinx.serialization.Serializable
import xyz.block.trailblaze.mcp.android.ondevice.rpc.RpcRequest

/**
 * Request to select/configure which tool sets should be active.
 * Returns a confirmation string.
 */
@Serializable
data class SelectToolSet(
  val toolSetNames: List<String>,
) : RpcRequest<String>
