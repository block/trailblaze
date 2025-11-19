package xyz.block.trailblaze.agent

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.prompt.dsl.ModerationResult
import ai.koog.prompt.dsl.Prompt
import ai.koog.prompt.executor.clients.LLMClient
import ai.koog.prompt.executor.model.LLMChoice
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.Message
import ai.koog.prompt.streaming.StreamFrame
import kotlinx.coroutines.flow.Flow
import xyz.block.trailblaze.tracing.TrailblazeTracer.traceRecorder

/**
 * This is a delegating tracing wrapper around the LLMClient.
 */
class TracingLlmClient(private val delegate: LLMClient) : LLMClient {

  /** Wraps the execution of the block with tracing. */
  private inline fun <T> traceLlmClient(name: String, block: () -> T): T = traceRecorder.trace(
    name = name,
    cat = "LlmClient",
    args = emptyMap(),
    block = block,
  )

  override fun llmProvider(): LLMProvider = traceLlmClient(name = "llmProvider") {
    delegate.llmProvider()
  }

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

  override suspend fun executeMultipleChoices(
    prompt: Prompt,
    model: LLModel,
    tools: List<ToolDescriptor>,
  ): List<LLMChoice> = traceLlmClient("executeMultipleChoices") {
    delegate.executeMultipleChoices(prompt, model, tools)
  }

  override fun executeStreaming(
    prompt: Prompt,
    model: LLModel,
    tools: List<ToolDescriptor>,
  ): Flow<StreamFrame> = traceLlmClient("executeStreaming") {
    delegate.executeStreaming(prompt, model, tools)
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

  override fun close() = traceLlmClient("close") {
    delegate.close()
  }
}
