package xyz.block.trailblaze.llm

import kotlinx.serialization.Serializable
import xyz.block.trailblaze.mcp.AgentImplementation

/**
 * Runtime configuration for [AgentImplementation.TWO_TIER_AGENT].
 *
 * These settings control execution behavior when using the two-tier agent
 * architecture and are ignored for [AgentImplementation.TRAILBLAZE_RUNNER].
 *
 * Note: For model selection (inner/outer), see [xyz.block.trailblaze.agent.TwoTierAgentConfig].
 */
@Serializable
data class DirectAgentConfig(
  /**
   * Maximum agent iterations per objective.
   * Each iteration is one LLM call + tool execution cycle.
   */
  val maxIterationsPerObjective: Int = 10,

  /**
   * Whether to include screenshots in agent context.
   * Enables vision-based reasoning but increases token usage.
   */
  val includeScreenshots: Boolean = true,

  /**
   * Whether the connected MCP client supports sampling.
   *
   * When true, uses [xyz.block.trailblaze.mcp.LlmCallStrategy.MCP_SAMPLING] which
   * delegates LLM calls back to the MCP client - this is typically a better user
   * experience as it uses the client's configured LLM.
   *
   * When false, uses [xyz.block.trailblaze.mcp.LlmCallStrategy.DIRECT] which
   * makes LLM calls directly using Trailblaze's configured credentials.
   *
   * Defaults to true to prefer MCP sampling when available.
   */
  val mcpClientSupportsSampling: Boolean = true,
)
