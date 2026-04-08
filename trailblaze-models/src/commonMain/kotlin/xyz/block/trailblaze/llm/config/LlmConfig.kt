package xyz.block.trailblaze.llm.config

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Top-level LLM configuration matching the schema from Decision 030.
 * Loaded from ~/.trailblaze/trailblaze.yaml and/or ./trailblaze.yaml (under llm: key).
 */
@Serializable
data class LlmConfig(
  @SerialName("providers") val providers: Map<String, LlmProviderConfig> = emptyMap(),
  @SerialName("defaults") val defaults: LlmDefaultsConfig = LlmDefaultsConfig(),
)

@Serializable
data class LlmDefaultsConfig(
  @SerialName("model") val model: String? = null,
  @SerialName("screenshot") val screenshot: LlmScreenshotConfig? = null,
)

/**
 * User-defined provider configuration from `trailblaze.yaml`.
 *
 * The [description] field supports Markdown for rich text (e.g. links, emphasis).
 */
@Serializable
data class LlmProviderConfig(
  @SerialName("enabled") val enabled: Boolean? = null,
  @SerialName("type") val type: LlmProviderType? = null,
  @SerialName("description") val description: String? = null,
  @SerialName("base_url") val baseUrl: String? = null,
  @SerialName("chat_completions_path") val chatCompletionsPath: String? = null,
  @SerialName("headers") val headers: Map<String, String> = emptyMap(),
  @SerialName("auth") val auth: LlmAuthConfig = LlmAuthConfig(),
  @SerialName("models") val models: List<LlmModelConfigEntry> = emptyList(),
)

@Serializable
enum class LlmProviderType {
  @SerialName("openai") OPENAI,
  @SerialName("anthropic") ANTHROPIC,
  @SerialName("google") GOOGLE,
  @SerialName("ollama") OLLAMA,
  @SerialName("openrouter") OPEN_ROUTER,
  @SerialName("openai_compatible") OPENAI_COMPATIBLE,
}

@Serializable
data class LlmAuthConfig(
  @SerialName("env_var") val envVar: String? = null,
  @SerialName("required") val required: Boolean? = null,
)
