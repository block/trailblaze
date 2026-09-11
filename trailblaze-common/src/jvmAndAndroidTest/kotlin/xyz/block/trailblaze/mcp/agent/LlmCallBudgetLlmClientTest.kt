package xyz.block.trailblaze.mcp.agent

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.prompt.Prompt
import ai.koog.prompt.dsl.ModerationResult
import ai.koog.prompt.executor.clients.LLMClient
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.LLMChoice
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.RequestMetaInfo
import ai.koog.prompt.message.ResponseMetaInfo
import ai.koog.prompt.params.LLMParams
import ai.koog.prompt.streaming.StreamFrame
import ai.koog.utils.time.KoogClock
import assertk.assertThat
import assertk.assertions.isEqualTo
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import xyz.block.trailblaze.exception.MaxCallsLimitReachedException
import xyz.block.trailblaze.llm.TrailblazeLlmModels
import xyz.block.trailblaze.llm.TrailblazeLlmProvider
import kotlin.test.Test
import kotlin.test.assertFailsWith

/**
 * Contract of [LlmCallBudgetLlmClient]: the budget is spent one unit per request that would reach
 * the model, the request that would exceed it is refused before it is forwarded, and the refusal is
 * the same exception the legacy runner throws so session-level handling is shared.
 */
class LlmCallBudgetLlmClientTest {

  /** Counts what actually reaches the model; the assertions are about this, not about the decorator's own counter. */
  private class CountingLlmClient : LLMClient() {
    var executes = 0
    var multipleChoices = 0
    var streams = 0
    var moderations = 0

    override fun llmProvider(): LLMProvider = TrailblazeLlmProvider.NONE_KOOG_LLM_PROVIDER
    override suspend fun execute(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>): Message.Assistant {
      executes++
      return Message.Assistant(content = "ok", metaInfo = ResponseMetaInfo.create(KoogClock.System))
    }
    override suspend fun executeMultipleChoices(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>): LLMChoice {
      multipleChoices++
      return emptyList()
    }
    override fun executeStreaming(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>): Flow<StreamFrame> {
      streams++
      return emptyFlow()
    }
    override suspend fun moderate(prompt: Prompt, model: LLModel): ModerationResult {
      moderations++
      return ModerationResult(isHarmful = false, categories = emptyMap())
    }
    override fun close() = Unit
  }

  private val model = TrailblazeLlmModels.GPT_4O_MINI.toKoogLlmModel()

  private fun prompt() = Prompt(
    messages = listOf(Message.User(content = "do the thing", metaInfo = RequestMetaInfo.create(KoogClock.System))),
    id = "test",
    params = LLMParams(temperature = null, speculation = null, schema = null),
  )

  @Test
  fun `forwards exactly the budgeted number of requests and refuses the next one before it reaches the model`() {
    val model_ = CountingLlmClient()
    val client = LlmCallBudgetLlmClient(delegate = model_, maxLlmCalls = 3)
    client.beginObjective("tap the login button")

    runBlocking {
      repeat(3) { client.execute(prompt(), model, emptyList()) }
      val refused = assertFailsWith<MaxCallsLimitReachedException> { client.execute(prompt(), model, emptyList()) }
      assertThat(refused.maxCalls).isEqualTo(3)
      assertThat(refused.objectivePrompt).isEqualTo("tap the login button")
    }

    // The refused request never left the decorator.
    assertThat(model_.executes).isEqualTo(3)
    assertThat(client.llmCallsMade).isEqualTo(3)
  }

  @Test
  fun `every generation request kind spends the same budget`() {
    val model_ = CountingLlmClient()
    val client = LlmCallBudgetLlmClient(delegate = model_, maxLlmCalls = 3)

    runBlocking {
      client.execute(prompt(), model, emptyList())
      client.executeMultipleChoices(prompt(), model, emptyList())
      client.executeStreaming(prompt(), model, emptyList())
      assertFailsWith<MaxCallsLimitReachedException> { client.execute(prompt(), model, emptyList()) }
    }

    assertThat(model_.executes + model_.multipleChoices + model_.streams).isEqualTo(3)
  }

  @Test
  fun `moderation is not a generation and does not spend the budget`() {
    val model_ = CountingLlmClient()
    val client = LlmCallBudgetLlmClient(delegate = model_, maxLlmCalls = 1)

    runBlocking {
      client.moderate(prompt(), model)
      client.moderate(prompt(), model)
      // The single budgeted generation is still available after two moderations.
      client.execute(prompt(), model, emptyList())
    }

    assertThat(model_.moderations).isEqualTo(2)
    assertThat(model_.executes).isEqualTo(1)
  }

  @Test
  fun `beginObjective resets the count and relabels the refusal`() {
    val model_ = CountingLlmClient()
    val client = LlmCallBudgetLlmClient(delegate = model_, maxLlmCalls = 1)

    runBlocking {
      client.beginObjective("first")
      client.execute(prompt(), model, emptyList())
      client.beginObjective("second")
      client.execute(prompt(), model, emptyList())
      val refused = assertFailsWith<MaxCallsLimitReachedException> { client.execute(prompt(), model, emptyList()) }
      assertThat(refused.objectivePrompt).isEqualTo("second")
    }

    assertThat(model_.executes).isEqualTo(2)
  }

  @Test
  fun `a non-positive budget is rejected at construction`() {
    assertFailsWith<IllegalArgumentException> { LlmCallBudgetLlmClient(delegate = CountingLlmClient(), maxLlmCalls = 0) }
  }
}
