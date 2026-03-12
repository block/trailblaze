package xyz.block.trailblaze.llm

import kotlinx.serialization.Serializable
import kotlin.math.round

/**
 * This is just a summary/roll up of the LLM usage.
 */
@Serializable
data class LlmSessionUsageAndCost(
  val llmModel: TrailblazeLlmModel,
  val averageDurationMillis: Double,
  val totalCostInUsDollars: Double,
  val totalRequestCount: Int,
  val totalInputTokens: Long,
  val totalOutputTokens: Long,
  val averageInputTokens: Double,
  val averageOutputTokens: Double,
  /** Total input tokens served from cache across all requests. */
  val totalCacheReadInputTokens: Long = 0L,
  /** Total input tokens written to cache across all requests. */
  val totalCacheCreationInputTokens: Long = 0L,
  /** Total cost savings from cached tokens compared to full-rate pricing. */
  val totalCacheSavings: Double = 0.0,
  val aggregatedInputTokenBreakdown: LlmInputTokenBreakdown? = null,
  val requestBreakdowns: List<LlmRequestUsageAndCost> = emptyList(),
) {
  /** Percentage of total input tokens that were served from cache. */
  val cacheHitPercentage: Double
    get() = if (totalInputTokens > 0) {
      (totalCacheReadInputTokens.toDouble() / totalInputTokens) * 100
    } else {
      0.0
    }

  /**
   * What the total cost would have been without any cache discounts.
   * Useful for showing the savings impact.
   */
  val totalCostWithoutCacheDiscount: Double
    get() = totalCostInUsDollars + totalCacheSavings

  private fun Double.formatTo2Decimals(): String {
    val rounded = round(this * 100) / 100
    return rounded.toString()
  }

  fun debugString(): String = buildString {
    appendLine("Model: ${llmModel.modelId}")
    appendLine("--- Totals ---")
    appendLine("- Requests: $totalRequestCount")
    if (totalRequestCount > 0 && totalOutputTokens > 0) {
      appendLine("- Input Token Count: $totalInputTokens")
      if (totalCacheReadInputTokens > 0) {
        appendLine("  - Cached (read): $totalCacheReadInputTokens (${cacheHitPercentage.formatTo2Decimals()}%)")
        appendLine("  - Non-cached: ${totalInputTokens - totalCacheReadInputTokens}")
      }
      if (totalCacheCreationInputTokens > 0) {
        appendLine("  - Cache creation: $totalCacheCreationInputTokens")
      }
      appendLine("- Output Token Count: $totalOutputTokens")
      appendLine("- Cost: $${totalCostInUsDollars.formatTo2Decimals()}")
      if (totalCacheSavings > 0.0) {
        appendLine("- Cache Savings: $${totalCacheSavings.formatTo2Decimals()}")
        appendLine("- Cost without cache: $${totalCostWithoutCacheDiscount.formatTo2Decimals()}")
      }
    }
    appendLine("--- Averages ---")
    appendLine("- Duration (seconds): ${(averageDurationMillis / 1000).formatTo2Decimals()}")

    if (totalRequestCount > 0 && totalOutputTokens > 0) {
      appendLine("- Input Tokens: ${(averageInputTokens / 1000).formatTo2Decimals()}")
      appendLine("- Output Tokens: ${(averageOutputTokens / 1000).formatTo2Decimals()}")
    }

    aggregatedInputTokenBreakdown?.let {
      appendLine()
      appendLine("--- Token Breakdown (Aggregated) ---")
      append(it.debugString())
    }
  }
}
