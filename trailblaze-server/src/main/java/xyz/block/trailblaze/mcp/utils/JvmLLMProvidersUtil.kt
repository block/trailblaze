package xyz.block.trailblaze.mcp.utils

import kotlin.concurrent.thread
import xyz.block.trailblaze.llm.LlmProviderEnvVarUtil
import xyz.block.trailblaze.llm.TrailblazeLlmModelList
import xyz.block.trailblaze.llm.TrailblazeLlmProvider
import xyz.block.trailblaze.llm.config.BuiltInLlmModelRegistry
import xyz.block.trailblaze.llm.config.LlmAuthResolver
import xyz.block.trailblaze.llm.config.LlmConfig
import xyz.block.trailblaze.llm.config.LlmConfigLoader
import xyz.block.trailblaze.llm.config.LlmProviderConfig
import xyz.block.trailblaze.llm.config.LlmProviderType
import xyz.block.trailblaze.util.TrailblazeProcessBuilderUtils.isCommandAvailable

object JvmLLMProvidersUtil {

  /**
   * How long the Ollama PATH lookup may take. Short because the first MCP session asks while
   * answering the client's `initialize`, which the CLI bounds at a few seconds. Ollama is an
   * optional provider, so a lookup cut off here only hides it for this run.
   */
  private const val OLLAMA_LOOKUP_TIMEOUT_MS = 1_000L

  val isOllamaInstalled: Boolean by lazy {
    isCommandAvailable("ollama", OLLAMA_LOOKUP_TIMEOUT_MS)
  }

  /**
   * Starts the [isOllamaInstalled] lookup on a background thread and returns at once, so the first
   * MCP session's `initialize` normally finds it done. A session that arrives before it finishes
   * waits at most [OLLAMA_LOOKUP_TIMEOUT_MS].
   */
  fun warmUpInBackground() {
    thread(isDaemon = true, name = "trailblaze-llm-provider-lookup") { isOllamaInstalled }
  }

  fun getEnvironmentVariableKeyForLlmProvider(llmProvider: TrailblazeLlmProvider): String? {
    return LlmProviderEnvVarUtil.getEnvironmentVariableKeyForProvider(llmProvider)
  }

  fun getEnvironmentVariableValueForLlmProvider(llmProvider: TrailblazeLlmProvider): String? {
    return LlmProviderEnvVarUtil.getEnvironmentVariableValueForProvider(llmProvider)
  }

  /**
   * Returns instrumentation args for all available LLM providers.
   * Config-driven: reads from YAML config and resolves tokens via [LlmAuthResolver].
   */
  fun getAdditionalInstrumentationArgs(
    config: LlmConfig = LlmConfigLoader.load(),
    customTokenProviders: Map<String, () -> String?> = emptyMap(),
    selectedProviderId: String? = null,
    defaultModel: String? = null,
  ): Map<String, String> {
    val auths = LlmAuthResolver.resolveAll(config, customTokenProviders)
    return LlmAuthResolver.toInstrumentationArgs(auths, selectedProviderId, defaultModel)
  }

  fun isProviderAvailableOnJvm(provider: TrailblazeLlmProvider): Boolean {
    return when (provider) {
      TrailblazeLlmProvider.OLLAMA -> isOllamaInstalled
      else -> {
        // Check built-in provider YAML auth config (e.g. auth.required: false)
        val builtInAuth = BuiltInLlmModelRegistry.authForProvider(provider)
        if (builtInAuth?.required == false) return true
        getEnvironmentVariableValueForLlmProvider(provider) != null
      }
    }
  }

  /**
   * Checks if a YAML-configured provider is available on this JVM.
   * Handles openai_compatible providers with auth.required=false (always available)
   * and custom auth.env_var settings.
   */
  fun isProviderAvailable(
    providerKey: String,
    config: LlmProviderConfig,
  ): Boolean {
    // Ollama: check if CLI is installed
    if (config.type == LlmProviderType.OLLAMA || providerKey == "ollama") {
      return isOllamaInstalled
    }

    // If auth is explicitly not required, provider is always available
    if (config.auth.required == false) {
      return true
    }

    // Check the configured env var first, then fall back to built-in mapping
    val envVarKey = config.auth.envVar
      ?: TrailblazeLlmProvider.ALL_PROVIDERS
        .firstOrNull { it.id == providerKey }
        ?.let { getEnvironmentVariableKeyForLlmProvider(it) }
    return envVarKey?.let { System.getenv(it) } != null
  }

  fun getAvailableTrailblazeLlmProviderModelLists(allPossibleModelLists: Set<TrailblazeLlmModelList>): Set<TrailblazeLlmModelList> {
    val providerToModelListMap = allPossibleModelLists.associateBy { it.provider }
    val availableProviders = providerToModelListMap.keys.filter { provider -> isProviderAvailableOnJvm(provider) }
    return providerToModelListMap.flatMap { (llmProvider, llmModelList) ->
      val include = availableProviders.contains(llmProvider)
      if (include) {
        listOf(llmModelList)
      } else {
        emptyList()
      }
    }.toSet()
  }

  /**
   * Config-aware variant that uses YAML provider configs for availability checks.
   * Custom/YAML-defined providers use [isProviderAvailable] (respects auth.required, auth.env_var);
   * providers without a config entry fall back to [isProviderAvailableOnJvm].
   */
  fun getAvailableTrailblazeLlmProviderModelLists(
    allPossibleModelLists: Set<TrailblazeLlmModelList>,
    config: LlmConfig,
  ): Set<TrailblazeLlmModelList> {
    return allPossibleModelLists.filter { modelList ->
      val providerKey = modelList.provider.id
      val providerConfig = config.providers[providerKey]
      if (providerConfig != null) {
        isProviderAvailable(providerKey, providerConfig)
      } else {
        isProviderAvailableOnJvm(modelList.provider)
      }
    }.toSet()
  }
}
