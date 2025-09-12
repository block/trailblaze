package xyz.block.trailblaze.llm

import ai.koog.prompt.message.Message
import kotlinx.serialization.Serializable
import kotlin.math.round

@Serializable
data class LlmRequestUsageAndCost(
  val trailblazeLlmModel: TrailblazeLlmModel,
  val inputTokens: Long,
  val outputTokens: Long,
  val promptCost: Double,
  val completionCost: Double,
  val totalCost: Double = promptCost + completionCost,
) {

  companion object {
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
    }
  }
}
