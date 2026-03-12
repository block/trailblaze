package xyz.block.trailblaze.mcp

import kotlinx.serialization.Serializable

/**
 * Strategy for how LLM API calls are made when Trailblaze acts as an agent.
 *
 * This determines the path through which LLM completions are obtained:
 * - DIRECT: Trailblaze -> LLM API (requires local LLM config)
 * - MCP_SAMPLING: Trailblaze -> MCP Client -> LLM API (uses MCP sampling protocol)
 */
@Serializable
enum class LlmCallStrategy {
  /**
   * Direct LLM calls from Trailblaze (recommended).
   *
   * Trailblaze calls the LLM API directly using locally configured credentials.
   * Uses Koog's native AIAgent with MCP tools via self-connection.
   *
   * Requires: Local LLM configured in Trailblaze settings (e.g., OpenAI API key).
   */
  DIRECT,

  /**
   * LLM calls via MCP sampling protocol.
   *
   * Uses the MCP sampling/createMessage protocol to request LLM completions
   * from the connected MCP client (e.g., Goose, Claude Desktop).
   *
   * Requires: MCP client that supports sampling (provides LLM completions).
   */
  MCP_SAMPLING,
}
