package xyz.block.trailblaze.mcp.agent

import ai.koog.agents.core.dsl.extension.ReceivedToolResults
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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import xyz.block.trailblaze.llm.TrailblazeLlmModels
import xyz.block.trailblaze.toolcalls.TrailblazeKoogTool
import xyz.block.trailblaze.toolcalls.TrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.ObjectiveStatusTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.Status
import kotlin.test.Test

/**
 * Runs the real strategy graph against a scripted model to pin how an `objectiveStatus` call ends —
 * or does not end — the objective. Three things ended it before and must not now: a status of
 * IN_PROGRESS, a status batched with other tool calls (which also dropped those calls unexecuted),
 * and a status the host's gate declines. Two verify steps that had done nothing but report
 * IN_PROGRESS were scoring as passes.
 */
class KoogObjectiveStatusRoutingGraphTest {

  @Serializable
  private class NoArgs : TrailblazeTool

  private val model = TrailblazeLlmModels.GPT_4O_MINI
  private val statusTool = KoogStrategyGraphAgent.OBJECTIVE_STATUS_TOOL_NAME

  /** One scripted response: the tool calls the model "makes" on that request, in order. */
  private data class Call(val tool: String, val args: String = "{}")

  private fun status(status: String, explanation: String = "x") =
    Call(statusTool, """{"status":"$status","explanation":"$explanation"}""")

  private class ScriptedLlmClient(
    private val provider: LLMProvider,
    private val script: (requestNumber: Int) -> List<Call>,
  ) : LLMClient() {
    val prompts = mutableListOf<Prompt>()
    override fun llmProvider(): LLMProvider = provider
    override suspend fun execute(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>): Message.Assistant {
      prompts += prompt
      val n = prompts.size
      return Message.Assistant(
        parts = script(n).mapIndexed { i, c -> MessagePart.Tool.Call(id = "call-$n-$i", tool = c.tool, args = c.args) },
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

  /** What the tools saw: the host dispatcher's side effects, stood in for. */
  private class Seen {
    var tapOnRuns = 0
    val statuses = mutableListOf<Status>()
  }

  private fun registry(seen: Seen) = ToolRegistry {
    tool(
      TrailblazeKoogTool(
        argsSerializer = NoArgs.serializer(),
        descriptor = ToolDescriptor(name = "tapOn", description = "tapOn", requiredParameters = emptyList()),
        executeTool = { seen.tapOnRuns++; "tapOn ran" },
      ),
    )
    tool(
      TrailblazeKoogTool(
        argsSerializer = ObjectiveStatusTrailblazeTool.serializer(),
        descriptor = ToolDescriptor(name = statusTool, description = statusTool, requiredParameters = emptyList()),
        executeTool = { args -> seen.statuses += args.status; "Recorded ${args.status}" },
      ),
    )
  }

  private fun Prompt.toolResultsSeen(): List<String> =
    messages.flatMap { it.parts }.filterIsInstance<MessagePart.Tool.Result>().map { it.tool }

  private fun run(
    seen: Seen,
    isObjectiveFinished: ((ReceivedToolResults) -> Boolean)? = null,
    script: (Int) -> List<Call>,
  ): ScriptedLlmClient {
    val client = ScriptedLlmClient(model.toKoogLlmModel().provider, script)
    val agent = if (isObjectiveFinished == null) {
      KoogStrategyGraphAgent.createInProcess(llmClient = client, llmModel = model, toolRegistry = registry(seen), maxLlmCalls = 10)
    } else {
      KoogStrategyGraphAgent.createInProcess(
        llmClient = client,
        llmModel = model,
        toolRegistry = registry(seen),
        maxLlmCalls = 10,
        isObjectiveFinished = isObjectiveFinished,
      )
    }
    runBlocking {
      try {
        agent.run("verify the thing")
      } finally {
        agent.close()
      }
    }
    return client
  }

  @Test
  fun `a lone COMPLETED ends the objective on the first turn`() {
    val seen = Seen()
    val client = run(seen) { listOf(status("COMPLETED")) }

    assertThat(client.prompts.size).isEqualTo(1)
    assertThat(seen.statuses).isEqualTo(listOf(Status.COMPLETED))
  }

  @Test
  fun `IN_PROGRESS keeps the loop going and its result is sent back to the model`() {
    val seen = Seen()
    val client = run(seen) { n -> if (n == 1) listOf(status("in_progress")) else listOf(status("COMPLETED")) }

    assertThat(client.prompts.size).isEqualTo(2)
    assertThat(seen.statuses).isEqualTo(listOf(Status.IN_PROGRESS, Status.COMPLETED))
    // The second request carried the IN_PROGRESS tool result, so the model saw it was still open.
    assertThat(client.prompts[1].toolResultsSeen()).isEqualTo(listOf(statusTool))
  }

  @Test
  fun `a status batched with another tool runs BOTH and does not end the objective`() {
    val seen = Seen()
    val client = run(seen) { n ->
      if (n == 1) listOf(Call("tapOn"), status("COMPLETED")) else listOf(status("COMPLETED"))
    }

    // Before: the completion edge matched on the status call, forwarded only it, and finished —
    // tapOn never ran.
    assertThat(seen.tapOnRuns).isEqualTo(1)
    assertThat(client.prompts.size).isEqualTo(2)
    assertThat(seen.statuses).isEqualTo(listOf(Status.COMPLETED, Status.COMPLETED))
    // Every call in the batch got a result back, so the transcript pairs up for the next request.
    assertThat(client.prompts[1].toolResultsSeen()).isEqualTo(listOf("tapOn", statusTool))
  }

  @Test
  fun `the host gate can decline a status the model considered final`() {
    // The host substitutes its own recorded outcome for the default arg-reading gate — this is how a
    // COMPLETED verify step with no passing assertion is sent back instead of ending the step.
    // The gate is a read of host state, not a counter: Koog evaluates each outgoing edge's condition
    // separately, so it is asked more than once per executed status.
    val seen = Seen()
    val client = run(seen, isObjectiveFinished = { seen.statuses.size >= 2 }) { listOf(status("COMPLETED")) }

    assertThat(client.prompts.size).isEqualTo(2)
    assertThat(seen.statuses).isEqualTo(listOf(Status.COMPLETED, Status.COMPLETED))
  }

  @Test
  fun `the default gate reads the status argument case-insensitively, as the tool does`() {
    val seen = Seen()
    val client = run(seen) { n -> if (n == 1) listOf(status("In_Progress")) else listOf(status("completed")) }

    assertThat(client.prompts.size).isEqualTo(2)
  }
}
