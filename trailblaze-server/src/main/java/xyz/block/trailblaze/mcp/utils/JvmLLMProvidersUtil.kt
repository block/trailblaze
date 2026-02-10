package xyz.block.trailblaze.mcp.utils

import xyz.block.trailblaze.llm.LlmProviderEnvVarUtil
import xyz.block.trailblaze.llm.TrailblazeLlmModelList
import xyz.block.trailblaze.llm.TrailblazeLlmProvider
import xyz.block.trailblaze.util.TrailblazeProcessBuilderUtils.isCommandAvailable

object JvmLLMProvidersUtil {

  val isOllamaInstalled: Boolean by lazy {
    isCommandAvailable("ollama")
  }

  fun getEnvironmentVariableKeyForLlmProvider(llmProvider: TrailblazeLlmProvider): String? {
    return LlmProviderEnvVarUtil.getEnvironmentVariableKeyForProvider(llmProvider)
  }

  fun getEnvironmentVariableValueForLlmProvider(llmProvider: TrailblazeLlmProvider): String? {
    return LlmProviderEnvVarUtil.getEnvironmentVariableValueForProvider(llmProvider)
  }

  fun getAdditionalInstrumentationArgs(): Map<String, String> {
    return TrailblazeLlmProvider.ALL_PROVIDERS.mapNotNull { llmProvider ->
      val key = getEnvironmentVariableKeyForLlmProvider(llmProvider)
      val value = getEnvironmentVariableValueForLlmProvider(llmProvider)
      if (key != null && value != null) {
        key to value
      } else {
        null
      }
    }.toMap()
  }

  fun isProviderAvailableOnJvm(provider: TrailblazeLlmProvider): Boolean {
    return when (provider) {
      TrailblazeLlmProvider.OLLAMA -> isOllamaInstalled
      else -> getEnvironmentVariableValueForLlmProvider(provider) != null
    }
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

  fun getAvailableTrailblazeLlmProviders(modelLists: Set<TrailblazeLlmModelList>): Set<TrailblazeLlmProvider> =
    modelLists.map { it.provider }.filter { isProviderAvailableOnJvm(it) }.toSet()
}
