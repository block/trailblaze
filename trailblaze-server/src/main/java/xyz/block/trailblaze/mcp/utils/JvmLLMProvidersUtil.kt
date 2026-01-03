package xyz.block.trailblaze.mcp.utils

import xyz.block.trailblaze.llm.TrailblazeLlmModelList
import xyz.block.trailblaze.llm.TrailblazeLlmProvider
import xyz.block.trailblaze.util.CommandProcessResult
import xyz.block.trailblaze.util.TrailblazeProcessBuilderUtils.createProcessBuilder
import xyz.block.trailblaze.util.TrailblazeProcessBuilderUtils.runProcess

object JvmLLMProvidersUtil {

  private val isOllamaInstalled: Boolean by lazy {
    try {
      val processSystemOutput: CommandProcessResult = createProcessBuilder(
        listOf("ollama", "-v"),
      ).runProcess {
        println(it)
      }
      println("Ollama Found.  ${processSystemOutput.fullOutput}")
      true
    } catch (e: Throwable) {
      println("ollama installation not found")
      false
    }
  }

  fun getEnvironmentVariableKeyForLlmProvider(llmProvider: TrailblazeLlmProvider): String? {
    return when (llmProvider) {
      TrailblazeLlmProvider.OPENAI -> "OPENAI_API_KEY"

      TrailblazeLlmProvider.DATABRICKS -> "DATABRICKS_TOKEN"

      TrailblazeLlmProvider.GOOGLE -> "GOOGLE_API_KEY"

      TrailblazeLlmProvider.ANTHROPIC -> "ANTHROPIC_API_KEY"
      else -> null
    }
  }


  fun getEnvironmentVariableValueForLlmProvider(llmProvider: TrailblazeLlmProvider): String? {
    val key = getEnvironmentVariableKeyForLlmProvider(llmProvider)
    return key?.let { System.getenv(it) }
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
      TrailblazeLlmProvider.OPENAI,
      TrailblazeLlmProvider.DATABRICKS,
      TrailblazeLlmProvider.GOOGLE,
      TrailblazeLlmProvider.ANTHROPIC -> getEnvironmentVariableValueForLlmProvider(provider) != null

      TrailblazeLlmProvider.OLLAMA -> isOllamaInstalled

      else -> false
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
