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

  fun isProviderAvailableOnJvm(provider: TrailblazeLlmProvider): Boolean {
    return when (provider) {
      TrailblazeLlmProvider.OPENAI -> System.getenv("OPENAI_API_KEY") != null

      TrailblazeLlmProvider.DATABRICKS -> System.getenv("DATABRICKS_TOKEN") != null

      TrailblazeLlmProvider.GOOGLE -> System.getenv("GOOGLE_API_KEY") != null

      TrailblazeLlmProvider.ANTHROPIC -> System.getenv("ANTHROPIC_API_KEY") != null

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
