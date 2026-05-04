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
    // Pre-existing contract for unrecorded steps (recording = null) is unchanged: replay
    // falls through to the AI runner. The previous variant of this test used
    // ToolRecording(emptyList()), which is now invalid construction (see ToolRecording's
    // init { require(...) } block); auto-satisfied is the explicit alternative for empty
    // tool recordings.
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

  @Test
  fun `auto-satisfied recording is treated as recorded and skipped without AI fallback`() = runBlocking {
    // Auto-satisfied recordings are explicit no-op recorded steps. They must NOT fall through
    // to the AI runner — they advance deterministically with zero tools fired.
    val runner = FakeTestAgentRunner(runSuspendReturn = { objectiveComplete("ai-ran") })
    val callbackInvocations = mutableListOf<List<TrailblazeTool>>()
    val util =
      TrailblazeRunnerUtil(
        runTrailblazeTool = { tools ->
          callbackInvocations += tools
          TrailblazeToolResult.Success()
        },
        trailblazeRunner = runner,
      )

    val step = DirectionStep(
      step = "Skip on this platform",
      recordable = true,
      recording = ToolRecording(tools = emptyList(), autoSatisfied = true),
    )

    val result =
      util.runPromptSuspend(prompts = listOf(step), useRecordedSteps = true, selfHeal = false)

    assertTrue(result is TrailblazeToolResult.Success)
    assertTrue(callbackInvocations.isEmpty(), "Auto-satisfied step should not fire any tools")
    assertTrue(
      runner.runSuspendCalls.isEmpty(),
      "Auto-satisfied step should not fall through to AI runSuspend; got ${runner.runSuspendCalls}",
    )
  }

  // --- helpers -----------------------------------------------------------------------------

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
