package xyz.block.trailblaze.mcp

import kotlinx.serialization.Serializable

/**
 * Agent implementation to use for UI automation.
 *
 * This controls which architecture handles the agent loop:
 * - [TRAILBLAZE_RUNNER]: Legacy YAML-based implementation retained for explicit selection
 * - [MULTI_AGENT_V3]: Modern multi-agent architecture with inner/outer agent separation
 * - [KOOG_STRATEGY_GRAPH]: Default Koog strategy-graph implementation
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
   * This legacy option remains available for explicit selection and backward compatibility.
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

  /**
   * Single Koog `strategy { }` graph that owns the agent reasoning loop — orchestration,
   * tool dispatch, and (over time) replanning / recovery / history compression — with
   * Trailblaze owning the domain (screen state, drivers, deterministic replay).
   *
   * This is the default agent. [TRAILBLAZE_RUNNER] remains available for explicit selection via
   * the CLI, the `trailblaze.agent` instrumentation arg, desktop run requests, and MCP requests.
   *
   * Intended successor to [MULTI_AGENT_V3] — add opt-in, prove via eval, then collapse the
   * hand-rolled loops onto this one.
   */
  KOOG_STRATEGY_GRAPH,
  ;

  companion object {
    /** Name of the default agent, usable in annotation parameters that require a const. */
    const val DEFAULT_NAME = "KOOG_STRATEGY_GRAPH"

    /** Global default agent implementation. Change this to switch the default everywhere. */
    val DEFAULT = KOOG_STRATEGY_GRAPH
  }
}
