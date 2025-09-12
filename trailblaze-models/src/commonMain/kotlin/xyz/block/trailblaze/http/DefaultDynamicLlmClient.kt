package xyz.block.trailblaze.http

import ai.koog.prompt.executor.clients.LLMClient
import ai.koog.prompt.executor.llms.MultiLLMPromptExecutor
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLMProvider
import xyz.block.trailblaze.llm.TrailblazeLlmModel

open class DefaultDynamicLlmClient(
  val trailblazeLlmModel: TrailblazeLlmModel,
  protected val llmClients: Map<LLMProvider, LLMClient>,
) : DynamicLlmClient {

  override fun createPromptExecutor(): PromptExecutor = MultiLLMPromptExecutor(llmClients)

  override fun createLlmClient(): LLMClient = llmClients.entries
    .firstOrNull { it.key.id == trailblazeLlmModel.trailblazeLlmProvider.id }?.value
    ?: error("Unsupported provider ${trailblazeLlmModel.trailblazeLlmProvider}")
}
