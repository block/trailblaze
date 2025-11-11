package xyz.block.trailblaze.host.rules

import ai.koog.prompt.executor.clients.LLMClient
import ai.koog.prompt.executor.clients.openai.OpenAILLMClient
import ai.koog.prompt.executor.llms.MultiLLMPromptExecutor
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.executor.ollama.client.OllamaClient
import ai.koog.prompt.llm.LLMProvider
import xyz.block.trailblaze.http.DynamicLlmClient
import xyz.block.trailblaze.http.TrailblazeHttpClientFactory
import xyz.block.trailblaze.llm.TrailblazeLlmModel
import xyz.block.trailblaze.llm.TrailblazeLlmProvider
import xyz.block.trailblaze.llm.providers.TrailblazeDynamicLlmTokenProvider

/**
 * Creates LLM clients for host testing by dynamically fetching API tokens from a token provider.
 */
class TrailblazeHostDynamicLlmClientProvider(
  private val trailblazeLlmModel: TrailblazeLlmModel,
  val trailblazeDynamicLlmTokenProvider: TrailblazeDynamicLlmTokenProvider,
) : DynamicLlmClient {

  private val baseClient = TrailblazeHttpClientFactory.createInsecureTrustAllCertsHttpClient(
    timeoutInSeconds = 120,
  )

  private val llmClients = mutableMapOf<LLMProvider, LLMClient>(
    LLMProvider.Ollama to OllamaClient(),
  ).apply {
    trailblazeDynamicLlmTokenProvider.getApiTokenForProvider(
      TrailblazeLlmProvider.OPENAI,
    )?.let { openAiApiKey ->
      put(
        LLMProvider.OpenAI,
        OpenAILLMClient(
          baseClient = baseClient,
          apiKey = openAiApiKey,
        ),
      )
    }
  }

  override fun createPromptExecutor(): PromptExecutor = MultiLLMPromptExecutor(llmClients = llmClients)

  override fun createLlmClient(): LLMClient = llmClients
    .map { it.key.id to it.value }
    .toMap()[trailblazeLlmModel.trailblazeLlmProvider.id]
    ?: error("Unsupported provider ${trailblazeLlmModel.trailblazeLlmProvider}")
}
