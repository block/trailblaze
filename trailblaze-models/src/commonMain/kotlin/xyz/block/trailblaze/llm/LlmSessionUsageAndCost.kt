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
) {
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
      appendLine("- Output Token Count: $totalOutputTokens")
      appendLine("- Cost: $${totalCostInUsDollars.formatTo2Decimals()}")
    }
    appendLine("--- Averages ---")
    appendLine("- Duration (seconds): ${(averageDurationMillis / 1000).formatTo2Decimals()}")

    if (totalRequestCount > 0 && totalOutputTokens > 0) {
      appendLine("- Input Tokens: ${(averageInputTokens / 1000).formatTo2Decimals()}")
      appendLine("- Output Tokens: ${(averageOutputTokens / 1000).formatTo2Decimals()}")
    }
  }
}
