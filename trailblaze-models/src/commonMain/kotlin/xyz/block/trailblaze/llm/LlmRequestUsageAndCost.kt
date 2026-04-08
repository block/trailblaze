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
   * Returns true if the estimated breakdown matches the LLM-reported input tokens.
   * Allows for a small tolerance of 1% or 10 tokens (whichever is larger).
   */
  fun hasInputTokenMismatch(): Boolean {
    val breakdown = inputTokenBreakdown ?: return false
    if (inputTokens == 0L) return false

    val estimatedTotal = breakdown.totalEstimatedTokens
    val diff = kotlin.math.abs(estimatedTotal - inputTokens)
    val tolerance = maxOf(10, (inputTokens * 0.01).toLong())

    return diff > tolerance
  }

  /**
   * Returns the difference between estimated and reported tokens.
   */
  fun getInputTokenDifference(): Long {
    val breakdown = inputTokenBreakdown ?: return 0
    return breakdown.totalEstimatedTokens - inputTokens
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

    /**
     * Calculates cost from LLM response without token breakdown.
     * Use this when you don't have access to the original request data.
     */
    fun List<Message.Response>.calculateCost(
      trailblazeLlmModel: TrailblazeLlmModel,
    ): LlmRequestUsageAndCost {
      val usage = this.last().metaInfo
      val promptTokens = usage.inputTokensCount?.toLong() ?: 0L
      val completionTokens = usage.outputTokensCount?.toLong() ?: 0L
      val cachedTokens = CachedTokenExtractor.extractCacheReadTokens(usage.metadata)

      val promptCost = calculatePromptCost(promptTokens, cachedTokens, trailblazeLlmModel)
      val completionCost =
        completionTokens * trailblazeLlmModel.outputCostPerOneMillionTokens / 1_000_000.0

      return LlmRequestUsageAndCost(
        trailblazeLlmModel = trailblazeLlmModel,
        inputTokens = promptTokens,
        outputTokens = completionTokens,
        cacheReadInputTokens = cachedTokens,
        cacheCreationInputTokens = CachedTokenExtractor.extractCacheCreationTokens(usage.metadata),
        promptCost = promptCost,
        completionCost = completionCost,
        inputTokenBreakdown = null,
      )
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
