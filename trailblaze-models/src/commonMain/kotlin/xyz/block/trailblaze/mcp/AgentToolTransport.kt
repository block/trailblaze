package xyz.block.trailblaze.mcp

import kotlinx.serialization.Serializable

/**
 * Transport mode for internal agent tool execution.
 *
 * When Trailblaze acts as an agent (TRAILBLAZE_AS_AGENT mode), it needs to execute
 * primitive tools like tap(), swipe(), etc. This enum controls HOW those tools are executed.
 *
 * Both modes use the same MCP-compatible tool interface - the difference is whether
 * execution stays in-process or goes over HTTP.
 */
@Serializable
enum class AgentToolTransport {
  /**
   * In-process execution via DirectMcpToolExecutor.
   *
   * Tools are executed directly in the same process without network round-trips.
   * This is the fastest option and recommended for production use.
   *
   * The interface is MCP-compatible (same tool descriptors and JSON args), but
   * execution bypasses the HTTP layer entirely.
   */
  MCP_IN_PROCESS,

  /**
   * Full MCP protocol over HTTP (Streamable HTTP transport).
   *
   * The internal agent connects to Trailblaze's own MCP server via HTTP
   * (the recursive MCP pattern). The agent genuinely IS an MCP client -
   * it discovers tools via MCP protocol and executes them via HTTP calls.
   *
   * This provides architectural purity (same code path for internal and external
   * tool execution) but adds HTTP overhead.
   *
   * Uses Koog's McpToolRegistryProvider for tool discovery.
   */
  MCP_OVER_HTTP,
}
