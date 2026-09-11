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
import assertk.assertions.isNotNull
import assertk.assertions.isNull
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
 * Runs the real strategy graph against a scripted model to prove the instrumentation is actually
 * wired to Koog, not just well-formatted. Without this, [KoogRunInstrumentationTest] would pass
 * against an agent that installs no features at all.
 *
 * What has to hold: the LLM counters move, the tokenizer produces a real estimate, and a run that
 * throws names the node it threw in — the whole reason the feature is installed.
 */
class KoogRunInstrumentationEventsTest {

  @Serializable
  private class NoArgs : TrailblazeTool

  private fun noArgsTool(name: String) = TrailblazeKoogTool(
    argsSerializer = NoArgs.serializer(),
    descriptor = ToolDescriptor(name = name, description = name, requiredParameters = emptyList()),
    executeTool = { "$name ran" },
  )

  private val model = TrailblazeLlmModels.GPT_4O_MINI

  /**
   * Answers every request with one tool call, chosen by request number (1-based). [failOnRequest]
   * makes that request throw instead, standing in for a transport or provider error.
   */
  private class ScriptedLlmClient(
    private val provider: LLMProvider,
    private val failOnRequest: Int? = null,
    private val toolForRequest: (requestNumber: Int) -> String,
  ) : LLMClient() {
    override fun llmProvider(): LLMProvider = provider
    override suspend fun execute(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>): Message.Assistant {
      requests++
      if (requests == failOnRequest) throw IllegalStateException("connection reset by peer")
      return Message.Assistant(
        parts = listOf(MessagePart.Tool.Call(id = "call-$requests", tool = toolForRequest(requests), args = "{}")),
        metaInfo = ResponseMetaInfo.create(KoogClock.System),
      )
    }
    var requests = 0
      private set
    override suspend fun executeMultipleChoices(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>): LLMChoice =
      listOf(execute(prompt, model, tools))
    override fun executeStreaming(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>): Flow<StreamFrame> =
      throw NotImplementedError()
    override suspend fun moderate(prompt: Prompt, model: LLModel): ModerationResult = throw NotImplementedError()
    override fun close() = Unit
  }

  private fun registry() = ToolRegistry {
    tool(noArgsTool("noop"))
    tool(
      TrailblazeKoogTool(
        argsSerializer = NoArgs.serializer(),
        descriptor = ToolDescriptor(name = "brokenTool", description = "throws", requiredParameters = emptyList()),
        executeTool = { throw IllegalStateException("no such element") },
      ),
    )
    tool(noArgsTool(KoogStrategyGraphAgent.OBJECTIVE_STATUS_TOOL_NAME))
  }

  private fun agentWith(
    instrumentation: KoogRunInstrumentation?,
    maxLlmCalls: Int,
    failOnRequest: Int? = null,
    toolForRequest: (Int) -> String,
  ) = KoogStrategyGraphAgent.createInProcess(
    llmClient = ScriptedLlmClient(model.toKoogLlmModel().provider, failOnRequest, toolForRequest),
    llmModel = model,
    toolRegistry = registry(),
    maxLlmCalls = maxLlmCalls,
    instrumentation = instrumentation,
  )

  @Test
  fun `an objective that completes reports the LLM rounds it actually spent`() {
    val instrumentation = KoogRunInstrumentation()
    val agent = agentWith(instrumentation, maxLlmCalls = 25) { request ->
      if (request == 3) KoogStrategyGraphAgent.OBJECTIVE_STATUS_TOOL_NAME else "noop"
    }

    runBlocking {
      try {
        agent.run("finish on the third turn")
      } finally {
        agent.close()
      }
    }

    assertThat(instrumentation.llmCallsStarted).isEqualTo(3)
    assertThat(instrumentation.llmCallsCompleted).isEqualTo(3)
    assertThat(instrumentation.nodeFailureCount).isEqualTo(0)
    assertThat(instrumentation.firstNodeFailure).isNull()
  }

  @Test
  fun `the tokenizer feature produces a real prompt estimate that grows with the conversation`() {
    // The value a token-based compression gate would read. It has to come from the installed
    // MessageTokenizer — a NoTokenizer (Koog's default) would report 0 forever and the gate would
    // never fire.
    val instrumentation = KoogRunInstrumentation()
    val agent = agentWith(instrumentation, maxLlmCalls = 25) { request ->
      if (request == 4) KoogStrategyGraphAgent.OBJECTIVE_STATUS_TOOL_NAME else "noop"
    }

    runBlocking {
      try {
        agent.run("a fairly wordy objective so the very first prompt is not trivially small")
      } finally {
        agent.close()
      }
    }

    assertThat(instrumentation.peakEstimatedPromptTokens).isNotNull()
    assertThat(instrumentation.peakEstimatedPromptTokens!!).isGreaterThan(0)
  }

  @Test
  fun `an exhausted budget is attributed to the node that issued the refused request`() {
    // The failure an A/B comparison sees most, and the one that used to report only "ended without
    // reporting a status" with a call count of zero.
    val instrumentation = KoogRunInstrumentation()
    val agent = agentWith(instrumentation, maxLlmCalls = 5) { "noop" }

    val thrown = runBlocking {
      try {
        assertFailsWith<MaxCallsLimitReachedException> { agent.run("keep going forever") }
      } finally {
        agent.close()
      }
    }

    val failure = instrumentation.firstNodeFailure
    assertThat(failure).isNotNull()
    // The follow-up request node, not the first one — proof the attribution is the node that was
    // actually running when the budget ran out, rather than a fixed label.
    assertThat(failure!!.nodeName).isEqualTo("sendToolResults")
    assertThat(failure.errorType).isEqualTo("MaxCallsLimitReachedException")
    assertThat(instrumentation.llmCallsCompleted).isEqualTo(5)

    val explanation = instrumentation.describeThrow(thrown)
    assertThat(explanation.contains(failure.nodeName)).isEqualTo(true)
    assertThat(explanation.contains("5 LLM call(s)")).isEqualTo(true)
  }

  @Test
  fun `a failure on the very first request is attributed to the first request node`() {
    // Paired with the exhausted-budget case above, which lands on `sendToolResults`: together they
    // show the node name tracks where the run actually broke.
    val instrumentation = KoogRunInstrumentation()
    val agent = agentWith(instrumentation, maxLlmCalls = 25, failOnRequest = 1) { "noop" }

    val thrown = runBlocking {
      try {
        assertFailsWith<IllegalStateException> { agent.run("fail immediately") }
      } finally {
        agent.close()
      }
    }

    assertThat(instrumentation.firstNodeFailure!!.nodeName).isEqualTo("callLlm")
    assertThat(instrumentation.llmCallsStarted).isEqualTo(1)
    assertThat(instrumentation.llmCallsCompleted).isEqualTo(0)
    assertThat(instrumentation.describeThrow(thrown).contains("connection reset by peer")).isEqualTo(true)
  }

  @Test
  fun `a tool that throws is recorded as recovered context, and the objective still completes`() {
    // Koog hands a failed tool call back to the model as a result rather than throwing, so this is
    // an objective that SUCCEEDS despite a failed tool. Recording it as the attribution would put a
    // spurious cause on the next unrelated failure.
    //
    // This is the path for a tool that throws out to Koog. A DRIVER tool never does — its failure is
    // normalized into result text before Koog sees it, and is recorded by `describeToolDispatch`
    // instead (see KoogStrategyGraphHostRunnerTest).
    val instrumentation = KoogRunInstrumentation()
    val agent = agentWith(instrumentation, maxLlmCalls = 25) { request ->
      when (request) {
        1 -> "brokenTool"
        else -> KoogStrategyGraphAgent.OBJECTIVE_STATUS_TOOL_NAME
      }
    }

    runBlocking {
      try {
        agent.run("call the broken tool, then finish")
      } finally {
        agent.close()
      }
    }

    assertThat(instrumentation.recoveredToolFailureCount).isEqualTo(1)
    assertThat(instrumentation.lastRecoveredToolFailure!!.toolName).isEqualTo("brokenTool")
    assertThat(instrumentation.firstNodeFailure).isNull()
    assertThat(instrumentation.nodeFailureCount).isEqualTo(0)
  }

  @Test
  fun `passing no instrumentation installs no features and still runs`() {
    // The default for every caller that doesn't want the record — the agent must be unchanged.
    val agent = agentWith(instrumentation = null, maxLlmCalls = 25) { request ->
      if (request == 2) KoogStrategyGraphAgent.OBJECTIVE_STATUS_TOOL_NAME else "noop"
    }

    runBlocking {
      try {
        agent.run("finish on the second turn")
      } finally {
        agent.close()
      }
    }

    assertThat(agent.llmCallsMade).isEqualTo(2)
  }
}
