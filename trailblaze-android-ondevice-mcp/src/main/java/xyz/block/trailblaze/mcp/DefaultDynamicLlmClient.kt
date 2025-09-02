package xyz.block.trailblaze.mcp

import ai.koog.prompt.executor.clients.LLMClient
import ai.koog.prompt.executor.clients.openai.OpenAIClientSettings
import ai.koog.prompt.executor.clients.openai.OpenAILLMClient
import ai.koog.prompt.executor.ollama.client.OllamaClient
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import xyz.block.trailblaze.android.InstrumentationArgUtil
import xyz.block.trailblaze.android.openai.OpenAiInstrumentationArgUtil
import xyz.block.trailblaze.http.TrailblazeHttpClientFactory
import xyz.block.trailblaze.llm.DynamicLlmConfig
import xyz.block.trailblaze.llm.LlmCapabilitiesUtil

class DefaultDynamicLlmClient(
  dynamicLlmConfig: DynamicLlmConfig,
) : DynamicLlmClient {

  val modelId: String = dynamicLlmConfig.modelId
  val contextLength: Long = dynamicLlmConfig.contextLength
  val providerId: String = dynamicLlmConfig.providerId
  val capabilities = dynamicLlmConfig.capabilityIds.mapNotNull {
    LlmCapabilitiesUtil.capabilityFromString(it)
  }

  override fun createLlmModel() = LLModel(
    id = modelId,
    provider = when (providerId) {
      LLMProvider.Ollama.id -> LLMProvider.Ollama
      LLMProvider.OpenAI.id -> LLMProvider.OpenAI
      LLMProvider.Google.id -> LLMProvider.Google
      LLMProvider.Meta.id -> LLMProvider.Meta
      LLMProvider.OpenRouter.id -> LLMProvider.OpenRouter
      else -> {
        error("Unsupported provider $providerId")
      }
    },
    capabilities = capabilities,
    contextLength = contextLength,
  )

  override fun createLlmClient(
    timeoutInSeconds: Long,
  ): LLMClient {
    val baseClient = TrailblazeHttpClientFactory.createInsecureTrustAllCertsHttpClient(
      timeoutInSeconds = timeoutInSeconds,
      reverseProxyUrl = InstrumentationArgUtil.reverseProxyEndpoint(),
    )

    return when (providerId) {
      LLMProvider.OpenAI.id -> OpenAILLMClient(
        apiKey = OpenAiInstrumentationArgUtil.getApiKeyFromInstrumentationArg(),
        settings = OpenAIClientSettings(),
        baseClient = baseClient,
      )

      LLMProvider.Ollama.id -> OllamaClient(
        baseClient = baseClient,
      )

      else -> {
        error("Unsupported provider $providerId")
      }
    }
  }
}
