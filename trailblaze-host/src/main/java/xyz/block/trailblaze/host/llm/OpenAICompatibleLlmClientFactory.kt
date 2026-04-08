package xyz.block.trailblaze.host.llm

import ai.koog.prompt.executor.clients.LLMClient
import ai.koog.prompt.executor.clients.openai.OpenAIClientSettings
import ai.koog.prompt.executor.clients.openai.OpenAILLMClient
import io.ktor.client.HttpClient
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.header
import xyz.block.trailblaze.llm.config.LlmProviderConfig

/**
 * Creates [OpenAILLMClient] instances for openai_compatible providers with custom
 * base_url, chat_completions_path, API key, and extra headers.
 *
 * This generalizes the pattern used by BlockDatabricksLLMClient for any
 * OpenAI-compatible endpoint (Azure, vLLM, LM Studio, custom gateways, etc.).
 *
 * The `chat_completions_path` supports `{{model_id}}` placeholder which is substituted
 * with the actual model ID at client creation time. This enables endpoints like Databricks
 * where each model has its own URL path (e.g., `serving-endpoints/{{model_id}}/invocations`).
 */
object OpenAICompatibleLlmClientFactory {

  /**
   * Creates an LLM client for a specific model on this provider.
   *
   * @param providerConfig The provider configuration from YAML
   * @param modelId The model ID to use (substituted into `{{model_id}}` in chat_completions_path)
   * @param apiKey The resolved auth token
   * @param baseClient The base HTTP client
   */
  fun createClient(
    providerConfig: LlmProviderConfig,
    modelId: String? = null,
    apiKey: String?,
    baseClient: HttpClient,
  ): LLMClient {
    val baseUrl =
      providerConfig.baseUrl ?: error("base_url is required for openai_compatible providers")

    val configuredClient =
      if (providerConfig.headers.isNotEmpty()) {
        baseClient.config {
          defaultRequest {
            providerConfig.headers.forEach { (key, value) -> header(key, value) }
          }
        }
      } else {
        baseClient
      }

    val chatPath = providerConfig.chatCompletionsPath?.let { path ->
      if (modelId != null) path.replace("{{model_id}}", modelId) else path
    }

    val settings =
      if (chatPath != null) {
        OpenAIClientSettings(baseUrl = baseUrl, chatCompletionsPath = chatPath)
      } else {
        OpenAIClientSettings(baseUrl = baseUrl)
      }

    val authRequired = providerConfig.auth.required != false
    val resolvedApiKey = if (authRequired) {
      apiKey?.takeUnless { it.isBlank() }
        ?: error("API key is required for provider with base_url=$baseUrl (set auth.required: false to disable)")
    } else {
      apiKey.orEmpty()
    }

    return OpenAILLMClient(
      apiKey = resolvedApiKey,
      settings = settings,
      baseClient = configuredClient,
    )
  }
}
