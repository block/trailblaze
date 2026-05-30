package xyz.block.trailblaze.host.llm

import ai.koog.http.client.ktor.KtorKoogHttpClient
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
 * Generic factory for OpenAI-compatible endpoints (Azure, Databricks, vLLM, LM Studio,
 * custom gateways, etc.) whose request shape matches OpenAI but whose URL/auth differs.
 *
 * The `chat_completions_path` supports `{{model_id}}` placeholder which is substituted
 * with the actual model ID at client creation time. This enables endpoints like Databricks
 * where each model has its own URL path (e.g., `serving-endpoints/{{model_id}}/invocations`).
 *
 * ## `chat_completions_path` default and the Koog 1.0.0 `/v1/` double-segment trap
 *
 * Koog 1.0.0's [ai.koog.http.client.ktor.KtorKoogHttpClient.Factory.create] force-normalizes
 * the base URL to end in `/`. Ktor's relative-URL resolution against a trailing-slash base
 * appends rather than replacing the last segment — so `base_url=https://api.openai.com/v1`
 * + Koog's default `chatCompletionsPath=v1/chat/completions` produces a 404-yielding
 * `https://api.openai.com/v1/v1/chat/completions` URL. This factory and its
 * counterparts ([BlockDynamicLlmClient.createOpenAiCompatibleClient],
 * [OnDeviceOpenAICompatibleLlmClientFactory]) therefore default `chatCompletionsPath` to
 * `"chat/completions"` (no version prefix), since the openai_compatible convention is
 * that `base_url` already carries the version path. Explicit
 * `chat_completions_path:` in YAML still takes precedence.
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

    // See [chatPath] default below for why we don't fall through to Koog's
    // `OpenAIClientSettings.chatCompletionsPath` default in openai_compatible mode.
    val chatPath = providerConfig.chatCompletionsPath?.let { path ->
      if (modelId != null) path.replace("{{model_id}}", modelId) else path
    } ?: "chat/completions"

    val settings = OpenAIClientSettings(baseUrl = baseUrl, chatCompletionsPath = chatPath)

    val authRequired = providerConfig.auth.required != false
    val resolvedApiKey = if (authRequired) {
      apiKey?.takeUnless { it.isBlank() }
        ?: error("API key is required for provider with base_url=$baseUrl (set auth.required: false to disable)")
    } else {
      apiKey.orEmpty()
    }

    // Koog 1.0.0 decoupled HTTP transport: LLM client constructors no longer accept a Ktor
    // `HttpClient` directly — instead they take a `KoogHttpClient.Factory`. Wrap our
    // customized Ktor client (with extra headers, TLS config, interceptors) via
    // `KtorKoogHttpClient.Factory(baseClient = ...)` so its config propagates through.
    return OpenAILLMClient(
      apiKey = resolvedApiKey,
      settings = settings,
      httpClientFactory = KtorKoogHttpClient.Factory(baseClient = configuredClient),
    )
  }
}
