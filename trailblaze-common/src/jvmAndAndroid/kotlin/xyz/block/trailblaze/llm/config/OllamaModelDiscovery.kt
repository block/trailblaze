package xyz.block.trailblaze.llm.config

import ai.koog.prompt.llm.LLMCapability
import java.util.concurrent.TimeUnit
import xyz.block.trailblaze.llm.TrailblazeLlmModel
import xyz.block.trailblaze.llm.TrailblazeLlmProvider
import xyz.block.trailblaze.util.Console
import xyz.block.trailblaze.util.TrailblazeProcessBuilderUtils

/**
 * Discovers locally installed Ollama models at runtime via the `ollama list` CLI command.
 * Returns [TrailblazeLlmModel] instances with reasonable defaults (cost=0, 128K context).
 * If a discovered model ID matches a built-in model, the built-in's metadata is used.
 */
object OllamaModelDiscovery {

  private const val TIMEOUT_SECONDS = 5L
  private const val DEFAULT_CONTEXT_LENGTH = 131_072L
  private const val DEFAULT_MAX_OUTPUT_TOKENS = 8_192L

  /**
   * Queries `ollama list` and returns discovered models.
   * Returns empty list if Ollama is not installed or the command fails/times out.
   */
  fun discoverModels(): List<TrailblazeLlmModel> {
    if (!TrailblazeProcessBuilderUtils.isCommandAvailable("ollama")) {
      return emptyList()
    }
    return try {
      val process = TrailblazeProcessBuilderUtils
        .createProcessBuilder(listOf("ollama", "list"))
        .start()

      val completed = process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)
      if (!completed) {
        process.destroyForcibly()
        Console.log("Warning: ollama list timed out after ${TIMEOUT_SECONDS}s")
        return emptyList()
      }

      if (process.exitValue() != 0) {
        Console.log("Warning: ollama list exited with code ${process.exitValue()}")
        return emptyList()
      }

      val output = process.inputStream.bufferedReader().readText()
      parseOllamaListOutput(output)
    } catch (e: Exception) {
      Console.log("Warning: Failed to discover Ollama models: ${e.message}")
      emptyList()
    }
  }

  /**
   * Parses the tabular output of `ollama list`.
   * Format: NAME  ID  SIZE  MODIFIED (first line is header)
   */
  internal fun parseOllamaListOutput(output: String): List<TrailblazeLlmModel> {
    return output.lines()
      .drop(1) // skip header
      .filter { it.isNotBlank() }
      .mapNotNull { line ->
        val name = line.split("\\s+".toRegex()).firstOrNull()
        if (name.isNullOrBlank()) return@mapNotNull null

        // If the model is in the built-in registry, use the built-in's full metadata
        val builtIn = BuiltInLlmModelRegistry.find(name)
        if (builtIn != null) {
          return@mapNotNull builtIn.copy(trailblazeLlmProvider = TrailblazeLlmProvider.OLLAMA)
        }

        // Otherwise create a new model with reasonable defaults
        TrailblazeLlmModel(
          trailblazeLlmProvider = TrailblazeLlmProvider.OLLAMA,
          modelId = name,
          inputCostPerOneMillionTokens = 0.0,
          outputCostPerOneMillionTokens = 0.0,
          contextLength = DEFAULT_CONTEXT_LENGTH,
          maxOutputTokens = DEFAULT_MAX_OUTPUT_TOKENS,
          capabilityIds = listOf(
            LLMCapability.Temperature.id,
            LLMCapability.Tools.id,
            LLMCapability.Schema.JSON.Basic.id,
          ),
        )
      }
  }
}
