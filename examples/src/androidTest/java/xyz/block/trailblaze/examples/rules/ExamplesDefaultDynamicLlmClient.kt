package xyz.block.trailblaze.examples.rules
import ai.koog.prompt.executor.clients.LLMClient
import ai.koog.prompt.executor.clients.openai.OpenAIClientSettings
import ai.koog.prompt.executor.clients.openai.OpenAILLMClient
import ai.koog.prompt.executor.ollama.client.OllamaClient
import ai.koog.prompt.llm.LLMProvider
import xyz.block.trailblaze.android.InstrumentationArgUtil
import xyz.block.trailblaze.android.openai.OpenAiInstrumentationArgUtil
import xyz.block.trailblaze.http.DefaultDynamicLlmClient
import xyz.block.trailblaze.http.DynamicLlmClient
import xyz.block.trailblaze.http.TrailblazeHttpClientFactory
import xyz.block.trailblaze.llm.TrailblazeLlmModel


class ExamplesDefaultDynamicLlmClient(trailblazeLlmModel: TrailblazeLlmModel) :
  DynamicLlmClient by DefaultDynamicLlmClient(
    trailblazeLlmModel = trailblazeLlmModel,
    llmClients = mutableMapOf<LLMProvider, LLMClient>(
      LLMProvider.Ollama to OllamaClient(baseClient = cachedLlmHttpClient),
    ).apply {
      InstrumentationArgUtil.getInstrumentationArg("OPENAI_API_KEY")?.let { openAiApiKey ->
        put(
          LLMProvider.OpenAI,
          OpenAILLMClient(
            baseClient = cachedLlmHttpClient,
            apiKey = openAiApiKey,
            settings = OpenAIClientSettings(
              baseUrl = OpenAiInstrumentationArgUtil.getBaseUrlFromInstrumentationArg(),
            )
          ),
        )
      }
    },
  ) {
  companion object {
    private val cachedLlmHttpClient = TrailblazeHttpClientFactory.createInsecureTrustAllCertsHttpClient(
      timeoutInSeconds = 600, // 10 minutes for large vision model requests on CI
      reverseProxyUrl = InstrumentationArgUtil.reverseProxyEndpoint(),
    )
  }
}