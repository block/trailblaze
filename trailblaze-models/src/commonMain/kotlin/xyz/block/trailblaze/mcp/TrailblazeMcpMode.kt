package xyz.block.trailblaze.mcp

import kotlinx.serialization.Serializable

/**
 * Operating modes for the Trailblaze MCP server.
 *
 * The mode determines "who is the agent" - who does the LLM reasoning:
 * - [MCP_CLIENT_AS_AGENT]: The MCP client acts as the agent (recommended default)
 * - [TRAILBLAZE_AS_AGENT]: Trailblaze acts as the agent
 *
 * Note: There is no separate "runner" mode. Deterministic trail execution is handled
 * by TRAILBLAZE_AS_AGENT mode - if a trail has recordings, it runs without LLM calls.
 * The trail content determines behavior, not the mode.
 */
@Serializable
enum class TrailblazeMcpMode {
  /**
   * MCP Client as Agent mode (recommended default).
   *
   * The MCP client (e.g., Claude, Firebender, Cursor) acts as the agent -
   * it handles all LLM reasoning and decides what actions to take.
   * Trailblaze exposes low-level device primitives (tap, swipe, type) and
   * screen state queries. No Trailblaze LLM configuration required.
   *
   * Tools available: Device primitives, screen state queries, trail management, session config
   */
  MCP_CLIENT_AS_AGENT,

  /**
   * Trailblaze as Agent mode.
   *
   * Trailblaze acts as the agent - it handles all LLM reasoning internally.
   * Client sends natural language prompts via runPrompt; Trailblaze reasons
   * and executes using its configured LLM. Also supports runTrail for
   * deterministic trail execution (recorded trails run without LLM calls).
   *
   * Tools available: runPrompt, runTrail, session config
   */
  TRAILBLAZE_AS_AGENT,
}
