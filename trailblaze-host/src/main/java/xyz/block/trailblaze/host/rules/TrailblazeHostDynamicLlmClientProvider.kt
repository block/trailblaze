package xyz.block.trailblaze.host.rules

import ai.koog.prompt.executor.clients.LLMClient
import ai.koog.prompt.executor.llms.MultiLLMPromptExecutor
import ai.koog.prompt.executor.model.PromptExecutor
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

  private val llmClients: Map<LLMProvider, LLMClient> = buildMap {
    listOf(
      TrailblazeLlmProvider.OLLAMA,
      TrailblazeLlmProvider.OPENAI,
      TrailblazeLlmProvider.GOOGLE,
      TrailblazeLlmProvider.ANTHROPIC,
      TrailblazeLlmProvider.OPEN_ROUTER
    ).forEach { llmProvider ->
      trailblazeDynamicLlmTokenProvider.getLLMClientForProviderIfAvailable(
        llmProvider,
        baseClient
      )?.let { llmClient ->
        put(llmProvider.toKoogLlmProvider(), llmClient)
      }
    }
  }

  override fun createPromptExecutor(): PromptExecutor = MultiLLMPromptExecutor(llmClients = llmClients)

  override fun createLlmClient(): LLMClient = llmClients
    .map { it.key.id to it.value }
    .toMap()[trailblazeLlmModel.trailblazeLlmProvider.id]
    ?: error("Unsupported provider ${trailblazeLlmModel.trailblazeLlmProvider}")
}
