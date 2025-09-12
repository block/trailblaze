package xyz.block.trailblaze.http

import ai.koog.prompt.executor.clients.LLMClient
import ai.koog.prompt.executor.model.PromptExecutor
import xyz.block.trailblaze.llm.TrailblazeLlmProvider

/**
 * Interface to provide dynamic LLM client, model and prompt executor.
 */
interface DynamicLlmClient {
  fun createPromptExecutor(): PromptExecutor

  fun createLlmClient(): LLMClient

  companion object {
    val DEFAULT_LLM_PROVIDER_ID_TO_LLM_PROVIDER_MAP: Map<String, TrailblazeLlmProvider> =
      TrailblazeLlmProvider.ALL_PROVIDERS.associateBy { it.id }
  }
}
