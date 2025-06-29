package xyz.block.trailblaze.mcp.android.ondevice.rpc.models

import kotlinx.serialization.Serializable

/**
 * Used to send a prompt to the MCP server.
 */
@Serializable
data class ListToolSets(
  val toolSets: List<ToolSetInfo>,
) {
  companion object {
    const val URL_PATH = "/list-toolsets"
  }

  @Serializable
  data class ToolSetInfo(
    val name: String,
  )
}
