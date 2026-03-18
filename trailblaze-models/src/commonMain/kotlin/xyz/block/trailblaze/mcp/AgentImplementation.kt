package xyz.block.trailblaze.mcp

import kotlinx.serialization.Serializable

/**
 * Agent implementation to use for UI automation.
 *
 * This controls which architecture handles the agent loop:
 * - [TRAILBLAZE_RUNNER]: Legacy YAML-based implementation (stable, well-tested)
 * - [MULTI_AGENT_V3]: Modern multi-agent architecture with inner/outer agent separation
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
