package xyz.block.trailblaze.llm

import com.aallam.openai.api.chat.ChatCompletion
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
    fun ChatCompletion.calculateCost(): LlmRequestUsageAndCost {
      val usage = this.usage
      val modelName = this.model.id
      val pricing = LlmModel.getModelByName(modelName)
      val promptTokens = usage?.promptTokens?.toLong() ?: 0L
      val completionTokens = usage?.completionTokens?.toLong() ?: 0L
      val promptCost = promptTokens * pricing.inputCostPerOneMillionTokens / 1_000_000.0
      val completionCost = completionTokens * pricing.outputCostPerOneMillionTokens / 1_000_000.0

      return LlmRequestUsageAndCost(
        modelName = pricing.id,
        inputTokens = promptTokens,
        outputTokens = completionTokens,
        promptCost = promptCost,
        completionCost = completionCost,
      )
    }
  }

  fun debugString(): String = buildString {
    appendLine("Model: $modelName")
    appendLine("Prompt Tokens: $inputTokens")
    appendLine("Completion Tokens: $outputTokens")
    appendLine("Prompt Cost: $${"%.6f".format(promptCost)}")
    appendLine("Completion Cost: $${"%.6f".format(completionCost)}")
    appendLine("Total Cost: $${"%.6f".format(totalCost)}")
  }
}
