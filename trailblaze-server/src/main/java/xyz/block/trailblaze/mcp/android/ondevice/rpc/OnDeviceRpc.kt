package xyz.block.trailblaze.mcp.android.ondevice.rpc

import xyz.block.trailblaze.logs.client.TrailblazeJsonInstance
import xyz.block.trailblaze.mcp.android.ondevice.rpc.models.ListToolSets
import xyz.block.trailblaze.mcp.android.ondevice.rpc.models.McpPromptRequestData
import xyz.block.trailblaze.mcp.android.ondevice.rpc.models.SelectToolSet
import xyz.block.trailblaze.mcp.utils.HttpRequestUtils

/**
 * This is a pseudo-RPC client that communicates with the on-device server.
 */
class OnDeviceRpc(
  onDeviceAndroidServerPort: Int,
  private val sendProgressMessage: (String) -> Unit,
) {

  private val httpRequestUtils: HttpRequestUtils = HttpRequestUtils(
    "http://localhost:$onDeviceAndroidServerPort",
  )

  fun prompt(mcpPromptRequestData: McpPromptRequestData): String {
    val jsonInputString = TrailblazeJsonInstance.encodeToString(mcpPromptRequestData)
    return httpRequestUtils.postRequest(jsonInputString, McpPromptRequestData.URL_PATH)
  }

  fun listAvailableToolSets(): String = httpRequestUtils.postRequest("{}", ListToolSets.URL_PATH)

  fun setToolSets(trailblazeMode: SelectToolSet): String {
    val jsonInputString = TrailblazeJsonInstance.encodeToString(trailblazeMode)
    return httpRequestUtils.postRequest(jsonInputString, SelectToolSet.URL_PATH)
  }
}
