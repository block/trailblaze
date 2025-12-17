package xyz.block.trailblaze.mcp.android.ondevice.rpc.models

import kotlinx.serialization.Serializable
import xyz.block.trailblaze.mcp.android.ondevice.rpc.RpcRequest

/**
 * Used to send a prompt to the MCP server.
 * Returns a string response from the MCP server.
 */
@Serializable
data class McpPromptRequestData(
  val fullPrompt: String,
  val steps: List<String>,
) : RpcRequest<String>