package xyz.block.trailblaze.agent

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.prompt.dsl.ModerationResult
import ai.koog.prompt.dsl.Prompt
import ai.koog.prompt.executor.clients.LLMClient
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.Message
import kotlinx.coroutines.flow.Flow
import xyz.block.trailblaze.tracing.TrailblazeTracer.traceRecorder

/**
 * This is a delegating tracing wrapper around the LLMClient.
 */
class TracingLlmClient(private val delegate: LLMClient) : LLMClient {

  private inline fun <T> traceLlmClient(name: String, block: () -> T): T = traceRecorder.trace(name, "LlmClient", emptyMap(), block)

  override suspend fun execute(
    prompt: Prompt,
    model: LLModel,
    tools: List<ToolDescriptor>,
  ): List<Message.Response> = traceLlmClient("execute") {
    delegate.execute(
      prompt = prompt,
      model = model,
      tools = tools,
    )
  }

  override fun executeStreaming(
    prompt: Prompt,
    model: LLModel,
  ): Flow<String> = traceLlmClient("executeStreaming") {
    delegate.executeStreaming(
      prompt = prompt,
      model = model,
    )
  }

  override suspend fun moderate(
    prompt: Prompt,
    model: LLModel,
  ): ModerationResult = traceLlmClient("moderate") {
    delegate.moderate(
      prompt = prompt,
      model = model,
    )
  }
}
