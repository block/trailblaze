package xyz.block.trailblaze.mcp.android.ondevice.rpc.models

import kotlinx.serialization.Serializable

/**
 * Used to send a prompt to the MCP server.
 */
@Serializable
data class McpPromptRequestData(
  val fullPrompt: String,
  val steps: List<String>,
) {
  companion object {
    const val URL_PATH = "/prompt"
  }
}
