package xyz.block.trailblaze.llm

import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLModel
import kotlinx.serialization.Serializable

interface TrailblazeLlmModelList {
  val entries: List<TrailblazeLlmModel>
  val provider: TrailblazeLlmProvider
}

/**
 * Sources:
 *  https://github.com/BerriAI/litellm/blob/main/model_prices_and_context_window.json
 *  https://platform.openai.com/docs/pricing
 */
@Serializable
data class TrailblazeLlmModel(
  val trailblazeLlmProvider: TrailblazeLlmProvider,
  val modelId: String,
  val inputCostPerOneMillionTokens: Double,
  val outputCostPerOneMillionTokens: Double,
  val contextLength: Long,
  val maxOutputTokens: Long,
  val capabilityIds: List<String>,
) {
  val capabilities: List<LLMCapability> = LlmCapabilitiesUtil.capabilitiesFromIds(capabilityIds)

  fun toKoogLlmModel(): LLModel = LLModel(
    id = modelId,
    capabilities = capabilities,
    provider = trailblazeLlmProvider.toKoogLlmProvider(),
    maxOutputTokens = maxOutputTokens,
    contextLength = contextLength,
  )

  companion object {
    fun LLModel.toTrailblazeLlmModel(
      inputCostPerOneMillionTokens: Double,
      outputCostPerOneMillionTokens: Double,
      maxOutputTokens: Long? = null,
    ): TrailblazeLlmModel {
      return TrailblazeLlmModel(
        trailblazeLlmProvider = TrailblazeLlmProvider.fromKoogLlmProvider(this.provider),
        modelId = this.id,
        inputCostPerOneMillionTokens = inputCostPerOneMillionTokens,
        outputCostPerOneMillionTokens = outputCostPerOneMillionTokens,
        contextLength = this.contextLength,
        maxOutputTokens = maxOutputTokens ?: this.maxOutputTokens
        ?: error("maxOutputTokens must be set for ${this.id}"),
        capabilityIds = this.capabilities.map { it.id }
      )
    }
  }
}
