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
import xyz.block.trailblaze.exception.TrailheadException
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
import xyz.block.trailblaze.yaml.TrailheadDefinition

/**
 * Exercises the self-heal orchestration lifted into [TrailblazeRunnerUtil].
 *
 * Covers the per-tool replay loop, the `selfHeal = false` failure path (throws with context),
 * the `selfHeal = true` recovery path (delegates to [TestAgentRunner.recover]), and the
 * recording gate: `recording == null` falls through to AI, while a declared-but-empty
 * recording (an explicit no-op) replays zero tools and succeeds without calling AI.
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
  fun `declared-empty recording replays zero tools and succeeds without calling AI`() = runBlocking {
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
    val step = DirectionStep(step = "Nothing needed here", recordable = true, recording = ToolRecording(tools = emptyList()))

    val result =
      util.runPromptSuspend(
        prompts = listOf(step),
        useRecordedSteps = true,
        selfHeal = false,
      )

    assertTrue(result is TrailblazeToolResult.Success)
    assertTrue(callbackInvocations.isEmpty(), "an explicit no-op recording must dispatch zero tools")
    assertTrue(runner.runSuspendCalls.isEmpty(), "an explicit no-op recording must not fall through to AI")
    assertTrue(runner.recoverCalls.isEmpty())
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
  fun `recording FatalError with selfHeal=true throws immediately without recovery`() {
    // FatalError's contract is "abort the test immediately" — it marks dead infrastructure
    // (e.g. a non-recoverably wedged on-device server), not the UI drift self-heal exists
    // for. selfHeal=true must be overridden: throw, don't recover.
    val runner = FakeTestAgentRunner(recoverReturn = { _, _ -> objectiveComplete("recovered") })
    var session = newSession("session-fatal")
    val util =
      TrailblazeRunnerUtil(
        runTrailblazeTool = { _ ->
          TrailblazeToolResult.Error.FatalError(errorMessage = "on-device server wedged")
        },
        trailblazeRunner = runner,
        trailblazeLogger = TrailblazeLogger.createNoOp(),
        sessionProvider = { session },
        sessionUpdater = { session = it },
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
    // The fatal tool's error must survive into the terminal exception — downstream wedge
    // detection reads the session's terminal failure message.
    assertTrue((ex.message ?: "").contains("on-device server wedged"), ex.message ?: "")
    assertTrue(runner.recoverCalls.isEmpty(), "FatalError must not hand off to self-heal recovery")
    // No recovery ran, so the session must not be marked as having used self-heal.
    assertFalse(session.usedSelfHeal)
  }

  @Test
  fun `FatalError on step 1 aborts the remaining steps even with selfHeal=true`() {
    // The containment thesis of the fatal skip: without it, selfHeal would recover() the
    // fatal step and CONTINUE into step 2 against the same dead infrastructure. The throw
    // must abort the whole prompt list — step 2's recorded tool never fires.
    val attemptedTools = mutableListOf<List<TrailblazeTool>>()
    val runner = FakeTestAgentRunner(recoverReturn = { _, _ -> objectiveComplete("recovered") })
    var session = newSession("session-fatal-multistep")
    val util =
      TrailblazeRunnerUtil(
        runTrailblazeTool = { tools ->
          attemptedTools += tools
          TrailblazeToolResult.Error.FatalError(errorMessage = "on-device server wedged")
        },
        trailblazeRunner = runner,
        trailblazeLogger = TrailblazeLogger.createNoOp(),
        sessionProvider = { session },
        sessionUpdater = { session = it },
      )

    assertThrowsTrailblazeException {
      runBlocking {
        util.runPromptSuspend(
          prompts = listOf(
            recordedStep("Tap login", tool("tapLogin")),
            recordedStep("Type password", tool("inputPassword")),
          ),
          useRecordedSteps = true,
          selfHeal = true,
        )
      }
    }

    assertEquals(1, attemptedTools.size, "only step 1 may dispatch; step 2 must be aborted")
    assertTrue(runner.recoverCalls.isEmpty(), "no self-heal hand-off for a fatal step")
    assertTrue(runner.runSuspendCalls.isEmpty(), "step 2 must not fall through to AI either")
    assertFalse(session.usedSelfHeal)
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

  // --- trailhead fail-fast -----------------------------------------------------------------

  @Test
  fun `trailhead recording failure throws TrailheadException even with selfHeal=true`() {
    // The trailhead is the deterministic bootstrap that reaches the trail's starting state.
    // When it fails the trail never began, so selfHeal must NOT hand off to AI recovery -
    // that only masks the failure behind a later step's unrelated error.
    val runner = FakeTestAgentRunner(recoverReturn = { _, _ -> objectiveComplete("recovered") })
    val util =
      TrailblazeRunnerUtil(
        runTrailblazeTool = { _ ->
          TrailblazeToolResult.Error.ExceptionThrown(errorMessage = "app never signed in")
        },
        trailblazeRunner = runner,
      )
    val trailheadStep =
      TrailheadDefinition(
        step = "Launch the app signed in with the test account",
        tools = listOf(tool("launchSignedIn")),
      ).toPromptStep()

    val ex =
      assertFailsWith<TrailheadException> {
        runBlocking {
          util.runPromptSuspend(
            prompts = listOf(trailheadStep),
            useRecordedSteps = true,
            selfHeal = true,
          )
        }
      }
    val message = ex.message ?: ""
    // The first line must already say the trailhead failed and which tool failed - CI
    // summaries surface only the first line of a failure reason.
    val firstLine = message.lineSequence().first()
    assertTrue(firstLine.contains(TrailheadException.MESSAGE_PREFIX), message)
    assertTrue(firstLine.contains("launchSignedIn"), message)
    assertTrue(message.contains("Launch the app signed in with the test account"), message)
    // The full tool call (with args) must appear so triage sees exactly what was dispatched.
    assertTrue(message.contains(tool("launchSignedIn").trailblazeTool.toString()), message)
    assertTrue(message.contains("app never signed in"), message)
    assertTrue(runner.recoverCalls.isEmpty(), "a trailhead failure must not hand off to self-heal")
  }

  @Test
  fun `trailhead failure aborts the remaining trail steps`() {
    // Encodes the bug this guards against: a failed trailhead must not let the trail continue
    // and fail minutes later on an unrelated assertion against the wrong device state.
    val attemptedTools = mutableListOf<List<TrailblazeTool>>()
    val runner = FakeTestAgentRunner(recoverReturn = { _, _ -> objectiveComplete("recovered") })
    val util =
      TrailblazeRunnerUtil(
        runTrailblazeTool = { tools ->
          attemptedTools += tools
          TrailblazeToolResult.Error.ExceptionThrown(errorMessage = "app never signed in")
        },
        trailblazeRunner = runner,
      )
    val trailheadStep =
      TrailheadDefinition(step = "Launch signed in", tools = listOf(tool("launchSignedIn")))
        .toPromptStep()

    assertFailsWith<TrailheadException> {
      runBlocking {
        util.runPromptSuspend(
          prompts = listOf(trailheadStep, recordedStep("Verify the home tab", tool("assertHomeTab"))),
          useRecordedSteps = true,
          selfHeal = true,
        )
      }
    }

    assertEquals(1, attemptedTools.size, "the trail step must never dispatch after a trailhead failure")
    assertTrue(runner.recoverCalls.isEmpty())
    assertTrue(runner.runSuspendCalls.isEmpty())
  }

  @Test
  fun `NL-only trailhead AI failure throws TrailheadException`() {
    // A trailhead with no recorded tools blazes via AI; when the AI cannot reach the starting
    // state the same fail-loudly contract applies.
    val runner =
      FakeTestAgentRunner(
        runSuspendReturn = { step ->
          AgentTaskStatus.Failure.ObjectiveFailed(
            statusData = fakeStatusData(step.prompt),
            llmExplanation = "could not sign in",
          )
        },
      )
    val util =
      TrailblazeRunnerUtil(
        runTrailblazeTool = { _ -> TrailblazeToolResult.Success() },
        trailblazeRunner = runner,
      )
    val trailheadStep = TrailheadDefinition(step = "Launch signed in").toPromptStep()

    val ex =
      assertFailsWith<TrailheadException> {
        runBlocking {
          util.runPromptSuspend(
            prompts = listOf(trailheadStep),
            useRecordedSteps = true,
            selfHeal = true,
          )
        }
      }
    val message = ex.message ?: ""
    assertTrue(message.lineSequence().first().contains(TrailheadException.MESSAGE_PREFIX), message)
    assertTrue(message.contains("Launch signed in"), message)
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
