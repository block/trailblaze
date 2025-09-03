package xyz.block.trailblaze.http

import ai.koog.prompt.executor.clients.LLMClient
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel

/**
 * Interface to provide dynamic LLM client, model and prompt executor.
 */
interface DynamicLlmClient {
  fun createPromptExecutor(): PromptExecutor

  fun createLlmModel(): LLModel

  fun createLlmClient(): LLMClient

  companion object {
    val DEFAULT_LLM_PROVIDER_ID_TO_LLM_PROVIDER_MAP: Map<String, LLMProvider> = setOf(
      LLMProvider.Alibaba,
      LLMProvider.Anthropic,
      LLMProvider.Bedrock,
      LLMProvider.DeepSeek,
      LLMProvider.Google,
      LLMProvider.Meta,
      LLMProvider.Ollama,
      LLMProvider.OpenAI,
      LLMProvider.OpenRouter,
    ).associateBy { it.id }
  }
}
