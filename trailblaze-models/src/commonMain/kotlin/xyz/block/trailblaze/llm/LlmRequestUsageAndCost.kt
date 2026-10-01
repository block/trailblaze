package xyz.block.trailblaze.llm

import ai.koog.prompt.message.Message
import kotlinx.serialization.Serializable
import kotlin.math.round

@Serializable
data class LlmRequestUsageAndCost(
  val trailblazeLlmModel: TrailblazeLlmModel,
  val inputTokens: Long, // LLM-reported input tokens
  val outputTokens: Long, // LLM-reported output tokens
  /** Number of input tokens that were served from cache (cheaper rate). */
  val cacheReadInputTokens: Long = 0L,
  /** Number of input tokens written to cache (may have surcharge on some providers). */
  val cacheCreationInputTokens: Long = 0L,
  val promptCost: Double,
  val completionCost: Double,
  val totalCost: Double = promptCost + completionCost,
  val inputTokenBreakdown: LlmInputTokenBreakdown? = null,
) {

  /** Non-cached input tokens charged at full rate. */
  val nonCachedInputTokens: Long
    get() = inputTokens - cacheReadInputTokens

  /**
   * The savings from cached tokens compared to if all input tokens were charged at full rate.
   * Returns 0.0 if no cached tokens or if cached pricing equals full pricing.
   */
  val cacheSavings: Double
    get() {
      if (cacheReadInputTokens <= 0L) return 0.0
      val fullRateCost = cacheReadInputTokens *
        trailblazeLlmModel.inputCostPerOneMillionTokens / 1_000_000.0
      val cachedRateCost = cacheReadInputTokens *
        trailblazeLlmModel.cachedInputCostPerOneMillionTokens / 1_000_000.0
      return fullRateCost - cachedRateCost
    }

  /**
   * Returns a copy with costs recalculated using the given model's pricing.
   * Used by the host-side LogsRepo to enrich logs from on-device execution
   * where the device may not have had the latest pricing config.
   */
  fun withRecalculatedCosts(model: TrailblazeLlmModel): LlmRequestUsageAndCost {
    val newPromptCost = calculatePromptCost(inputTokens, cacheReadInputTokens, model)
    val newCompletionCost = outputTokens * model.outputCostPerOneMillionTokens / 1_000_000.0
    return copy(
      trailblazeLlmModel = model,
      promptCost = newPromptCost,
      completionCost = newCompletionCost,
      totalCost = newPromptCost + newCompletionCost,
    )
  }

  companion object {
    /**
     * Calculates the prompt cost accounting for cached token discounts.
     *
     * Formula: (non-cached tokens * full rate) + (cached tokens * cached rate)
     */
    fun calculatePromptCost(
      inputTokens: Long,
      cacheReadInputTokens: Long,
      model: TrailblazeLlmModel,
    ): Double {
      val nonCached = inputTokens - cacheReadInputTokens
      val nonCachedCost = nonCached * model.inputCostPerOneMillionTokens / 1_000_000.0
      val cachedCost = cacheReadInputTokens *
        model.cachedInputCostPerOneMillionTokens / 1_000_000.0
      return nonCachedCost + cachedCost
    }
  }


  private fun Double.formatTo6Decimals(): String {
    val rounded = round(this * 1_000_000) / 1_000_000
    return rounded.toString()
  }

  fun debugString(): String = buildString {
    appendLine("Model: ${trailblazeLlmModel.modelId}")
    if (inputTokens == 0L && outputTokens == 0L) {
      appendLine("Usage not available.")
    } else {
      appendLine("Prompt Tokens: $inputTokens")
      if (cacheReadInputTokens > 0L) {
        appendLine("  Cached (read): $cacheReadInputTokens")
        appendLine("  Non-cached: $nonCachedInputTokens")
      }
      if (cacheCreationInputTokens > 0L) {
        appendLine("  Cache creation: $cacheCreationInputTokens")
      }
      appendLine("Completion Tokens: $outputTokens")
      appendLine("Prompt Cost: $${promptCost.formatTo6Decimals()}")
      appendLine("Completion Cost: $${completionCost.formatTo6Decimals()}")
      appendLine("Total Cost: $${totalCost.formatTo6Decimals()}")
      if (cacheSavings > 0.0) {
        appendLine("Cache Savings: $${cacheSavings.formatTo6Decimals()}")
      }

      inputTokenBreakdown?.let {
        appendLine()
        append(it.debugString())
      }
    }
  }
}
