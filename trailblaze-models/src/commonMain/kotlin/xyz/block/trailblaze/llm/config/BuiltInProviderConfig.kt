package xyz.block.trailblaze.llm.config

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Schema for built-in provider YAML files shipped with Trailblaze.
 *
 * Each file at `trailblaze-config/providers/{name}.yaml` describes one provider and its models.
 * The [providerId] makes each file self-describing — it should match the filename
 * but the file doesn't depend on its path to be interpreted correctly.
 *
 * The optional [name] field provides a human-readable display name for the provider
 * (shown in the UI). If omitted, a display name is derived from the [providerId].
 *
 * The [description] field supports Markdown for rich text (e.g. links, emphasis).
 *
 * The [auth] block specifies how to authenticate with this provider (env var, headers, etc.).
 * The [models] list uses the same [LlmModelConfigEntry] schema that users write
 * in their own `trailblaze.yaml` files.
 */
@Serializable
data class BuiltInProviderConfig(
  @SerialName("provider_id") val providerId: String,
  @SerialName("name") val name: String? = null,
  @SerialName("type") val type: LlmProviderType? = null,
  @SerialName("description") val description: String? = null,
  @SerialName("base_url") val baseUrl: String? = null,
  @SerialName("chat_completions_path") val chatCompletionsPath: String? = null,
  @SerialName("headers") val headers: Map<String, String> = emptyMap(),
  @SerialName("auth") val auth: LlmAuthConfig = LlmAuthConfig(),
  @SerialName("default_model") val defaultModel: String? = null,
  @SerialName("models") val models: List<LlmModelConfigEntry> = emptyList(),
)
