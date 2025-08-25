package xyz.block.trailblaze.llm

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
  val inputCostPerOneMillionTokens: Double,
  val outputCostPerOneMillionTokens: Double,
  val capabilityIds: List<String>,
)
