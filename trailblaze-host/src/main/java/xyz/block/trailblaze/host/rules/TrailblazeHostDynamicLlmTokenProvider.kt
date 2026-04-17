package xyz.block.trailblaze.host.rules

import ai.koog.prompt.executor.clients.LLMClient
import ai.koog.prompt.executor.clients.anthropic.AnthropicLLMClient
import ai.koog.prompt.executor.clients.google.GoogleLLMClient
import ai.koog.prompt.executor.clients.openai.OpenAILLMClient
import ai.koog.prompt.executor.clients.openrouter.OpenRouterLLMClient
import ai.koog.prompt.executor.ollama.client.OllamaClient
import io.ktor.client.HttpClient
import xyz.block.trailblaze.host.llm.OpenAICompatibleLlmClientFactory
import xyz.block.trailblaze.llm.LlmProviderEnvVarUtil
import xyz.block.trailblaze.llm.TrailblazeLlmProvider
import xyz.block.trailblaze.llm.config.LlmConfigLoader
import xyz.block.trailblaze.llm.config.LlmProviderType
import xyz.block.trailblaze.llm.providers.TrailblazeDynamicLlmTokenProvider
import xyz.block.trailblaze.mcp.utils.JvmLLMProvidersUtil

/**
 * Retrieves LLM API tokens from environment variables.
 *
 * Supports both built-in providers (OpenAI, Anthropic, Google, etc.) and custom
 * `openai_compatible` providers defined in the user's `trailblaze.yaml` config.
 */
object TrailblazeHostDynamicLlmTokenProvider : TrailblazeDynamicLlmTokenProvider {

  private val loadedConfig by lazy { LlmConfigLoader.load() }

  val ollamaBaseUrl: String? by lazy {
    loadedConfig.providers["ollama"]?.baseUrl
  }

  private val builtInProviders: Set<TrailblazeLlmProvider> = setOf(
    TrailblazeLlmProvider.OLLAMA,
    TrailblazeLlmProvider.OPENAI,
    TrailblazeLlmProvider.GOOGLE,
    TrailblazeLlmProvider.ANTHROPIC,
    TrailblazeLlmProvider.OPEN_ROUTER,
  )

  override fun supportedProviders(): Set<TrailblazeLlmProvider> {
    val customProviders = loadedConfig.providers
      .filter { (key, config) ->
        config.enabled != false && key !in builtInProviders.map { it.id }.toSet()
      }
      .map { (key, config) ->
        TrailblazeLlmProvider(
          id = key,
          display = config.description
            ?: key.replace("_", " ").replaceFirstChar { it.uppercase() },
        )
      }
    return builtInProviders + customProviders
  }

  override fun getApiTokenForProvider(llmProvider: TrailblazeLlmProvider): String? {
    // Check YAML config for custom env_var first, then fall back to built-in mapping
    val providerConfig = loadedConfig.providers[llmProvider.id]
    return LlmProviderEnvVarUtil.getEnvironmentVariableValueForProviderConfig(
      providerConfig,
      llmProvider,
    )
  }

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

    // Check if this is a custom openai_compatible provider from YAML config
    val providerConfig = loadedConfig.providers[trailblazeLlmProvider.id]
    if (providerConfig?.type == LlmProviderType.OPENAI_COMPATIBLE) {
      val apiKey = getApiTokenForProvider(trailblazeLlmProvider)
      return OpenAICompatibleLlmClientFactory.createClient(
        providerConfig = providerConfig,
        apiKey = apiKey,
        baseClient = baseClient,
      )
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
