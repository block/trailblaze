package xyz.block.trailblaze.llm.config

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A model entry in the YAML config.
 *
 * When `id` matches a built-in model, all specs (context length, capabilities, pricing)
 * are used from the built-in registry. Any fields specified here override the built-in values.
 *
 * For models not in the built-in registry, `context_length` and `max_output_tokens` default
 * to reasonable values (131K/8K) if not specified — allowing project configs to list models
 * that may not be installed yet (e.g., Ollama models).
 */
@Serializable
data class LlmModelConfigEntry(
  @SerialName("id") val id: String,
  @SerialName("tier") val tier: String? = null,
  @SerialName("vision") val vision: Boolean? = null,
  @SerialName("temperature") val temperature: Double? = null,
  @SerialName("context_length") val contextLength: Long? = null,
  @SerialName("max_output_tokens") val maxOutputTokens: Long? = null,
  @SerialName("cost") val cost: LlmModelCostConfig? = null,
  @SerialName("screenshot") val screenshot: LlmScreenshotConfig? = null,
)
