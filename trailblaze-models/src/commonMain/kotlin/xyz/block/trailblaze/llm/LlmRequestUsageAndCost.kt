package xyz.block.trailblaze.llm

import ai.koog.prompt.message.Message
import kotlinx.serialization.Serializable
import xyz.block.trailblaze.llm.providers.OpenAITrailblazeLlmModelList
import kotlin.math.round

@Serializable
data class LlmRequestUsageAndCost(
  val modelName: String,
  val inputTokens: Long,
  val outputTokens: Long,
  val promptCost: Double,
  val completionCost: Double,
  val totalCost: Double = promptCost + completionCost,
) {

  companion object {
    fun List<Message.Response>.calculateCost(llmModelId: String): LlmRequestUsageAndCost {
      val usage = this.last().metaInfo
      // Default to GPT-4.1 if the model name is not found.
      // We will want to get away from our old `LlmModel` info in the future and use Koog's.
      // TODO Find the matching model
      val pricing = OpenAITrailblazeLlmModelList.OPENAI_GPT_4_1
      val promptTokens = usage.inputTokensCount?.toLong() ?: 0L
      val completionTokens = usage.outputTokensCount?.toLong() ?: 0L
      val promptCost = promptTokens * pricing.inputCostPerOneMillionTokens / 1_000_000.0
      val completionCost = completionTokens * pricing.outputCostPerOneMillionTokens / 1_000_000.0

      return LlmRequestUsageAndCost(
        modelName = llmModelId,
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
    appendLine("Model: $modelName")
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
