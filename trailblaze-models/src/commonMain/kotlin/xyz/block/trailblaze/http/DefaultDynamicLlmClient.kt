package xyz.block.trailblaze.http

import ai.koog.prompt.executor.clients.LLMClient
import ai.koog.prompt.executor.llms.MultiLLMPromptExecutor
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import xyz.block.trailblaze.llm.DynamicLlmConfig
import xyz.block.trailblaze.llm.LlmCapabilitiesUtil

open class DefaultDynamicLlmClient(
  dynamicLlmConfig: DynamicLlmConfig,
  protected val llmClients: Map<LLMProvider, LLMClient>,
  protected val llmProviderMap: Map<String, LLMProvider> = DynamicLlmClient.DEFAULT_LLM_PROVIDER_ID_TO_LLM_PROVIDER_MAP,
) : DynamicLlmClient {
  private val modelId: String = dynamicLlmConfig.modelId
  private val contextLength: Long = dynamicLlmConfig.contextLength
  private val providerId: String = dynamicLlmConfig.providerId
  private val capabilities = dynamicLlmConfig.capabilityIds.mapNotNull {
    LlmCapabilitiesUtil.capabilityFromString(it)
  }

  override fun createPromptExecutor(): PromptExecutor = MultiLLMPromptExecutor(llmClients)

  override fun createLlmModel() = LLModel(
    id = modelId,
    provider = llmProviderMap[providerId] ?: error("Unsupported provider $providerId"),
    capabilities = capabilities,
    contextLength = contextLength,
  )

  override fun createLlmClient(): LLMClient = llmClients.entries.firstOrNull { it.key.id == providerId }?.value
    ?: error("Unsupported provider $providerId")
}
