package xyz.block.trailblaze.mcp

import kotlinx.serialization.Serializable

/**
 * Identifies who is running the outer loop in the two-tier agent architecture.
 *
 * The outer loop is responsible for:
 * - Deciding what objective to pursue
 * - Interpreting inner loop analysis results
 * - Deciding when to retry, give up, or declare success
 *
 * @see LlmCallStrategy For how the inner loop's LLM calls are made
 */
@Serializable
enum class OuterLoopAgent {
  /**
   * An external MCP client (Goose, Claude Desktop, etc.) is the outer loop.
   *
   * The MCP client calls getNextActionRecommendation() and executeUiAction() tools,
   * making its own decisions about what to do next.
   */
  MCP_CLIENT,

  /**
   * Trailblaze's OuterLoopAgent (Koog-based) is the outer loop.
   *
   * Used when MCP client calls runPrompt() and Trailblaze handles the
   * full objective autonomously using OuterLoopAgent + InnerLoopScreenAnalyzer.
   */
  TRAILBLAZE_KOOG_AGENT,
}
