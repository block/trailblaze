package xyz.block.trailblaze.host.rules

import ai.koog.prompt.executor.clients.LLMClient
import ai.koog.prompt.executor.clients.anthropic.AnthropicLLMClient
import ai.koog.prompt.executor.clients.google.GoogleLLMClient
import ai.koog.prompt.executor.clients.openai.OpenAILLMClient
import ai.koog.prompt.executor.clients.openrouter.OpenRouterLLMClient
import ai.koog.prompt.executor.ollama.client.OllamaClient
import io.ktor.client.HttpClient
import xyz.block.trailblaze.llm.LlmProviderEnvVarUtil
import xyz.block.trailblaze.llm.TrailblazeLlmProvider
import xyz.block.trailblaze.llm.config.LlmConfigLoader
import xyz.block.trailblaze.llm.providers.TrailblazeDynamicLlmTokenProvider
import xyz.block.trailblaze.mcp.utils.JvmLLMProvidersUtil

/**
 * Retrieves LLM API tokens from environment variables
 */
object TrailblazeHostDynamicLlmTokenProvider : TrailblazeDynamicLlmTokenProvider {

  val ollamaBaseUrl: String? by lazy {
    LlmConfigLoader.load().providers["ollama"]?.baseUrl
  }

  override fun supportedProviders(): Set<TrailblazeLlmProvider> = setOf(
    TrailblazeLlmProvider.OLLAMA,
    TrailblazeLlmProvider.OPENAI,
    TrailblazeLlmProvider.GOOGLE,
    TrailblazeLlmProvider.ANTHROPIC,
    TrailblazeLlmProvider.OPEN_ROUTER,
  )

  override fun getApiTokenForProvider(llmProvider: TrailblazeLlmProvider): String? =
    LlmProviderEnvVarUtil.getEnvironmentVariableValueForProvider(llmProvider)

  override fun getLLMClientForProviderIfAvailable(
    trailblazeLlmProvider: TrailblazeLlmProvider,
    baseClient: HttpClient,
  ): LLMClient? {
    if (trailblazeLlmProvider == TrailblazeLlmProvider.OLLAMA) {
      return if (JvmLLMProvidersUtil.isOllamaInstalled) {
        OllamaClient(baseUrl = ollamaBaseUrl ?: "http://localhost:11434")
      } else {
        null
      }
    }
    val apiKey = getApiTokenForProvider(trailblazeLlmProvider)
    return if (apiKey != null) {
      when (trailblazeLlmProvider) {
        TrailblazeLlmProvider.ANTHROPIC -> AnthropicLLMClient(
          baseClient = baseClient,
          apiKey = apiKey,
        )

        TrailblazeLlmProvider.GOOGLE -> GoogleLLMClient(
          baseClient = baseClient,
          apiKey = apiKey,
        )

        TrailblazeLlmProvider.OPENAI -> OpenAILLMClient(
          baseClient = baseClient,
          apiKey = apiKey,
        )

        TrailblazeLlmProvider.OPEN_ROUTER -> OpenRouterLLMClient(
          baseClient = baseClient,
          apiKey = apiKey,
        )

        else -> null
      }
    } else {
      null
    }
  }
}
