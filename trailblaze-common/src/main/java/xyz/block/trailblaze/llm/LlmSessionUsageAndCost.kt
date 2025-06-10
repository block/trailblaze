package xyz.block.trailblaze.llm

import kotlinx.serialization.Serializable

/**
 * This is just a summary/roll up of the LLM usage.
 */
@Serializable
data class LlmSessionUsageAndCost(
  val modelName: String,
  val averageDurationMillis: Double,
  val totalCostInUsDollars: Double,
  val totalRequestCount: Int,
  val totalInputTokens: Long,
  val totalOutputTokens: Long,
  val averageInputTokens: Double,
  val averageOutputTokens: Double,
) {
  fun debugString(): String = buildString {
    appendLine("Model: $modelName")
    appendLine("--- Totals ---")
    appendLine("- Requests: $totalRequestCount")
    appendLine("- Input Token Count: $totalInputTokens")
    appendLine("- Output Token Count: $totalOutputTokens")
    appendLine("- Cost: $${"%.2f".format(totalCostInUsDollars)}")
    appendLine("--- Averages ---")
    appendLine("- Duration (seconds): ${"%.2f".format(averageDurationMillis / 1000)}")
    appendLine("- Input Tokens: ${"%.2f".format(averageInputTokens / 1000)}")
    appendLine("- Output Tokens: ${"%.2f".format(averageOutputTokens / 1000)}")
  }
}
