package xyz.block.trailblaze.mcp.utils

import xyz.block.trailblaze.llm.TrailblazeLlmModelList
import xyz.block.trailblaze.llm.TrailblazeLlmProvider
import xyz.block.trailblaze.mcp.utils.HostAdbCommandUtils.createProcessBuilder
import xyz.block.trailblaze.mcp.utils.HostAdbCommandUtils.runProcess

object JvmLLMProvidersUtil {

  fun isProviderAvailableOnJvm(provider: TrailblazeLlmProvider): Boolean {
    when (provider) {
      TrailblazeLlmProvider.OPENAI -> {
        if (System.getenv("OPENAI_API_KEY") != null) {
          return true
        }
      }

      TrailblazeLlmProvider.DATABRICKS -> {
        if (System.getenv("DATABRICKS_TOKEN") != null) {
          return true
        }
      }

      TrailblazeLlmProvider.OLLAMA -> {
        try {
          val processSystemOutput: CommandProcessResult = createProcessBuilder(listOf("ollama", "-v")).runProcess {
            println(it)
          }
          println("Ollama Found.  ${processSystemOutput.fullOutput}")
          return true
        } catch (e: Throwable) {
          println("ollama installation not found")
        }
      }
    }
    return false
  }

  fun getAvailableTrailblazeLlmProviders(modelLists: Set<TrailblazeLlmModelList>): Set<TrailblazeLlmProvider> = modelLists.map { it.provider }.filter { isProviderAvailableOnJvm(it) }.toSet()
}
