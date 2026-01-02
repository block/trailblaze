package xyz.block.trailblaze.examples.rules

import ai.koog.prompt.executor.clients.openai.OpenAIClientSettings
import ai.koog.prompt.executor.clients.openai.OpenAILLMClient
import ai.koog.prompt.executor.clients.openrouter.OpenRouterLLMClient
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
    llmClients = buildMap {
      put(
        LLMProvider.Ollama,
        OllamaClient(baseClient = cachedLlmHttpClient)
      )
      put(
        LLMProvider.OpenAI,
        OpenAILLMClient(
          baseClient = cachedLlmHttpClient,
          apiKey = InstrumentationArgUtil.getInstrumentationArg("OPENAI_API_KEY") ?: "OPENAI_API_KEY NOT SET",
          settings = OpenAIClientSettings(
            baseUrl = OpenAiInstrumentationArgUtil.getBaseUrlFromInstrumentationArg(),
          )
        ),
      )
      put(
        LLMProvider.OpenRouter,
        OpenRouterLLMClient(
          baseClient = cachedLlmHttpClient,
          apiKey = InstrumentationArgUtil.getInstrumentationArg("OPENROUTER_API_KEY") ?: "OPENROUTER_API_KEY NOT SET",
        ),
      )
    },
  ) {
  companion object {
    private val cachedLlmHttpClient = TrailblazeHttpClientFactory.createInsecureTrustAllCertsHttpClient(
      timeoutInSeconds = 120,
      reverseProxyUrl = InstrumentationArgUtil.reverseProxyEndpoint(),
    )
  }
}