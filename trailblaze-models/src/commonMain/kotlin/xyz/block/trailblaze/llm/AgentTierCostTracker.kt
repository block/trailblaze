package xyz.block.trailblaze.llm

import kotlinx.serialization.Serializable
import xyz.block.trailblaze.agent.AgentTier
import xyz.block.trailblaze.agent.TokenUsage
import kotlin.math.round
import xyz.block.trailblaze.util.Console

/**
 * Tracks LLM costs separately for inner and outer agent tiers.
 *
 * This enables cost analysis and optimization of the two-tier agent architecture
 * by providing visibility into how costs are distributed between:
 * - **Inner agent**: High-volume, cheap screen analysis calls
 * - **Outer agent**: Lower-volume, expensive planning/reasoning calls
 *
 * ## Thread Safety
 *
 * This class is NOT thread-safe. For concurrent access, use external synchronization.
 *
 * ## Usage Example
 *
 * ```kotlin
 * val tracker = AgentTierCostTracker()
 *
 * // Record inner agent call
 * tracker.recordCall(
 *   tier = AgentTier.INNER,
 *   tokenUsage = TokenUsage(inputTokens = 1000, outputTokens = 200),
 *   model = TrailblazeLlmModels.GPT_4O_MINI,
 * )
 *
 * // Record outer agent call
 * tracker.recordCall(
 *   tier = AgentTier.OUTER,
 *   tokenUsage = TokenUsage(inputTokens = 500, outputTokens = 150),
 *   model = TrailblazeLlmModels.GPT_4O,
 * )
 *
 * // Get cost breakdown
 * val breakdown = tracker.getCostBreakdown()
 * Console.log("Inner: $${breakdown.innerAgentCost}, Outer: $${breakdown.outerAgentCost}")
 * ```
 *
 * @see TierCostBreakdown
 * @see AgentTier
 */
class AgentTierCostTracker {

  private var innerAgentInputTokens: Long = 0
  private var innerAgentOutputTokens: Long = 0
  private var innerAgentCost: Double = 0.0
  private var innerAgentCallCount: Int = 0

  private var outerAgentInputTokens: Long = 0
  private var outerAgentOutputTokens: Long = 0
  private var outerAgentCost: Double = 0.0
  private var outerAgentCallCount: Int = 0

  /**
   * Records an LLM call for the specified tier.
   *
   * Calculates cost based on the model's pricing and updates running totals.
   *
   * @param tier Which agent tier made the call
   * @param tokenUsage Token counts from the LLM response
   * @param model The model used (for pricing information)
   */
  fun recordCall(
    tier: AgentTier,
    tokenUsage: TokenUsage,
    model: TrailblazeLlmModel,
  ) {
    val inputCost = (tokenUsage.inputTokens / 1_000_000.0) * model.inputCostPerOneMillionTokens
    val outputCost = (tokenUsage.outputTokens / 1_000_000.0) * model.outputCostPerOneMillionTokens
    val totalCost = inputCost + outputCost

    when (tier) {
      AgentTier.INNER -> {
        innerAgentInputTokens += tokenUsage.inputTokens
        innerAgentOutputTokens += tokenUsage.outputTokens
        innerAgentCost += totalCost
        innerAgentCallCount++
      }
      AgentTier.OUTER -> {
        outerAgentInputTokens += tokenUsage.inputTokens
        outerAgentOutputTokens += tokenUsage.outputTokens
        outerAgentCost += totalCost
        outerAgentCallCount++
      }
    }
  }

  /**
   * Convenience method to record an inner agent call.
   */
  fun recordInnerCall(tokenUsage: TokenUsage, model: TrailblazeLlmModel) {
    recordCall(AgentTier.INNER, tokenUsage, model)
  }

  /**
   * Convenience method to record an outer agent call.
   */
  fun recordOuterCall(tokenUsage: TokenUsage, model: TrailblazeLlmModel) {
    recordCall(AgentTier.OUTER, tokenUsage, model)
  }

  /**
   * Returns the current cost breakdown by tier.
   */
  fun getCostBreakdown(): TierCostBreakdown = TierCostBreakdown(
    innerAgentCost = innerAgentCost,
    innerAgentInputTokens = innerAgentInputTokens,
    innerAgentOutputTokens = innerAgentOutputTokens,
    innerAgentCallCount = innerAgentCallCount,
    outerAgentCost = outerAgentCost,
    outerAgentInputTokens = outerAgentInputTokens,
    outerAgentOutputTokens = outerAgentOutputTokens,
    outerAgentCallCount = outerAgentCallCount,
  )

  /**
   * Returns the total cost across both tiers.
   */
  fun getTotalCost(): Double = innerAgentCost + outerAgentCost

  /**
   * Resets all tracked costs and token counts.
   */
  fun reset() {
    innerAgentInputTokens = 0
    innerAgentOutputTokens = 0
    innerAgentCost = 0.0
    innerAgentCallCount = 0
    outerAgentInputTokens = 0
    outerAgentOutputTokens = 0
    outerAgentCost = 0.0
    outerAgentCallCount = 0
  }
}

/**
 * Breakdown of costs by agent tier.
 *
 * Provides detailed visibility into how costs are distributed between
 * inner and outer agents in the two-tier architecture.
 *
 * @property innerAgentCost Total cost (USD) for inner agent calls
 * @property innerAgentInputTokens Total input tokens for inner agent
 * @property innerAgentOutputTokens Total output tokens for inner agent
 * @property innerAgentCallCount Number of inner agent LLM calls
 * @property outerAgentCost Total cost (USD) for outer agent calls
 * @property outerAgentInputTokens Total input tokens for outer agent
 * @property outerAgentOutputTokens Total output tokens for outer agent
 * @property outerAgentCallCount Number of outer agent LLM calls
 */
@Serializable
data class TierCostBreakdown(
  val innerAgentCost: Double,
  val innerAgentInputTokens: Long,
  val innerAgentOutputTokens: Long,
  val innerAgentCallCount: Int,
  val outerAgentCost: Double,
  val outerAgentInputTokens: Long,
  val outerAgentOutputTokens: Long,
  val outerAgentCallCount: Int,
) {
  /**
   * Total cost across both tiers.
   */
  val totalCost: Double = innerAgentCost + outerAgentCost

  /**
   * Total number of LLM calls across both tiers.
   */
  val totalCallCount: Int = innerAgentCallCount + outerAgentCallCount

  /**
   * Percentage of total cost attributed to inner agent (0-100).
   */
  val innerAgentCostPercentage: Double
    get() = if (totalCost > 0) (innerAgentCost / totalCost) * 100 else 0.0

  /**
   * Percentage of total cost attributed to outer agent (0-100).
   */
  val outerAgentCostPercentage: Double
    get() = if (totalCost > 0) (outerAgentCost / totalCost) * 100 else 0.0

  /**
   * Returns a human-readable debug string.
   */
  fun debugString(): String = buildString {
    appendLine("=== Two-Tier Agent Cost Breakdown ===")
    appendLine()
    appendLine("Inner Agent (Screen Analysis):")
    appendLine("  Calls: $innerAgentCallCount")
    appendLine("  Input Tokens: $innerAgentInputTokens")
    appendLine("  Output Tokens: $innerAgentOutputTokens")
    appendLine("  Cost: $${formatCost(innerAgentCost)} (${formatPercentage(innerAgentCostPercentage)})")
    appendLine()
    appendLine("Outer Agent (Planning/Reasoning):")
    appendLine("  Calls: $outerAgentCallCount")
    appendLine("  Input Tokens: $outerAgentInputTokens")
    appendLine("  Output Tokens: $outerAgentOutputTokens")
    appendLine("  Cost: $${formatCost(outerAgentCost)} (${formatPercentage(outerAgentCostPercentage)})")
    appendLine()
    appendLine("Total Cost: $${formatCost(totalCost)}")
    appendLine("Total Calls: $totalCallCount")
  }

  private fun formatCost(cost: Double): String {
    // Round to 6 decimal places using KMP-compatible approach
    val rounded = round(cost * 1_000_000) / 1_000_000
    return rounded.toString()
  }

  private fun formatPercentage(percentage: Double): String {
    // Round to 1 decimal place using KMP-compatible approach
    val rounded = round(percentage * 10) / 10
    return "$rounded%"
  }
}
