package xyz.block.trailblaze.agent

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.prompt.dsl.ModerationResult
import ai.koog.prompt.Prompt
import ai.koog.prompt.executor.clients.LLMClient
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.LLMChoice
import ai.koog.prompt.message.Message
import ai.koog.prompt.streaming.StreamFrame
import kotlinx.coroutines.flow.Flow
import xyz.block.trailblaze.tracing.TrailblazeTracer.traceRecorder

/**
 * This is a delegating tracing wrapper around the LLMClient.
 */
class TracingLlmClient(private val delegate: LLMClient) : LLMClient() {

  /** Wraps the execution of the block with tracing. */
  private inline fun <T> traceLlmClient(name: String, block: () -> T): T = traceRecorder.trace(
    name = name,
    cat = "LlmClient",
    args = emptyMap(),
    block = block,
  )

  /**
   * Suspending [traceLlmClient], for the overrides that are themselves suspending.
   *
   * Not interchangeable with the non-suspending one. That variant publishes its span only to
   * [xyz.block.trailblaze.tracing.TraceSpanLocal], and an outgoing HTTP request reads its parent
   * from the coroutine context — the thread-local is unreliable there, because the ktor interceptor
   * may already have been dispatched to a thread whose frame belongs to someone else. Wrapping a
   * suspending call in the non-suspending variant therefore leaves every request this LLM call makes
   * parentless, and the network time stays inside `LlmClient.execute` self time instead of showing up
   * as the child that explains it.
   */
  private suspend inline fun <T> traceLlmClientSuspend(name: String, crossinline block: suspend () -> T): T =
    traceRecorder.traceSuspend(
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
  ): Message.Assistant = traceLlmClientSuspend("execute") {
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
  ): LLMChoice = traceLlmClientSuspend("executeMultipleChoices") {
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
  ): ModerationResult = traceLlmClientSuspend("moderate") {
    delegate.moderate(
      prompt = prompt,
      model = model,
    )
  }

  override fun close() = traceLlmClient("close") {
    delegate.close()
  }
}
