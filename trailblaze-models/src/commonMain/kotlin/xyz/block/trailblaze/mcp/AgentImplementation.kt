package xyz.block.trailblaze.mcp

import kotlinx.serialization.Serializable

/**
 * Agent implementation to use for UI automation.
 *
 * This controls which architecture handles the agent loop:
 * - [TRAILBLAZE_RUNNER]: Legacy YAML-based implementation (stable, well-tested)
 * - [TWO_TIER_AGENT]: Modern two-tier architecture with separate inner/outer agents
 *
 * ## Two-Tier Architecture
 *
 * The two-tier architecture separates concerns:
 * - **Inner Agent**: Cheap vision model for screen analysis (ScreenAnalyzerImpl)
 * - **Outer Agent**: Expensive reasoning model for planning and decision-making
 *
 * Who plays the outer agent depends on the [TrailblazeMcpMode]:
 * - [TrailblazeMcpMode.TRAILBLAZE_AS_AGENT]: Trailblaze's KoogStrategistAgent is outer
 * - [TrailblazeMcpMode.MCP_CLIENT_AS_AGENT]: MCP client (Goose, etc.) is outer,
 *   calling getNextActionRecommendation() and executeUiAction() tools
 *
 * @see TrailblazeMcpMode
 */
@Serializable
enum class AgentImplementation {
  /**
   * TrailblazeRunner via YAML execution (legacy).
   *
   * Uses the original TrailblazeRunner.kt implementation which:
   * - Converts prompts to YAML format
   * - Has rich tool support via TrailblazeToolRepo
   * - Produces detailed logs (TrailblazeLlmRequestLog, TrailblazeToolLog)
   * - Is battle-tested in production
   *
   * This is the stable option for backward compatibility.
   */
  TRAILBLAZE_RUNNER,

  /**
   * Two-tier agent architecture (recommended).
   *
   * Uses separate inner and outer agents for optimal cost and performance:
   * - **Inner agent** (ScreenAnalyzerImpl): Cheap vision model analyzes screens
   * - **Outer agent**: Expensive reasoning model makes decisions
   *
   * The outer agent can be either:
   * - Trailblaze's KoogStrategistAgent (when using runPrompt)
   * - An external MCP client (when client calls getNextActionRecommendation/executeUiAction)
   *
   * Benefits:
   * - Cost optimization: cheap model handles repetitive screen analysis
   * - Flexibility: works with Trailblaze or external clients as outer agent
   * - Better observability: clear separation of analysis vs. decision-making
   */
  TWO_TIER_AGENT,

  /**
   * Multi-agent architecture inspired by Mobile-Agent-v3.
   *
   * Uses Koog's planner infrastructure with two modes:
   * - **trail()**: Execute predefined steps from .trail.yaml files
   * - **blaze()**: Explore and discover steps via screen analysis
   *
   * Key features:
   * - Goal-oriented action planning for both modes
   * - Zero LLM calls for fully recorded trails (deterministic)
   * - Recording generation from blaze exploration
   * - Optional reflection, progress tracking, and memory nodes
   *
   * @see https://arxiv.org/abs/2508.15144
   */
  MULTI_AGENT_V3,
  ;

  companion object {
    /** Name of the default agent, usable in annotation parameters that require a const. */
    const val DEFAULT_NAME = "TRAILBLAZE_RUNNER"

    /** Global default agent implementation. Change this to switch the default everywhere. */
    val DEFAULT = TRAILBLAZE_RUNNER
  }
}
