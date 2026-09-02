package xyz.block.trailblaze.agent

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.prompt.Prompt
import ai.koog.prompt.dsl.ModerationResult
import ai.koog.prompt.executor.clients.LLMClient
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.LLMChoice
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.ResponseMetaInfo
import ai.koog.prompt.streaming.StreamFrame
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test
import xyz.block.trailblaze.llm.TrailblazeLlmModels
import xyz.block.trailblaze.tracing.TRACING_JSON_INSTANCE
import xyz.block.trailblaze.tracing.TraceSpanContextElement
import xyz.block.trailblaze.tracing.TrailblazeTracer
import kotlin.coroutines.coroutineContext
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.time.Clock

/**
 * Where an outgoing HTTP request finds its parent when the agent calls the LLM.
 *
 * The ktor interceptor reads the enclosing span from the COROUTINE CONTEXT, not from the thread
 * local — by the time it runs it may already sit on a thread whose frame belongs to someone else,
 * and a confidently wrong parent is worse than none. So a suspending call wrapped in the
 * NON-suspending `trace` publishes its span somewhere the request will never look: the request comes
 * out a root, and its duration stays inside `LlmClient.execute` self time instead of appearing as
 * the child that explains it. This is the primary LLM path, so that is most of a run's network time.
 */
class TracingLlmClientSpanParentageTest {

  private val model: LLModel = TrailblazeLlmModels.CLAUDE_HAIKU.toKoogLlmModel()

  /** Stands in for the ktor interceptor: records the parent an outgoing request would name. */
  private class ParentObservingLlmClient : LLMClient() {
    var observedParentSpanId: String? = null

    override suspend fun execute(
      prompt: Prompt,
      model: LLModel,
      tools: List<ToolDescriptor>,
    ): Message.Assistant {
      observedParentSpanId = coroutineContext[TraceSpanContextElement.Key]?.spanId
      return Message.Assistant("ok", ResponseMetaInfo(Clock.System.now()))
    }

    override suspend fun executeMultipleChoices(
      prompt: Prompt,
      model: LLModel,
      tools: List<ToolDescriptor>,
    ): LLMChoice {
      observedParentSpanId = coroutineContext[TraceSpanContextElement.Key]?.spanId
      return listOf(Message.Assistant("ok", ResponseMetaInfo(Clock.System.now())))
    }

    override suspend fun moderate(prompt: Prompt, model: LLModel): ModerationResult {
      observedParentSpanId = coroutineContext[TraceSpanContextElement.Key]?.spanId
      return ModerationResult(isHarmful = false, categories = emptyMap())
    }

    override fun llmProvider(): LLMProvider = LLMProvider.Anthropic
    override fun executeStreaming(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>): Flow<StreamFrame> =
      throw UnsupportedOperationException("not exercised")

    override fun close() = Unit
  }

  private fun spanIdOf(name: String): String? {
    val events = TRACING_JSON_INSTANCE.decodeFromString<JsonArray>(TrailblazeTracer.exportJson())
    return events.map { it.jsonObject }
      .single { it["name"]?.jsonPrimitive?.content == name }["sid"]
      ?.jsonPrimitive?.content
  }

  @Test
  fun `a request made during execute names the execute span as its parent`() = runBlocking {
    TrailblazeTracer.clear()
    val delegate = ParentObservingLlmClient()

    TracingLlmClient(delegate).execute(Prompt.Empty, model, emptyList())

    val spanId = spanIdOf("execute")
    assertNotNull(spanId, "the execute span must be addressable to be nameable as a parent")
    assertEquals(spanId, delegate.observedParentSpanId)
  }

  @Test
  fun `executeMultipleChoices publishes its span the same way`() = runBlocking {
    TrailblazeTracer.clear()
    val delegate = ParentObservingLlmClient()

    TracingLlmClient(delegate).executeMultipleChoices(Prompt.Empty, model, emptyList())

    assertEquals(spanIdOf("executeMultipleChoices"), delegate.observedParentSpanId)
  }

  @Test
  fun `moderate publishes its span the same way`() = runBlocking {
    TrailblazeTracer.clear()
    val delegate = ParentObservingLlmClient()

    TracingLlmClient(delegate).moderate(Prompt.Empty, model)

    assertEquals(spanIdOf("moderate"), delegate.observedParentSpanId)
  }
}
