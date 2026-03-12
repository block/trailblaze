package xyz.block.trailblaze.agent

import kotlinx.serialization.Serializable
import xyz.block.trailblaze.llm.TrailblazeLlmModel

/**
 * Identifies which tier of the two-tier agent architecture a component belongs to.
 *
 * The two-tier architecture separates concerns:
 * - **INNER**: Fast, cheap vision model for screen analysis (e.g., GPT-4o-mini)
 * - **OUTER**: Expensive reasoning model for planning/coordination (e.g., GPT-4o, Claude Sonnet)
 *
 * This separation allows cost optimization by using cheaper models for repetitive
 * screen analysis tasks while reserving expensive models for high-level reasoning.
 *
 * @see TwoTierAgentConfig
 */
@Serializable
enum class AgentTier {
  /**
   * Inner agent tier - screen analysis.
   *
   * Uses cheap, fast vision models for:
   * - Analyzing current screen state
   * - Recommending next UI action
   * - Detecting progress and blockers
   *
   * Typical models: GPT-4o-mini, Claude Haiku, Gemini Flash
   */
  INNER,

  /**
   * Outer agent tier - planning and reasoning.
   *
   * Uses expensive, capable models for:
   * - High-level objective planning
   * - Decision making on inner agent recommendations
   * - Error recovery and alternative approaches
   *
   * Typical models: GPT-4o, Claude Sonnet, o1-mini
   */
  OUTER,
}

/**
 * Configuration for the two-tier agent architecture.
 *
 * This config specifies which LLM models to use for each tier and whether
 * the two-tier mode is enabled. When disabled, the system falls back to
 * the single-agent DirectMcpAgent pattern.
 *
 * ## Example Usage
 *
 * ```kotlin
 * val config = TwoTierAgentConfig(
 *   innerModel = TrailblazeLlmModels.GPT_4O_MINI,
 *   outerModel = TrailblazeLlmModels.GPT_4O,
 *   enabled = true,
 * )
 * ```
 *
 * @property innerModel The LLM model for the inner agent (screen analysis)
 * @property outerModel The LLM model for the outer agent (planning/reasoning)
 * @property enabled Whether two-tier mode is enabled (default: false for gradual rollout)
 *
 * @see AgentTier
 */
@Serializable
data class TwoTierAgentConfig(
  /**
   * LLM model for the inner agent (screen analysis).
   *
   * Should be a cheap, vision-capable model. The inner agent makes many
   * calls per objective, so cost is a primary concern.
   */
  val innerModel: TrailblazeLlmModel,

  /**
   * LLM model for the outer agent (planning/reasoning).
   *
   * Can be a more expensive, capable model. The outer agent makes fewer
   * calls and handles complex decision-making.
   */
  val outerModel: TrailblazeLlmModel,

  /**
   * Whether two-tier agent mode is enabled.
   *
   * When false, the system uses the legacy single-agent pattern.
   * Default is false for safe rollout - enable explicitly when ready.
   */
  val enabled: Boolean = false,
)
