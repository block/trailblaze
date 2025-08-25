package xyz.block.trailblaze.mcp

import ai.koog.prompt.executor.clients.LLMClient
import ai.koog.prompt.llm.LLModel

interface DynamicLlmClient {

  fun createLlmModel(): LLModel

  fun createLlmClient(
    timeoutInSeconds: Long = 120L,
  ): LLMClient
}
