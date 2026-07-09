package xyz.block.trailblaze.rules

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import xyz.block.trailblaze.agent.model.AgentTaskStatus
import xyz.block.trailblaze.agent.model.AgentTaskStatusData
import xyz.block.trailblaze.agent.model.PromptRecordingResult
import xyz.block.trailblaze.agent.model.PromptStepStatus
import xyz.block.trailblaze.api.ScreenState
import xyz.block.trailblaze.api.TestAgentRunner
import xyz.block.trailblaze.exception.TrailblazeException
import xyz.block.trailblaze.logs.client.TrailblazeLogger
import xyz.block.trailblaze.logs.client.TrailblazeSession
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.logs.model.TaskId
import xyz.block.trailblaze.toolcalls.ToolBatchScope
import xyz.block.trailblaze.toolcalls.TrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult
import xyz.block.trailblaze.toolcalls.commands.InputTextTrailblazeTool
import xyz.block.trailblaze.yaml.DirectionStep
import xyz.block.trailblaze.yaml.PromptStep
import xyz.block.trailblaze.yaml.ToolRecording
import xyz.block.trailblaze.yaml.TrailblazeToolYamlWrapper

/**
 * Exercises the self-heal orchestration lifted into [TrailblazeRunnerUtil].
 *
 * Covers the per-tool replay loop, the `selfHeal = false` failure path (throws with context),
 * the `selfHeal = true` recovery path (delegates to [TestAgentRunner.recover]), and the
 * tightened recording gate (empty tool list falls through to AI).
 */
class TrailblazeRunnerUtilTest {

  @Test
  fun `successful recording invokes callback per tool and returns Success`() = runBlocking {
    val callbackInvocations = mutableListOf<List<TrailblazeTool>>()
    val runner = FakeTestAgentRunner()
    val util =
      TrailblazeRunnerUtil(
        runTrailblazeTool = { tools ->
          callbackInvocations += tools
          TrailblazeToolResult.Success()
        },
        trailblazeRunner = runner,
      )

    val result =
      util.runPromptSuspend(
        prompts = listOf(recordedStep("Tap login", tool("tapLogin"), tool("inputPassword"))),
        useRecordedSteps = true,
        selfHeal = false,
      )

    assertTrue(result is TrailblazeToolResult.Success)
    // Per-tool iteration: each callback invocation gets exactly one tool.
    assertEquals(2, callbackInvocations.size)
    assertTrue(callbackInvocations.all { it.size == 1 })
    assertTrue(runner.recoverCalls.isEmpty())
    assertTrue(runner.runSuspendCalls.isEmpty())
  }

  @Test
  fun `recording failure with selfHeal=false throws with recorded tool context`() {
    val runner = FakeTestAgentRunner()
    val util =
      TrailblazeRunnerUtil(
        runTrailblazeTool = { tools ->
          if ((tools.single() as InputTextTrailblazeTool).text == "inputPassword") {
            TrailblazeToolResult.Error.ExceptionThrown(errorMessage = "selector broke")
          } else {
            TrailblazeToolResult.Success()
          }
        },
        trailblazeRunner = runner,
      )

    val step = recordedStep("Tap then type", tool("tapLogin"), tool("inputPassword"))

    val ex =
      assertThrowsTrailblazeException {
        runBlocking {
          util.runPromptSuspend(
            prompts = listOf(step),
            useRecordedSteps = true,
            selfHeal = false,
          )
        }
      }
    val message = ex.message ?: ""
    assertTrue(message.contains("Failed to run recording for prompt step"), message)
    assertTrue(message.contains("prompt: Tap then type"), message)
    assertTrue(message.contains("recorded tools: tapLogin, inputPassword"), message)
    assertTrue(message.contains("failed tool: inputPassword"), message)
    assertTrue(message.contains("selector broke"), message)
    assertTrue(runner.recoverCalls.isEmpty())
  }

  @Test
  fun `recording failure with selfHeal=true hands off to recover and succeeds`() = runBlocking {
    val runner = FakeTestAgentRunner(recoverReturn = { _, _ -> objectiveComplete("recovered") })
    val util =
      TrailblazeRunnerUtil(
        runTrailblazeTool = { _ ->
          TrailblazeToolResult.Error.ExceptionThrown(errorMessage = "selector broke")
        },
        trailblazeRunner = runner,
      )

    val step = recordedStep("Tap login", tool("tapLogin"))

    val result =
      util.runPromptSuspend(
        prompts = listOf(step),
        useRecordedSteps = true,
        selfHeal = true,
      )

    assertTrue(result is TrailblazeToolResult.Success)
    assertEquals(1, runner.recoverCalls.size)
    val (recoveredStep, failure) = runner.recoverCalls.single()
    assertEquals(step, recoveredStep)
    assertEquals("tapLogin", failure.failedTool.name)
  }

  @Test
  fun `recording failure with selfHeal=true throws when recovery also fails`() {
    val runner =
      FakeTestAgentRunner(
        recoverReturn = { _, _ ->
          AgentTaskStatus.Failure.ObjectiveFailed(
            statusData = fakeStatusData("Tap login"),
            llmExplanation = "still broken",
          )
        }
      )
    val util =
      TrailblazeRunnerUtil(
        runTrailblazeTool = { _ ->
          TrailblazeToolResult.Error.ExceptionThrown(errorMessage = "selector broke")
        },
        trailblazeRunner = runner,
      )

    val ex =
      assertThrowsTrailblazeException {
        runBlocking {
          util.runPromptSuspend(
            prompts = listOf(recordedStep("Tap login", tool("tapLogin"))),
            useRecordedSteps = true,
            selfHeal = true,
          )
        }
      }
    assertTrue(
      (ex.message ?: "").contains("Failed to successfully run prompt with AI"),
      ex.message ?: "",
    )
    assertEquals(1, runner.recoverCalls.size)
  }

  @Test
  fun `selfHeal=true marks session usedSelfHeal via sessionUpdater on recovery`() = runBlocking {
    val runner = FakeTestAgentRunner(recoverReturn = { _, _ -> objectiveComplete("recovered") })
    var session = newSession("session-marks-fallback")
    val util =
      TrailblazeRunnerUtil(
        runTrailblazeTool = { _ ->
          TrailblazeToolResult.Error.ExceptionThrown(errorMessage = "selector broke")
        },
        trailblazeRunner = runner,
        trailblazeLogger = TrailblazeLogger.createNoOp(),
        sessionProvider = { session },
        sessionUpdater = { session = it },
      )

    util.runPromptSuspend(
      prompts = listOf(recordedStep("Tap login", tool("tapLogin"))),
      useRecordedSteps = true,
      selfHeal = true,
    )

    assertTrue(session.usedSelfHeal)
  }

  @Test
  fun `session stays marked usedSelfHeal even when recovery also fails`() {
    val runner =
      FakeTestAgentRunner(
        recoverReturn = { _, _ ->
          AgentTaskStatus.Failure.ObjectiveFailed(
            statusData = fakeStatusData("Tap login"),
            llmExplanation = "still broken",
          )
        }
      )
    var session = newSession("session-recover-fail")
    val util =
      TrailblazeRunnerUtil(
        runTrailblazeTool = { _ ->
          TrailblazeToolResult.Error.ExceptionThrown(errorMessage = "selector broke")
        },
        trailblazeRunner = runner,
        trailblazeLogger = TrailblazeLogger.createNoOp(),
        sessionProvider = { session },
        sessionUpdater = { session = it },
      )

    assertThrowsTrailblazeException {
      runBlocking {
        util.runPromptSuspend(
          prompts = listOf(recordedStep("Tap login", tool("tapLogin"))),
          useRecordedSteps = true,
          selfHeal = true,
        )
      }
    }
    // Mark must land before we hand off to recover(), so SessionManager can still emit
    // FailedWithSelfHeal when the whole run ultimately errors out.
    assertTrue(session.usedSelfHeal)
  }

  @Test
  fun `usedSelfHeal persists across multiple prompts after first recovery`() = runBlocking {
    val failingTool = tool("fail")
    val passingTool = tool("pass")
    val runner = FakeTestAgentRunner(recoverReturn = { _, _ -> objectiveComplete("recovered") })
    var session = newSession("session-persist")
    val util =
      TrailblazeRunnerUtil(
        runTrailblazeTool = { tools ->
          if ((tools.single() as InputTextTrailblazeTool).text == "fail") {
            TrailblazeToolResult.Error.ExceptionThrown(errorMessage = "boom")
          } else {
            TrailblazeToolResult.Success()
          }
        },
        trailblazeRunner = runner,
        trailblazeLogger = TrailblazeLogger.createNoOp(),
        sessionProvider = { session },
        sessionUpdater = { session = it },
      )

    util.runPromptSuspend(
      prompts = listOf(
        recordedStep("First step", failingTool),
        recordedStep("Second step", passingTool),
      ),
      useRecordedSteps = true,
      selfHeal = true,
    )

    assertTrue(session.usedSelfHeal)
    assertEquals(1, runner.recoverCalls.size)
  }

  @Test
  fun `selfHeal=false does not mark session even on recording failure`() {
    var session = newSession("session-no-fallback")
    val util =
      TrailblazeRunnerUtil(
        runTrailblazeTool = { _ ->
          TrailblazeToolResult.Error.ExceptionThrown(errorMessage = "selector broke")
        },
        trailblazeRunner = FakeTestAgentRunner(),
        trailblazeLogger = TrailblazeLogger.createNoOp(),
        sessionProvider = { session },
        sessionUpdater = { session = it },
      )

    assertThrowsTrailblazeException {
      runBlocking {
        util.runPromptSuspend(
          prompts = listOf(recordedStep("Tap login", tool("tapLogin"))),
          useRecordedSteps = true,
          selfHeal = false,
        )
      }
    }
    assertFalse(session.usedSelfHeal)
  }

  @Test
  fun `cancelled coroutine context aborts recording playback at ensureActive`() {
    val attemptedTools = mutableListOf<List<TrailblazeTool>>()
    val util =
      TrailblazeRunnerUtil(
        runTrailblazeTool = { tools ->
          attemptedTools += tools
          TrailblazeToolResult.Success()
        },
        trailblazeRunner = FakeTestAgentRunner(),
      )
    val cancelledJob = Job().apply { cancel() }

    assertFailsWith<CancellationException> {
      runBlocking {
        withContext(cancelledJob) {
          util.runPromptSuspend(
            prompts = listOf(recordedStep("Tap login", tool("tapLogin"), tool("inputPassword"))),
            useRecordedSteps = true,
            selfHeal = false,
          )
        }
      }
    }
    // `ensureActive()` at the top of the per-tool loop throws before the first tool runs,
    // so the runTrailblazeTool callback should never see a tool.
    assertTrue(attemptedTools.isEmpty(), "Expected no tools to fire; got $attemptedTools")
  }

  @Test
  fun `null recording falls through to AI runSuspend`() = runBlocking {
    // A step without a `recording:` block falls through to the AI runner. Empty `ToolRecording`
    // instances are rejected at construction (see ToolRecording's init block) — to express
    // "no recording", omit the block entirely so this path triggers.
    val runner = FakeTestAgentRunner(runSuspendReturn = { objectiveComplete("ran") })
    val callbackInvocations = mutableListOf<List<TrailblazeTool>>()
    val util =
      TrailblazeRunnerUtil(
        runTrailblazeTool = { tools ->
          callbackInvocations += tools
          TrailblazeToolResult.Success()
        },
        trailblazeRunner = runner,
      )

    val step = DirectionStep(step = "Log in", recordable = true, recording = null)

    val result =
      util.runPromptSuspend(prompts = listOf(step), useRecordedSteps = true, selfHeal = false)

    assertTrue(result is TrailblazeToolResult.Success)
    assertTrue(callbackInvocations.isEmpty())
    assertEquals(listOf<PromptStep>(step), runner.runSuspendCalls)
  }

  // --- shared tool-batch scope -------------------------------------------------------------

  @Test
  fun `shared batch makes an earlier tool's context state visible to a later tool`() = runBlocking {
    // The set→paste round-trip only succeeds when both tools share one execution context (the
    // clipboard write from `set` must be visible to `paste`). With the batch bracket wired, they do.
    val runner = FakeTestAgentRunner()
    val util = clipboardModelUtil(runner, wireBatch = true)

    val result =
      util.runPromptSuspend(
        prompts = listOf(recordedStep("Set then paste", tool("set:HELLO"), tool("paste"))),
        useRecordedSteps = true,
        selfHeal = false,
      )

    assertTrue(result is TrailblazeToolResult.Success)
    assertTrue(runner.recoverCalls.isEmpty())
  }

  @Test
  fun `without a shared batch a later tool cannot observe an earlier tool's context state`() {
    // Documents why the batch is load-bearing: with each tool on its own context, `paste` reads a
    // fresh (empty) clipboard cache and the recording fails at the paste tool.
    val runner = FakeTestAgentRunner()
    val util = clipboardModelUtil(runner, wireBatch = false)

    val ex =
      assertThrowsTrailblazeException {
        runBlocking {
          util.runPromptSuspend(
            prompts = listOf(recordedStep("Set then paste", tool("set:HELLO"), tool("paste"))),
            useRecordedSteps = true,
            selfHeal = false,
          )
        }
      }
    val message = ex.message ?: ""
    assertTrue(message.contains("failed tool: paste"), message)
    assertTrue(message.contains("device clipboard is empty"), message)
  }

  @Test
  fun `shared batch preserves failed-tool attribution for self-heal`() = runBlocking {
    // The batch bracket must not swallow or blur which tool failed — self-heal still gets the exact
    // failed tool + successful prefix.
    val runner = FakeTestAgentRunner(recoverReturn = { _, _ -> objectiveComplete("recovered") })
    val util =
      TrailblazeRunnerUtil(
        runTrailblazeTool = { tools ->
          if ((tools.single() as InputTextTrailblazeTool).text == "boom") {
            TrailblazeToolResult.Error.ExceptionThrown(errorMessage = "kaboom")
          } else {
            TrailblazeToolResult.Success()
          }
        },
        trailblazeRunner = runner,
        sharedToolBatch = { block -> block() },
      )

    util.runPromptSuspend(
      prompts = listOf(recordedStep("Ok then boom", tool("ok"), tool("boom"))),
      useRecordedSteps = true,
      selfHeal = true,
    )

    assertEquals(1, runner.recoverCalls.size)
    val (_, failure) = runner.recoverCalls.single()
    assertEquals("boom", failure.failedTool.name)
    assertEquals(listOf("ok"), failure.successfulTools.map { it.name })
  }

  @Test
  fun `shared batch scope is torn down before self-heal recovery runs`() = runBlocking {
    // Wires the REAL ToolBatchScope (not a trivial pass-through bracket) so this proves the
    // production ordering: runRecordedTools' sharedToolBatch bracket wraps only the per-tool
    // replay loop, and recover() is invoked afterward in runRecordedPrompt — so a lingering
    // scope from the failed recording can't bleed into self-heal's own tool dispatches.
    var scopeActiveDuringRecovery: Boolean? = null
    val runner = FakeTestAgentRunner(
      recoverReturn = { _, _ ->
        scopeActiveDuringRecovery = ToolBatchScope.isActive()
        objectiveComplete("recovered")
      },
    )
    val util =
      TrailblazeRunnerUtil(
        runTrailblazeTool = { _ ->
          TrailblazeToolResult.Error.ExceptionThrown(errorMessage = "selector broke")
        },
        trailblazeRunner = runner,
        sharedToolBatch = { block ->
          ToolBatchScope.enter()
          try {
            block()
          } finally {
            ToolBatchScope.exit()
          }
        },
      )

    util.runPromptSuspend(
      prompts = listOf(recordedStep("Tap login", tool("tapLogin"))),
      useRecordedSteps = true,
      selfHeal = true,
    )

    assertEquals(1, runner.recoverCalls.size)
    assertEquals(false, scopeActiveDuringRecovery)
  }

  // --- helpers -----------------------------------------------------------------------------

  /**
   * Builds a [TrailblazeRunnerUtil] whose `runTrailblazeTool` models the real per-context clipboard
   * cache: a "context" carries an in-process clipboard, `set:<text>` writes to it, and `paste` reads
   * it (erroring when empty, like [xyz.block.trailblaze.toolcalls.commands.PasteClipboardTrailblazeTool]
   * on an empty clipboard). When [wireBatch] is true, one cache is installed for the whole recording
   * (the shared-batch scope); when false, each tool dispatch gets a fresh cache (a fresh context).
   */
  private fun clipboardModelUtil(
    runner: TestAgentRunner,
    wireBatch: Boolean,
  ): TrailblazeRunnerUtil {
    var batchCache: MutableList<String>? = null
    val runTool: (List<TrailblazeTool>) -> TrailblazeToolResult = { tools ->
      val cache = batchCache ?: mutableListOf() // fresh per-call cache when not inside a batch
      when (val cmd = (tools.single() as InputTextTrailblazeTool).text) {
        "paste" ->
          if (cache.isEmpty()) {
            TrailblazeToolResult.Error.ExceptionThrown(errorMessage = "device clipboard is empty")
          } else {
            TrailblazeToolResult.Success()
          }
        else -> {
          if (cmd.startsWith("set:")) cache.add(cmd.removePrefix("set:"))
          TrailblazeToolResult.Success()
        }
      }
    }
    val batch: (suspend (suspend () -> PromptRecordingResult) -> PromptRecordingResult)? =
      if (wireBatch) {
        { block ->
          batchCache = mutableListOf()
          try {
            block()
          } finally {
            batchCache = null
          }
        }
      } else {
        null
      }
    return TrailblazeRunnerUtil(
      runTrailblazeTool = runTool,
      trailblazeRunner = runner,
      sharedToolBatch = batch,
    )
  }

  private fun tool(name: String): TrailblazeToolYamlWrapper =
    TrailblazeToolYamlWrapper(name = name, trailblazeTool = InputTextTrailblazeTool(text = name))

  private fun recordedStep(prompt: String, vararg tools: TrailblazeToolYamlWrapper): PromptStep =
    DirectionStep(step = prompt, recordable = true, recording = ToolRecording(tools.toList()))

  private fun objectiveComplete(explanation: String): AgentTaskStatus.Success.ObjectiveComplete =
    AgentTaskStatus.Success.ObjectiveComplete(
      statusData = fakeStatusData(explanation),
      llmExplanation = explanation,
    )

  private fun newSession(id: String): TrailblazeSession =
    TrailblazeSession(sessionId = SessionId(id), startTime = Clock.System.now())

  private fun fakeStatusData(prompt: String): AgentTaskStatusData =
    AgentTaskStatusData(
      taskId = TaskId.generate(),
      prompt = prompt,
      callCount = 0,
      taskStartTime = Clock.System.now(),
      totalDurationMs = 0L,
    )

  private inline fun assertThrowsTrailblazeException(block: () -> Unit): TrailblazeException {
    try {
      block()
    } catch (e: TrailblazeException) {
      return e
    }
    error("Expected TrailblazeException, none thrown")
  }

  private class FakeTestAgentRunner(
    private val runSuspendReturn:
      (PromptStep) -> AgentTaskStatus =
      { step ->
        AgentTaskStatus.Success.ObjectiveComplete(
          statusData =
            AgentTaskStatusData(
              taskId = TaskId.generate(),
              prompt = step.prompt,
              callCount = 0,
              taskStartTime = Clock.System.now(),
              totalDurationMs = 0L,
            ),
          llmExplanation = "fake",
        )
      },
    private val recoverReturn:
      (PromptStep, PromptRecordingResult.Failure) -> AgentTaskStatus =
      { _, _ -> error("recover not stubbed") },
  ) : TestAgentRunner {
    override val screenStateProvider: () -> ScreenState = { error("screen state not stubbed") }
    val runSuspendCalls: MutableList<PromptStep> = mutableListOf()
    val recoverCalls: MutableList<Pair<PromptStep, PromptRecordingResult.Failure>> = mutableListOf()

    override fun run(prompt: PromptStep, stepStatus: PromptStepStatus): AgentTaskStatus =
      runSuspendReturn(prompt).also { runSuspendCalls += prompt }

    override suspend fun runSuspend(
      prompt: PromptStep,
      stepStatus: PromptStepStatus,
    ): AgentTaskStatus = runSuspendReturn(prompt).also { runSuspendCalls += prompt }

    override fun recover(
      promptStep: PromptStep,
      recordingResult: PromptRecordingResult.Failure,
    ): AgentTaskStatus =
      recoverReturn(promptStep, recordingResult).also {
        recoverCalls += promptStep to recordingResult
      }

    override fun appendToSystemPrompt(context: String) = Unit
  }
}
