package xyz.block.trailblaze.mcp.agent

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.prompt.Prompt
import ai.koog.prompt.dsl.ModerationResult
import ai.koog.prompt.executor.clients.LLMClient
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.LLMChoice
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.message.ResponseMetaInfo
import ai.koog.prompt.streaming.StreamFrame
import ai.koog.utils.time.KoogClock
import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isGreaterThan
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import xyz.block.trailblaze.exception.MaxCallsLimitReachedException
import xyz.block.trailblaze.llm.TrailblazeLlmModels
import xyz.block.trailblaze.toolcalls.TrailblazeKoogTool
import xyz.block.trailblaze.toolcalls.TrailblazeTool
import kotlin.test.Test
import kotlin.test.assertFailsWith

/**
 * Runs the real strategy graph against a scripted model to pin the budget's unit: the agent is cut
 * off after N **LLM requests**, not after N graph node executions. The loop runs three nodes per
 * request, so a budget enforced in Koog's `maxAgentIterations` would trip after roughly N/3 requests
 * and with Koog's own exception — the failure an A/B comparison produced on every multi-step
 * objective.
 */
class KoogStrategyGraphAgentBudgetTest {

  @Serializable
  private class NoArgs : TrailblazeTool

  private fun noArgsTool(name: String) = TrailblazeKoogTool(
    argsSerializer = NoArgs.serializer(),
    descriptor = ToolDescriptor(name = name, description = name, requiredParameters = emptyList()),
    executeTool = { "$name ran" },
  )

  private val model = TrailblazeLlmModels.GPT_4O_MINI

  /** Answers every request with one tool call, chosen by request number (1-based). */
  private class ScriptedLlmClient(
    private val provider: LLMProvider,
    private val toolForRequest: (requestNumber: Int) -> String,
  ) : LLMClient() {
    var requests = 0
      private set

    override fun llmProvider(): LLMProvider = provider
    override suspend fun execute(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>): Message.Assistant {
      requests++
      return Message.Assistant(
        parts = listOf(MessagePart.Tool.Call(id = "call-$requests", tool = toolForRequest(requests), args = "{}")),
        metaInfo = ResponseMetaInfo.create(KoogClock.System),
      )
    }
    override suspend fun executeMultipleChoices(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>): LLMChoice =
      listOf(execute(prompt, model, tools))
    override fun executeStreaming(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>): Flow<StreamFrame> =
      throw NotImplementedError()
    override suspend fun moderate(prompt: Prompt, model: LLModel): ModerationResult = throw NotImplementedError()
    override fun close() = Unit
  }

  private fun scripted(toolForRequest: (Int) -> String) =
    ScriptedLlmClient(model.toKoogLlmModel().provider, toolForRequest)

  private fun registry() = ToolRegistry {
    tool(noArgsTool("noop"))
    tool(noArgsTool(KoogStrategyGraphAgent.OBJECTIVE_STATUS_TOOL_NAME))
  }

  @Test
  fun `an objective that never finishes is cut off after exactly the budgeted number of LLM requests`() {
    val llm = scripted { "noop" }
    val agent = KoogStrategyGraphAgent.createInProcess(
      llmClient = llm,
      llmModel = model,
      toolRegistry = registry(),
      maxLlmCalls = 5,
    )

    val refused = runBlocking {
      try {
        assertFailsWith<MaxCallsLimitReachedException> { agent.run("keep going forever") }
      } finally {
        agent.close()
      }
    }

    assertThat(llm.requests).isEqualTo(5)
    assertThat(agent.llmCallsMade).isEqualTo(5)
    assertThat(refused.maxCalls).isEqualTo(5)
    assertThat(refused.objectivePrompt).isEqualTo("keep going forever")
  }

  @Test
  fun `the graph ceiling sits above the budget, so five requests cost more than five iterations and still run`() {
    // Documents WHY the ceiling is derived rather than equal to the budget: the loop below issues 5
    // requests, which is more than 5 node executions — an iteration limit of 5 would have thrown Koog's
    // own exception after the second request.
    assertThat(KoogStrategyGraphAgent.graphIterationCeiling(5)).isGreaterThan(5 * 3)
  }

  @Test
  fun `an objective that finishes inside the budget completes without spending the rest`() {
    val llm = scripted { request -> if (request == 3) KoogStrategyGraphAgent.OBJECTIVE_STATUS_TOOL_NAME else "noop" }
    val agent = KoogStrategyGraphAgent.createInProcess(
      llmClient = llm,
      llmModel = model,
      toolRegistry = registry(),
      maxLlmCalls = 25,
    )

    runBlocking {
      try {
        agent.run("finish on the third turn")
      } finally {
        agent.close()
      }
    }

    assertThat(llm.requests).isEqualTo(3)
    assertThat(agent.llmCallsMade).isEqualTo(3)
  }
}
