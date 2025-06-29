package xyz.block.trailblaze.mcp.android.ondevice.rpc.models

import kotlinx.serialization.Serializable

/**
 * Used to send a prompt to the MCP server.
 */
@Serializable
data class SelectToolSet(
  val toolSetNames: List<String>,
) {
  companion object {
    const val URL_PATH = "/select-toolsets"
  }
}
