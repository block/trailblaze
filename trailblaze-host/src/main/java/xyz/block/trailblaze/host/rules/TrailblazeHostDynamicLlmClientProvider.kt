package xyz.block.trailblaze.host.rules

import ai.koog.prompt.executor.clients.LLMClient
import ai.koog.prompt.executor.llms.MultiLLMPromptExecutor
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLMProvider
import io.ktor.client.HttpClient
import xyz.block.trailblaze.http.DynamicLlmClient
import xyz.block.trailblaze.http.NoOpLlmClient
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
  private val baseClient: HttpClient =
    TrailblazeHttpClientFactory.createInsecureTrustAllCertsHttpClient(timeoutInSeconds = 120),
) : DynamicLlmClient {

  private val llmClients: Map<LLMProvider, LLMClient> = buildMap {
    trailblazeDynamicLlmTokenProvider.supportedProviders().forEach { llmProvider ->
      trailblazeDynamicLlmTokenProvider.getLLMClientForProviderIfAvailable(
        llmProvider,
        baseClient
      )?.let { llmClient ->
        put(llmProvider.toKoogLlmProvider(), llmClient)
      }
    }
    put(TrailblazeLlmProvider.NONE.toKoogLlmProvider(), NoOpLlmClient())
  }

  override fun createPromptExecutor(): PromptExecutor = MultiLLMPromptExecutor(llmClients = llmClients)

  override fun createLlmClient(): LLMClient = llmClients
    .map { it.key.id to it.value }
    .toMap()[trailblazeLlmModel.trailblazeLlmProvider.id]
    ?: error("Unsupported provider ${trailblazeLlmModel.trailblazeLlmProvider}")
}
