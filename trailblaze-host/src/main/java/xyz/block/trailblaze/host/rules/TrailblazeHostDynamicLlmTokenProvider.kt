package xyz.block.trailblaze.host.rules

import ai.koog.prompt.executor.clients.LLMClient
import ai.koog.prompt.executor.clients.anthropic.AnthropicLLMClient
import ai.koog.prompt.executor.clients.google.GoogleLLMClient
import ai.koog.prompt.executor.clients.openai.OpenAILLMClient
import ai.koog.prompt.executor.clients.openrouter.OpenRouterLLMClient
import ai.koog.prompt.executor.ollama.client.OllamaClient
import io.ktor.client.HttpClient
import xyz.block.trailblaze.llm.TrailblazeLlmProvider
import xyz.block.trailblaze.llm.providers.TrailblazeDynamicLlmTokenProvider
import xyz.block.trailblaze.util.CommandProcessResult
import xyz.block.trailblaze.util.TrailblazeProcessBuilderUtils.createProcessBuilder
import xyz.block.trailblaze.util.TrailblazeProcessBuilderUtils.runProcess

/**
 * Retrieves LLM API tokens from environment variables
 */
object TrailblazeHostDynamicLlmTokenProvider : TrailblazeDynamicLlmTokenProvider {

  override fun getApiTokenForProvider(llmProvider: TrailblazeLlmProvider): String? = when (llmProvider) {
    TrailblazeLlmProvider.ANTHROPIC -> System.getenv("ANTHROPIC_API_KEY")
    TrailblazeLlmProvider.DATABRICKS -> System.getenv("DATABRICKS_TOKEN")
    TrailblazeLlmProvider.GOOGLE -> System.getenv("GOOGLE_API_KEY")
    TrailblazeLlmProvider.OPENAI -> System.getenv("OPENAI_API_KEY")
    TrailblazeLlmProvider.OPEN_ROUTER -> System.getenv("OPENROUTER_API_KEY")
    else -> error("Currently unsupported provider: $llmProvider")
  }

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

  override fun getLLMClientForProviderIfAvailable(
    trailblazeLlmProvider: TrailblazeLlmProvider,
    baseClient: HttpClient,
  ): LLMClient? {
    if (trailblazeLlmProvider == TrailblazeLlmProvider.OLLAMA) {
      return if (isOllamaInstalled) {
        OllamaClient()
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

        else -> error("${trailblazeLlmProvider.id} LLM client not supported in this version")
      }
    } else {
      null
    }
  }
}
