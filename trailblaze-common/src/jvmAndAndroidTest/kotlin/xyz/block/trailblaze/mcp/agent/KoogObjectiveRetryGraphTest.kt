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
import assertk.assertions.contains
import assertk.assertions.doesNotContain
import assertk.assertions.isEqualTo
import assertk.assertions.isNotNull
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
 * Runs the real strategy graph with a [KoogObjectiveRetryPolicy] against a scripted model. What has
 * to hold, because it is the whole point of using Koog's `subgraphWithRetry` rather than a loop of
 * our own: a rejected attempt is run again from a REWOUND conversation that carries the feedback and
 * none of the failed attempt's turns, on the same LLM-call budget, with the same advertised tools.
 */
class KoogObjectiveRetryGraphTest {

  @Serializable
  private class NoArgs : TrailblazeTool

  private val model = TrailblazeLlmModels.GPT_4O_MINI

  /** The stand-in for the host dispatcher's captured outcome: whatever the last objectiveStatus said. */
  private class Outcome {
    var last: String? = null
  }

  /**
   * Answers request N with the tool named by [toolForRequest]. Every prompt it is handed is kept so a
   * test can read what the model actually saw on each request.
   */
  private class ScriptedLlmClient(
    private val provider: LLMProvider,
    private val toolForRequest: (requestNumber: Int) -> String,
  ) : LLMClient() {
    val prompts = mutableListOf<Prompt>()
    val advertisedTools = mutableListOf<List<ToolDescriptor>>()
    override fun llmProvider(): LLMProvider = provider
    override suspend fun execute(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>): Message.Assistant {
      prompts += prompt
      advertisedTools += tools
      val n = prompts.size
      return Message.Assistant(
        parts = listOf(MessagePart.Tool.Call(id = "call-$n", tool = toolForRequest(n), args = "{}")),
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

  private fun tool(name: String, onRun: () -> Unit = {}) = TrailblazeKoogTool(
    argsSerializer = NoArgs.serializer(),
    descriptor = ToolDescriptor(name = name, description = name, requiredParameters = emptyList()),
    executeTool = { onRun(); "$name ran" },
  )

  /**
   * A registry whose objectiveStatus tool reports whatever [statusForCall] says for its Nth
   * invocation — the way the host's dispatcher captures COMPLETED/FAILED as a side effect.
   */
  private fun registry(outcome: Outcome, statusForCall: (call: Int) -> String) = ToolRegistry {
    tool(tool("tapOn"))
    var objectiveStatusCalls = 0
    tool(
      tool(KoogStrategyGraphAgent.OBJECTIVE_STATUS_TOOL_NAME) {
        objectiveStatusCalls++
        outcome.last = statusForCall(objectiveStatusCalls)
      },
    )
  }

  private fun policyFor(outcome: Outcome, maxRetries: Int = 1) = KoogObjectiveRetryPolicy(
    maxRetries = maxRetries,
    feedbackForRetry = { if (outcome.last == "FAILED") KoogObjectiveRetryPolicy.feedbackForFailedObjective("gave up") else null },
  )

  private fun Prompt.userTexts(): List<String> = messages.filterIsInstance<Message.User>()
    .map { m -> m.parts.filterIsInstance<MessagePart.Text>().joinToString("") { it.text } }

  private fun Prompt.toolCallsSeen(): List<String> = messages.filterIsInstance<Message.Assistant>()
    .flatMap { m -> m.parts.filterIsInstance<MessagePart.Tool.Call>().map { it.tool } }

  private fun runToCompletion(agent: KoogStrategyGraphAgent, objective: String) = runBlocking {
    try {
      agent.run(objective)
    } finally {
      agent.close()
    }
  }

  @Test
  fun `a FAILED first attempt is retried from a rewound conversation that carries the feedback`() {
    val outcome = Outcome()
    val instrumentation = KoogRunInstrumentation()
    // Attempt 1: tapOn, then FAILED. Attempt 2: COMPLETED straight away.
    val client = ScriptedLlmClient(model.toKoogLlmModel().provider) { request ->
      if (request == 2 || request == 3) KoogStrategyGraphAgent.OBJECTIVE_STATUS_TOOL_NAME else "tapOn"
    }
    val agent = KoogStrategyGraphAgent.createInProcess(
      llmClient = client,
      llmModel = model,
      toolRegistry = registry(outcome) { call -> if (call == 1) "FAILED" else "COMPLETED" },
      maxLlmCalls = 25,
      instrumentation = instrumentation,
      retryPolicy = policyFor(outcome),
    )

    runToCompletion(agent, "open the settings")

    assertThat(client.prompts.size).isEqualTo(3)
    assertThat(outcome.last).isEqualTo("COMPLETED")
    assertThat(instrumentation.objectiveRetries).isEqualTo(1)
    assertThat(instrumentation.llmCallsCompleted).isEqualTo(3)

    // The first attempt saw exactly the objective — Koog injected nothing of its own.
    assertThat(client.prompts[0].userTexts()).isEqualTo(listOf("open the settings"))
    // The retried attempt's first request: the feedback plus the objective, as user messages and
    // nothing else...
    val retryPrompt = client.prompts[2]
    val retryUserTexts = retryPrompt.userTexts()
    assertThat(retryUserTexts.size).isEqualTo(2)
    assertThat(retryUserTexts).contains("open the settings")
    assertThat(retryUserTexts.any { it.contains("objectiveStatus=FAILED") && it.contains("\"gave up\"") }).isEqualTo(true)
    // ...and NONE of the failed attempt's turns. That is the rewind — without it this would be a
    // conversation that already contains a tapOn and a FAILED report.
    assertThat(retryPrompt.toolCallsSeen()).isEqualTo(emptyList())
    // The first attempt's second request, for contrast, did carry its own earlier turn.
    assertThat(client.prompts[1].toolCallsSeen()).isEqualTo(listOf("tapOn"))
  }

  @Test
  fun `a second FAILED ends the run with that outcome instead of looping or throwing`() {
    val outcome = Outcome()
    val instrumentation = KoogRunInstrumentation()
    val client = ScriptedLlmClient(model.toKoogLlmModel().provider) { KoogStrategyGraphAgent.OBJECTIVE_STATUS_TOOL_NAME }
    val agent = KoogStrategyGraphAgent.createInProcess(
      llmClient = client,
      llmModel = model,
      toolRegistry = registry(outcome) { "FAILED" },
      maxLlmCalls = 25,
      instrumentation = instrumentation,
      retryPolicy = policyFor(outcome, maxRetries = 1),
    )

    runToCompletion(agent, "impossible thing")

    // One attempt, one retry, then the graph accepts the second FAILED as final.
    assertThat(client.prompts.size).isEqualTo(2)
    assertThat(outcome.last).isEqualTo("FAILED")
    assertThat(instrumentation.objectiveRetries).isEqualTo(1)
    assertThat(instrumentation.describeRun()).contains("1 retry(ies) after a FAILED report")
  }

  @Test
  fun `a COMPLETED first attempt is never retried`() {
    val outcome = Outcome()
    val instrumentation = KoogRunInstrumentation()
    val client = ScriptedLlmClient(model.toKoogLlmModel().provider) { KoogStrategyGraphAgent.OBJECTIVE_STATUS_TOOL_NAME }
    val agent = KoogStrategyGraphAgent.createInProcess(
      llmClient = client,
      llmModel = model,
      toolRegistry = registry(outcome) { "COMPLETED" },
      maxLlmCalls = 25,
      instrumentation = instrumentation,
      retryPolicy = policyFor(outcome),
    )

    runToCompletion(agent, "easy thing")

    assertThat(client.prompts.size).isEqualTo(1)
    assertThat(instrumentation.objectiveRetries).isEqualTo(0)
    assertThat(instrumentation.describeRun()).contains("0 retry(ies)")
  }

  @Test
  fun `without a policy a FAILED report ends the run on the first attempt`() {
    val outcome = Outcome()
    val client = ScriptedLlmClient(model.toKoogLlmModel().provider) { KoogStrategyGraphAgent.OBJECTIVE_STATUS_TOOL_NAME }
    val agent = KoogStrategyGraphAgent.createInProcess(
      llmClient = client,
      llmModel = model,
      toolRegistry = registry(outcome) { "FAILED" },
      maxLlmCalls = 25,
      retryPolicy = null,
    )

    runToCompletion(agent, "give up once")

    assertThat(client.prompts.size).isEqualTo(1)
  }

  @Test
  fun `the retry spends from the same LLM-call budget as the first attempt`() {
    // Budget 3: attempt 1 uses tapOn, tapOn, FAILED (3 calls). The retry's first request is call 4
    // and must be refused — the retry must not buy the objective a second budget.
    val outcome = Outcome()
    val instrumentation = KoogRunInstrumentation()
    val client = ScriptedLlmClient(model.toKoogLlmModel().provider) { request ->
      if (request == 3) KoogStrategyGraphAgent.OBJECTIVE_STATUS_TOOL_NAME else "tapOn"
    }
    val agent = KoogStrategyGraphAgent.createInProcess(
      llmClient = client,
      llmModel = model,
      toolRegistry = registry(outcome) { "FAILED" },
      maxLlmCalls = 3,
      instrumentation = instrumentation,
      retryPolicy = policyFor(outcome),
    )

    val thrown = runBlocking {
      try {
        assertFailsWith<MaxCallsLimitReachedException> { agent.run("budgeted") }
      } finally {
        agent.close()
      }
    }

    assertThat(client.prompts.size).isEqualTo(3)
    assertThat(instrumentation.objectiveRetries).isEqualTo(1)
    // The throw happened inside the retried attempt, and the attribution says so.
    val explanation = instrumentation.describeThrow(thrown)
    assertThat(explanation).contains("retried 1 time(s) after the model reported FAILED")
    assertThat(instrumentation.firstNodeFailure).isNotNull()
    assertThat(instrumentation.firstNodeFailure!!.nodeName).isEqualTo("callLlm")
  }

  @Test
  fun `a retried attempt advertises the tool surface the failed attempt switched to`() {
    // A toolset switch mutates the host's tool repo, which the rewind does not touch, while the
    // advertised list lives in the LLM context, which it does. The host marks the surface dirty
    // when a retry is granted; the loop's first request must then re-advertise from the repo, or
    // the retry sees the pre-switch menu and cannot call the tools it already activated.
    val outcome = Outcome()
    var surfaceDirty = false
    val base = listOf("tapOn", "switchTools", KoogStrategyGraphAgent.OBJECTIVE_STATUS_TOOL_NAME)
    val switched = (base + "newlyActiveTool").map { ToolDescriptor(name = it, description = it, requiredParameters = emptyList()) }
    val client = ScriptedLlmClient(model.toKoogLlmModel().provider) { n ->
      when (n) {
        1 -> "switchTools"
        else -> KoogStrategyGraphAgent.OBJECTIVE_STATUS_TOOL_NAME
      }
    }
    val registry = ToolRegistry {
      tool(tool("tapOn"))
      tool(tool("switchTools") { surfaceDirty = true })
      var objectiveStatusCalls = 0
      tool(
        tool(KoogStrategyGraphAgent.OBJECTIVE_STATUS_TOOL_NAME) {
          objectiveStatusCalls++
          outcome.last = if (objectiveStatusCalls == 1) "FAILED" else "COMPLETED"
        },
      )
    }
    val agent = KoogStrategyGraphAgent.createInProcess(
      llmClient = client,
      llmModel = model,
      toolRegistry = registry,
      maxLlmCalls = 25,
      onToolSurfaceRefresh = { if (surfaceDirty) { surfaceDirty = false; switched } else null },
      retryPolicy = KoogObjectiveRetryPolicy(
        feedbackForRetry = { if (outcome.last == "FAILED") KoogObjectiveRetryPolicy.feedbackForFailedObjective("gave up") else null },
        // The host's contract: a granted retry re-syncs the advertised surface from the repo.
        onRetry = { _, _ -> surfaceDirty = true },
      ),
    )

    runToCompletion(agent, "switch then retry")

    // Request 1 saw the base surface; request 2 (after the switch) the new one; request 3 opens the
    // retry and must see the new one too, not the base surface the rewind restored.
    assertThat(client.advertisedTools.size).isEqualTo(3)
    assertThat(client.advertisedTools[0].map { it.name }).doesNotContain("newlyActiveTool")
    assertThat(client.advertisedTools[1].map { it.name }).contains("newlyActiveTool")
    assertThat(client.advertisedTools[2].map { it.name }).contains("newlyActiveTool")
    assertThat(outcome.last).isEqualTo("COMPLETED")
  }

  @Test
  fun `a retried attempt advertises the same scoped tools as the first`() {
    // The verify-scope override is applied by a node inside the loop; a retry re-runs that node, so
    // the scope must survive the rewind rather than reverting to the full registry.
    val outcome = Outcome()
    val scoped = listOf(ToolDescriptor(name = KoogStrategyGraphAgent.OBJECTIVE_STATUS_TOOL_NAME, description = "finish", requiredParameters = emptyList()))
    val client = ScriptedLlmClient(model.toKoogLlmModel().provider) { KoogStrategyGraphAgent.OBJECTIVE_STATUS_TOOL_NAME }
    val agent = KoogStrategyGraphAgent.createInProcess(
      llmClient = client,
      llmModel = model,
      toolRegistry = registry(outcome) { call -> if (call == 1) "FAILED" else "COMPLETED" },
      maxLlmCalls = 25,
      initialAdvertisedTools = scoped,
      retryPolicy = policyFor(outcome),
    )

    runToCompletion(agent, "scoped")

    assertThat(client.advertisedTools.size).isEqualTo(2)
    assertThat(client.advertisedTools[0].map { it.name }).isEqualTo(listOf(KoogStrategyGraphAgent.OBJECTIVE_STATUS_TOOL_NAME))
    assertThat(client.advertisedTools[1].map { it.name }).isEqualTo(listOf(KoogStrategyGraphAgent.OBJECTIVE_STATUS_TOOL_NAME))
    assertThat(client.prompts[1].toolCallsSeen()).doesNotContain(KoogStrategyGraphAgent.OBJECTIVE_STATUS_TOOL_NAME)
  }
}
