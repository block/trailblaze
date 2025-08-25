package xyz.block.trailblaze.mcp.utils

import xyz.block.trailblaze.llm.TrailblazeLlmProvider
import xyz.block.trailblaze.mcp.utils.HostAdbCommandUtils.createProcessBuilder
import xyz.block.trailblaze.mcp.utils.HostAdbCommandUtils.runProcess

object LLMProvidersUtil {

  fun getAvailableTrailblazeLlmProviders(): Set<TrailblazeLlmProvider> = buildSet {
    if (System.getenv("OPENAI_API_KEY") != null) {
      add(TrailblazeLlmProvider.OPENAI)
    }
    if (System.getenv("DATABRICKS_TOKEN") != null) {
      add(TrailblazeLlmProvider.DATABRICKS)
    }
    try {
      val processSystemOutput: CommandProcessResult = createProcessBuilder(listOf("ollama", "-v")).runProcess {
        println(it)
      }
      println("Ollama Found.  ${processSystemOutput.fullOutput}")
      add(TrailblazeLlmProvider.OLLAMA)
    } catch (e: Throwable) {
      println("ollama installation not found")
    }
  }
}
