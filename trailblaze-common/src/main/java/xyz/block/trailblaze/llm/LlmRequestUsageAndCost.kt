package xyz.block.trailblaze.llm

import ai.koog.prompt.message.Message
import kotlinx.serialization.Serializable

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
      val modelName = llmModelId
      // Default to GPT-4.1 if the model name is not found.
      // We will want to get away from our old `LlmModel` info in the future and use Koog's.
      val pricing = LlmModel.getModelByName(modelName) ?: LlmModel.GPT_4_1
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

  fun debugString(): String = buildString {
    appendLine("Model: $modelName")
    if (inputTokens == 0L && outputTokens == 0L) {
      appendLine("Usage not available.")
    } else {
      appendLine("Prompt Tokens: $inputTokens")
      appendLine("Completion Tokens: $outputTokens")
      appendLine("Prompt Cost: $${"%.6f".format(promptCost)}")
      appendLine("Completion Cost: $${"%.6f".format(completionCost)}")
      appendLine("Total Cost: $${"%.6f".format(totalCost)}")
    }
  }
}
