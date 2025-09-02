package xyz.block.trailblaze.llm

import ai.koog.prompt.llm.LLMCapability

interface TrailblazeLlmModelList {
  val entries: List<TrailblazeLlmModel>
  val provider: TrailblazeLlmProvider
}

/**
 * Sources:
 *  https://github.com/BerriAI/litellm/blob/main/model_prices_and_context_window.json
 *  https://platform.openai.com/docs/pricing
 */
data class TrailblazeLlmModel(
  val trailblazeLlmProvider: TrailblazeLlmProvider,
  val modelId: String,
  private val capabilities: List<LLMCapability>,
  val inputCostPerOneMillionTokens: Double,
  val outputCostPerOneMillionTokens: Double,
  val contextLength: Long,
  val maxOutputTokens: Long,
) {
  val capabilityIds: List<String> = capabilities.map { it.id }
}
