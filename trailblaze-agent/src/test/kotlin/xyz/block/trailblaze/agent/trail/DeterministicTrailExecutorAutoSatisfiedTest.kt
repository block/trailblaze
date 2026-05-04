package xyz.block.trailblaze.agent.trail

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.block.trailblaze.agent.ExecutionResult
import xyz.block.trailblaze.agent.UiActionExecutor
import xyz.block.trailblaze.api.ScreenState
import xyz.block.trailblaze.logs.model.TraceId
import xyz.block.trailblaze.toolcalls.commands.InputTextTrailblazeTool
import xyz.block.trailblaze.yaml.DirectionStep
import xyz.block.trailblaze.yaml.PromptStep
import xyz.block.trailblaze.yaml.ToolRecording
import xyz.block.trailblaze.yaml.TrailblazeToolYamlWrapper

/**
 * End-to-end integration test for the auto_satisfied recording marker.
 *
 * Drives the real [DeterministicTrailExecutor] with a counting [UiActionExecutor] stub and
 * proves three things:
 *
 *  1. An auto_satisfied step advances trail state without invoking any tool.
 *  2. A trail mixing recorded steps and auto_satisfied steps completes successfully and only
 *     the recorded steps' tools are executed.
 *  3. A recording with empty tools that is NOT auto_satisfied is now rejected at construction
 *     (the bot-flagged regression — see PR #2671 review).
 */
class DeterministicTrailExecutorAutoSatisfiedTest {

  /** Fake executor that records every tool name it was asked to run and returns Success. */
  private class CountingExecutor : UiActionExecutor {
    val executedTools = mutableListOf<String>()

    override suspend fun execute(toolName: String, args: JsonObject, traceId: TraceId?): ExecutionResult {
      executedTools.add(toolName)
      return ExecutionResult.Success(screenSummaryAfter = "fake", durationMs = 1L)
    }

    override suspend fun captureScreenState(): ScreenState? = null
  }

  @Test
  fun autoSatisfiedStepIsSkippedWithoutInvokingAnyTool() = runBlocking {
    val executor = CountingExecutor()
    val deterministic = DeterministicTrailExecutor(executor = executor)

    val steps: List<PromptStep> = listOf(
      DirectionStep(
        step = "Confirm closing the dialog",
        recording = ToolRecording(tools = emptyList(), autoSatisfied = true),
      ),
    )

    val result = deterministic.execute(steps)

    assertTrue("trail should succeed", result.success)
    assertEquals("no tools should have been invoked", 0, executor.executedTools.size)
    assertEquals(
      "auto_satisfied step should be marked completed",
      setOf(0),
      result.state.completedSteps,
    )
  }

  @Test
  fun autoSatisfiedMixedWithRecordedStepsExecutesOnlyTheRecordedTools() = runBlocking {
    val executor = CountingExecutor()
    val deterministic = DeterministicTrailExecutor(executor = executor)

    val steps: List<PromptStep> = listOf(
      DirectionStep(
        step = "Type a username",
        recording = ToolRecording(
          tools = listOf(
            TrailblazeToolYamlWrapper(
              name = "inputText",
              trailblazeTool = InputTextTrailblazeTool(text = "alice"),
            ),
          ),
          autoSatisfied = false,
        ),
      ),
      DirectionStep(
        step = "Confirm sign-in already happened",
        recording = ToolRecording(tools = emptyList(), autoSatisfied = true),
      ),
      DirectionStep(
        step = "Type a password",
        recording = ToolRecording(
          tools = listOf(
            TrailblazeToolYamlWrapper(
              name = "inputText",
              trailblazeTool = InputTextTrailblazeTool(text = "secret"),
            ),
          ),
          autoSatisfied = false,
        ),
      ),
    )

    val result = deterministic.execute(steps)

    assertTrue("trail should succeed end-to-end", result.success)
    assertEquals(
      "only the two recorded steps should have invoked their tools",
      listOf("inputText", "inputText"),
      executor.executedTools,
    )
    assertEquals(
      "all three steps should be marked completed (the auto_satisfied one too)",
      setOf(0, 1, 2),
      result.state.completedSteps,
    )
  }

  @Test
  fun emptyRecordingWithoutAutoSatisfiedIsRejectedAtConstruction() {
    // The bot's P2: previously, `recording: { tools: [] }` would silently produce a no-op
    // recorded step. The init { require(...) } block rejects this — empty tools require an
    // explicit auto_satisfied marker.
    val ex = try {
      ToolRecording(tools = emptyList(), autoSatisfied = false)
      null
    } catch (e: IllegalArgumentException) {
      e
    }
    assertFalse("constructor must throw", ex == null)
    assertTrue(
      "message should mention autoSatisfied",
      ex!!.message!!.contains("autoSatisfied"),
    )
  }
}
