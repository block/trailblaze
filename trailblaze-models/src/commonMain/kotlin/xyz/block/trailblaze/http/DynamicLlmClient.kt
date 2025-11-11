package xyz.block.trailblaze.http

import ai.koog.prompt.executor.clients.LLMClient
import ai.koog.prompt.executor.model.PromptExecutor

/**
 * Interface to provide dynamic LLM client, model and prompt executor.
 *
 * This allows us to have a non-static LLM client which can be sent a model and provider to use.
 */
interface DynamicLlmClient {
  fun createPromptExecutor(): PromptExecutor

  fun createLlmClient(): LLMClient
}
