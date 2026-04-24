package xyz.block.trailblaze.http

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.prompt.dsl.ModerationResult
import ai.koog.prompt.dsl.Prompt
import ai.koog.prompt.executor.clients.LLMClient
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.Message
import ai.koog.prompt.streaming.StreamFrame
import kotlinx.coroutines.flow.Flow
import xyz.block.trailblaze.exception.TrailblazeException
import xyz.block.trailblaze.llm.TrailblazeLlmProvider

private const val NO_LLM_ERROR =
  "No LLM configured — this trail has a prompt step without a recording. " +
    "Configure a provider via 'trailblaze config llm <provider/model>' or add a recording."

/**
 * No-op [LLMClient] for [TrailblazeLlmProvider.NONE]. Every method throws [TrailblazeException]
 * so that recordings-only trails fail fast with a clear message if any step unexpectedly needs
 * live inference.
 */
class NoOpLlmClient : LLMClient() {
  override fun llmProvider(): LLMProvider = TrailblazeLlmProvider.NONE_KOOG_LLM_PROVIDER

  override suspend fun execute(
    prompt: Prompt,
    model: LLModel,
    tools: List<ToolDescriptor>,
  ): List<Message.Response> = throw TrailblazeException(NO_LLM_ERROR)

  override suspend fun executeMultipleChoices(
    prompt: Prompt,
    model: LLModel,
    tools: List<ToolDescriptor>,
  ): List<List<Message.Response>> = throw TrailblazeException(NO_LLM_ERROR)

  override fun executeStreaming(
    prompt: Prompt,
    model: LLModel,
    tools: List<ToolDescriptor>,
  ): Flow<StreamFrame> = throw TrailblazeException(NO_LLM_ERROR)

  override suspend fun moderate(
    prompt: Prompt,
    model: LLModel,
  ): ModerationResult = throw TrailblazeException(NO_LLM_ERROR)

  override fun close() = Unit
}
