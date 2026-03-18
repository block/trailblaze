package xyz.block.trailblaze.examples.sampleapp

import ai.koog.prompt.executor.clients.openai.OpenAIClientSettings
import ai.koog.prompt.executor.clients.openai.OpenAILLMClient
import ai.koog.prompt.executor.clients.openrouter.OpenRouterLLMClient
import ai.koog.prompt.executor.ollama.client.OllamaClient
import ai.koog.prompt.llm.LLMProvider
import xyz.block.trailblaze.android.AndroidTrailblazeRule
import xyz.block.trailblaze.android.InstrumentationArgUtil
import xyz.block.trailblaze.android.openai.OpenAiInstrumentationArgUtil
import xyz.block.trailblaze.http.DefaultDynamicLlmClient
import xyz.block.trailblaze.http.TrailblazeHttpClientFactory
import xyz.block.trailblaze.llm.TrailblazeLlmModel
import xyz.block.trailblaze.llm.providers.OpenAITrailblazeLlmModelList
import xyz.block.trailblaze.llm.providers.OpenRouterTrailblazeLlmModelList

class SampleAppTrailblazeRule(
  trailblazeLlmModel: TrailblazeLlmModel =
    InstrumentationArgUtil.resolveTrailblazeLlmModel(
      "OPENROUTER_API_KEY" to OpenRouterTrailblazeLlmModelList.GPT_OSS_120B_FREE,
      "OPENAI_API_KEY" to OpenAITrailblazeLlmModelList.OPENAI_GPT_4_1,
    )
) :
  AndroidTrailblazeRule(
    llmClient =
      DefaultDynamicLlmClient(
          trailblazeLlmModel = trailblazeLlmModel,
          llmClients =
            buildMap {
              put(LLMProvider.Ollama, OllamaClient(baseClient = httpClient))
              put(
                LLMProvider.OpenAI,
                OpenAILLMClient(
                  baseClient = httpClient,
                  apiKey =
                    InstrumentationArgUtil.getInstrumentationArg("OPENAI_API_KEY")
                      ?: "OPENAI_API_KEY NOT SET",
                  settings =
                    OpenAIClientSettings(
                      baseUrl = OpenAiInstrumentationArgUtil.getBaseUrlFromInstrumentationArg()
                    ),
                ),
              )
              put(
                LLMProvider.OpenRouter,
                OpenRouterLLMClient(
                  baseClient = httpClient,
                  apiKey =
                    InstrumentationArgUtil.getInstrumentationArg("OPENROUTER_API_KEY")
                      ?: "OPENROUTER_API_KEY NOT SET",
                ),
              )
            },
        )
        .createLlmClient(),
    trailblazeLlmModel = trailblazeLlmModel,
  ) {
  companion object {
    private val httpClient =
      TrailblazeHttpClientFactory.createInsecureTrustAllCertsHttpClient(
        timeoutInSeconds = 120,
        reverseProxyUrl = InstrumentationArgUtil.reverseProxyEndpoint(),
      )
  }
}
