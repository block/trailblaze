package xyz.block.trailblaze.llm

import ai.koog.prompt.message.Message
import kotlinx.serialization.Serializable
import kotlin.math.round

@Serializable
data class LlmRequestUsageAndCost(
  val trailblazeLlmModel: TrailblazeLlmModel,
  val inputTokens: Long, // LLM-reported input tokens
  val outputTokens: Long, // LLM-reported output tokens
  val promptCost: Double,
  val completionCost: Double,
  val totalCost: Double = promptCost + completionCost,
  val inputTokenBreakdown: LlmInputTokenBreakdown? = null,
) {
  
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

  companion object {
    /**
     * Calculates cost from LLM response without token breakdown.
     * Use this when you don't have access to the original request data.
     */
    fun List<Message.Response>.calculateCost(trailblazeLlmModel: TrailblazeLlmModel): LlmRequestUsageAndCost {
      val usage = this.last().metaInfo
      val promptTokens = usage.inputTokensCount?.toLong() ?: 0L
      val completionTokens = usage.outputTokensCount?.toLong() ?: 0L
      val promptCost = promptTokens * trailblazeLlmModel.inputCostPerOneMillionTokens / 1_000_000.0
      val completionCost = completionTokens * trailblazeLlmModel.outputCostPerOneMillionTokens / 1_000_000.0

      return LlmRequestUsageAndCost(
        trailblazeLlmModel = trailblazeLlmModel,
        inputTokens = promptTokens,
        outputTokens = completionTokens,
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
      appendLine("Completion Tokens: $outputTokens")
      appendLine("Prompt Cost: $${promptCost.formatTo6Decimals()}")
      appendLine("Completion Cost: $${completionCost.formatTo6Decimals()}")
      appendLine("Total Cost: $${totalCost.formatTo6Decimals()}")
      
      inputTokenBreakdown?.let {
        appendLine()
        append(it.debugString())
      }
    }
  }
}
