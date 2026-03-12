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
 *  https://docs.anthropic.com/en/docs/build-with-claude/prompt-caching
 *  https://ai.google.dev/gemini-api/docs/caching
 */
@Serializable
data class TrailblazeLlmModel(
  val trailblazeLlmProvider: TrailblazeLlmProvider,
  val modelId: String,
  val inputCostPerOneMillionTokens: Double,
  val outputCostPerOneMillionTokens: Double,
  /** Cost per million tokens for cached input reads. Defaults to full input price when unknown. */
  val cachedInputCostPerOneMillionTokens: Double = inputCostPerOneMillionTokens,
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
      cachedInputDiscountMultiplier: Double = 1.0,
      maxOutputTokens: Long? = null,
    ): TrailblazeLlmModel {
      return TrailblazeLlmModel(
        trailblazeLlmProvider = TrailblazeLlmProvider.fromKoogLlmProvider(this.provider),
        modelId = this.id,
        inputCostPerOneMillionTokens = inputCostPerOneMillionTokens,
        outputCostPerOneMillionTokens = outputCostPerOneMillionTokens,
        cachedInputCostPerOneMillionTokens = inputCostPerOneMillionTokens * cachedInputDiscountMultiplier,
        contextLength = this.contextLength
          ?: error("contextLength must be set for ${this.id}"),
        maxOutputTokens = maxOutputTokens ?: this.maxOutputTokens
        ?: error("maxOutputTokens must be set for ${this.id}"),
        capabilityIds = this.capabilities?.map { it.id } ?: emptyList()
      )
    }

    /**
     * Creates a TrailblazeLlmModel for MCP Sampling requests.
     *
     * MCP sampling delegates LLM calls to the client, so we don't know the exact
     * model/provider being used. The client may report the model name, which is
     * captured here. Cost is set to 0 since we can't determine the actual cost.
     *
     * @param client The identified MCP client (use [McpClient.fromClientName] to identify)
     * @param modelNameFromResponse The model name reported by the MCP client in the sampling response
     *                              (e.g., "claude-3-5-sonnet-20241022") or null if not reported
     */
    fun mcpSampling(
      client: McpClient = McpClient.UNKNOWN,
      modelNameFromResponse: String? = null,
    ): TrailblazeLlmModel {
      val modelId = buildString {
        append(client.id)
        if (modelNameFromResponse != null) {
          append(":")
          append(modelNameFromResponse)
        }
      }

      return TrailblazeLlmModel(
        trailblazeLlmProvider = TrailblazeLlmProvider.MCP_SAMPLING,
        modelId = modelId,
        inputCostPerOneMillionTokens = 0.0, // Unknown cost - client pays
        outputCostPerOneMillionTokens = 0.0, // Unknown cost - client pays
        contextLength = 200_000, // Reasonable default
        maxOutputTokens = 8_192, // Reasonable default
        capabilityIds = listOf("vision", "tools"), // Assume full capabilities
      )
    }

    /**
     * Creates a TrailblazeLlmModel for MCP Sampling from a raw client name string.
     *
     * Convenience overload that identifies the client from the raw clientInfo.name.
     *
     * @param rawClientName The raw client name from MCP clientInfo (e.g., "block-goose", "Visual Studio Code")
     * @param modelNameFromResponse The model name reported by the MCP client in the sampling response
     * @see McpClient.fromClientName
     */
    fun mcpSampling(
      rawClientName: String?,
      modelNameFromResponse: String? = null,
    ): TrailblazeLlmModel = mcpSampling(
      client = McpClient.fromClientName(rawClientName),
      modelNameFromResponse = modelNameFromResponse,
    )
  }
}
