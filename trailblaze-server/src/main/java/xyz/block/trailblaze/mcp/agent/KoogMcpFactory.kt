package xyz.block.trailblaze.mcp.agent

import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.mcp.McpToolRegistryProvider
import io.modelcontextprotocol.kotlin.sdk.client.Client
import xyz.block.trailblaze.devices.TrailblazeDevicePort

/**
 * Factory for creating Koog MCP tool registries.
 *
 * Uses Koog's [McpToolRegistryProvider] to connect to MCP servers and discover tools.
 * The returned [ToolRegistry] can be used with [KoogNativeMcpAgent].
 *
 * ## Self-Connection Pattern
 *
 * For recursive MCP, connect to Trailblaze's own server:
 * ```kotlin
 * val toolRegistry = KoogMcpFactory.createMcpToolRegistry("http://localhost:52525/mcp")
 * // Use toolRegistry with a Koog AIAgent
 * ```
 *
 * The agent will discover tools (tap, swipe, etc.) via MCP protocol.
 */
object KoogMcpFactory {

  /** Default MCP server URL for self-connection */
  const val DEFAULT_SELF_CONNECTION_URL = TrailblazeDevicePort.DEFAULT_MCP_URL

  /** Default client name for MCP connection */
  const val DEFAULT_CLIENT_NAME = "trailblaze-agent"

  /** Default client version */
  const val DEFAULT_CLIENT_VERSION = "1.0.0"

  /**
   * Creates a Koog [ToolRegistry] by connecting to an MCP server.
   *
   * This uses Koog's native MCP client support to discover tools from the server.
   * The returned registry can be used with a Koog AIAgent.
   *
   * ## Self-Connection Pattern
   *
   * For recursive MCP, connect to Trailblaze's own server:
   * ```kotlin
   * val toolRegistry = createMcpToolRegistry("http://localhost:52525/mcp")
   * ```
   *
   * The agent will discover tools (tap, swipe, etc.) via MCP protocol.
   *
   * @param mcpServerUrl URL of the MCP server
   * @param clientName Name to identify this client
   * @param clientVersion Version string
   * @return ToolRegistry with tools from the MCP server
   */
  suspend fun createMcpToolRegistry(
    mcpServerUrl: String = DEFAULT_SELF_CONNECTION_URL,
    clientName: String = DEFAULT_CLIENT_NAME,
    clientVersion: String = DEFAULT_CLIENT_VERSION,
  ): ToolRegistry {
    // Create SSE transport to the MCP server
    val transport = McpToolRegistryProvider.defaultSseTransport(mcpServerUrl)

    // Create and return tool registry - Koog discovers tools via MCP protocol
    return McpToolRegistryProvider.fromTransport(
      transport = transport,
      name = clientName,
      version = clientVersion,
    )
  }

  /**
   * Creates a Koog [ToolRegistry] from an existing MCP client.
   *
   * Use this when you already have an MCP client connection.
   *
   * @param mcpClient Existing MCP client
   * @return ToolRegistry with tools from the MCP server
   */
  suspend fun createMcpToolRegistryFromClient(
    mcpClient: Client,
  ): ToolRegistry {
    return McpToolRegistryProvider.fromClient(mcpClient)
  }
}
