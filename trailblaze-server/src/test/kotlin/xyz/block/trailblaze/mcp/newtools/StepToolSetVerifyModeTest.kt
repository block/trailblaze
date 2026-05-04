package xyz.block.trailblaze.mcp.newtools

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Test
import xyz.block.trailblaze.agent.Confidence
import xyz.block.trailblaze.agent.ExecutionResult
import xyz.block.trailblaze.agent.RecommendationContext
import xyz.block.trailblaze.agent.ScreenAnalysis
import xyz.block.trailblaze.agent.ScreenAnalyzer
import xyz.block.trailblaze.agent.UiActionExecutor
import xyz.block.trailblaze.api.ScreenState
import xyz.block.trailblaze.api.ViewHierarchyTreeNode
import xyz.block.trailblaze.devices.TrailblazeDeviceClassifier
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.logs.model.TraceId
import xyz.block.trailblaze.toolcalls.TrailblazeToolDescriptor
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * Verifies that `blaze(hint=VERIFY)` only treats an assertion as PASSED when the
 * LLM either explicitly confirms it (objectiveAppearsAchieved=true) or picks a tool
 * annotated `@TrailblazeToolClass(isVerification = true)`. Regression test for the bug
 * where `verify` exited 0 for a clearly false assertion because a non-verification
 * tool's successful execution was misinterpreted as proof the assertion held.
 */
class StepToolSetVerifyModeTest {

  private val dummyScreenState =
    object : ScreenState {
      override val screenshotBytes: ByteArray? = ByteArray(0)
      override val deviceWidth: Int = 1080
      override val deviceHeight: Int = 1920
      override val viewHierarchy: ViewHierarchyTreeNode = ViewHierarchyTreeNode()
      override val trailblazeDevicePlatform: TrailblazeDevicePlatform =
        TrailblazeDevicePlatform.ANDROID
      override val deviceClassifiers: List<TrailblazeDeviceClassifier> = emptyList()
    }

  /** Returns a fixed analysis without ever invoking the model. */
  private fun stubAnalyzer(analysis: ScreenAnalysis): ScreenAnalyzer =
    object : ScreenAnalyzer {
      override suspend fun analyze(
        context: RecommendationContext,
        screenState: ScreenState,
        traceId: TraceId?,
        availableTools: List<TrailblazeToolDescriptor>,
      ): ScreenAnalysis = analysis
    }

  /** Tracks whether `executor.execute` was called and lets the test pick the result. */
  private class RecordingExecutor(
    private val result: ExecutionResult = ExecutionResult.Success(screenSummaryAfter = "after", durationMs = 0L),
    private val throws: Throwable? = null,
  ) : UiActionExecutor {
    var calls: Int = 0
      private set

    override suspend fun execute(toolName: String, args: JsonObject, traceId: TraceId?): ExecutionResult {
      calls++
      throws?.let { throw it }
      return result
    }

    override suspend fun captureScreenState(): ScreenState? = null
  }

  private fun analysis(
    recommendedTool: String,
    confidence: Confidence = Confidence.HIGH,
    achieved: Boolean = false,
    impossible: Boolean = false,
  ): ScreenAnalysis = ScreenAnalysis(
    recommendedTool = recommendedTool,
    recommendedArgs = buildJsonObject { put("ref", "k166") },
    reasoning = "stub reasoning",
    screenSummary = "stub screen",
    confidence = confidence,
    objectiveAppearsAchieved = achieved,
    objectiveAppearsImpossible = impossible,
  )

  @Test
  fun `verify with non-verification tool fails without executing`() = runTest {
    val executor = RecordingExecutor()
    val toolSet = StepToolSet(
      // `tap` is a normal interaction tool — not annotated isVerification.
      screenAnalyzer = stubAnalyzer(analysis(recommendedTool = "tap")),
      executor = executor,
      screenStateProvider = { _, _, _ -> dummyScreenState },
    )

    val result = toolSet.blaze(objective = "A pizza emoji is visible", hint = "VERIFY")

    assertContains(result, "FAILED")
    assertContains(result, "Assertion not confirmed")
    assertFalse(executor.calls > 0, "Verify must not execute non-verification tools (would mutate the device)")
  }

  @Test
  fun `verify with verification tool runs the executor and reports its outcome`() = runTest {
    val executor = RecordingExecutor(result = ExecutionResult.Success(screenSummaryAfter = "after", durationMs = 0L))
    val toolSet = StepToolSet(
      // `assertVisible` is annotated @TrailblazeToolClass(isVerification = true).
      screenAnalyzer = stubAnalyzer(analysis(recommendedTool = "assertVisible")),
      executor = executor,
      screenStateProvider = { _, _, _ -> dummyScreenState },
    )

    val result = toolSet.blaze(objective = "Sign In button is visible", hint = "VERIFY")

    assertContains(result, "PASSED")
    assertEquals(1, executor.calls, "verification tool should run once")
  }

  @Test
  fun `verify with verification tool that fails to execute reports FAILED`() = runTest {
    val executor = RecordingExecutor(result = ExecutionResult.Failure(error = "element not visible", recoverable = false))
    val toolSet = StepToolSet(
      screenAnalyzer = stubAnalyzer(analysis(recommendedTool = "assertVisible")),
      executor = executor,
      screenStateProvider = { _, _, _ -> dummyScreenState },
    )

    val result = toolSet.blaze(objective = "Hidden button is visible", hint = "VERIFY")

    assertContains(result, "FAILED")
    assertContains(result, "element not visible")
  }

  @Test
  fun `verify with unknown tool name fails without executing`() = runTest {
    val executor = RecordingExecutor()
    val toolSet = StepToolSet(
      // No registered tool by this name → treated as unsafe → FAILED, not executed.
      screenAnalyzer = stubAnalyzer(analysis(recommendedTool = "nonexistent_made_up_tool")),
      executor = executor,
      screenStateProvider = { _, _, _ -> dummyScreenState },
    )

    val result = toolSet.blaze(objective = "Anything", hint = "VERIFY")

    assertContains(result, "FAILED")
    assertFalse(executor.calls > 0, "Unknown tools must not execute under verify")
  }

  @Test
  fun `verify with objectiveAppearsAchieved passes without executing`() = runTest {
    val executor = RecordingExecutor()
    val toolSet = StepToolSet(
      screenAnalyzer = stubAnalyzer(analysis(recommendedTool = "tap", achieved = true)),
      executor = executor,
      screenStateProvider = { _, _, _ -> dummyScreenState },
    )

    val result = toolSet.blaze(objective = "Already on home screen", hint = "VERIFY")

    assertContains(result, "PASSED")
    assertFalse(executor.calls > 0, "Verify with achieved=true must not execute any tool")
  }

  @Test
  fun `verify with objectiveAppearsImpossible fails without executing`() = runTest {
    val executor = RecordingExecutor()
    val toolSet = StepToolSet(
      screenAnalyzer = stubAnalyzer(analysis(recommendedTool = "tap", impossible = true)),
      executor = executor,
      screenStateProvider = { _, _, _ -> dummyScreenState },
    )

    val result = toolSet.blaze(objective = "A pizza emoji is visible", hint = "VERIFY")

    assertContains(result, "FAILED")
    assertContains(result, "Assertion failed")
    assertFalse(executor.calls > 0, "Verify with impossible=true must not execute any tool")
  }

  @Test
  fun `verify with verification tool that throws reports FAILED`() = runTest {
    // Covers the catch-around-executor.execute path: even verification tools can fail
    // by throwing (e.g. driver lost the device). Verify must report FAILED, not crash.
    val executor = RecordingExecutor(throws = IllegalStateException("driver disconnected"))
    val toolSet = StepToolSet(
      screenAnalyzer = stubAnalyzer(analysis(recommendedTool = "assertVisible")),
      executor = executor,
      screenStateProvider = { _, _, _ -> dummyScreenState },
    )

    val result = toolSet.blaze(objective = "Sign In button is visible", hint = "VERIFY")

    assertContains(result, "FAILED")
    assertContains(result, "Action failed")
    assertContains(result, "driver disconnected")
    assertEquals(1, executor.calls, "executor should have been invoked exactly once before throwing")
  }

  @Test
  fun `verify with LOW confidence fails without executing even for verification tools`() = runTest {
    // LOW confidence means the LLM couldn't tell whether the assertion holds. For verify
    // mode that's a failure ("not confirmed"), not a "needs input" — verify is binary.
    val executor = RecordingExecutor()
    val toolSet = StepToolSet(
      screenAnalyzer = stubAnalyzer(
        analysis(recommendedTool = "assertVisible", confidence = Confidence.LOW),
      ),
      executor = executor,
      screenStateProvider = { _, _, _ -> dummyScreenState },
    )

    val result = toolSet.blaze(objective = "Something maybe visible", hint = "VERIFY")

    assertContains(result, "FAILED")
    assertContains(result, "low confidence")
    assertFalse(executor.calls > 0, "LOW-confidence verify must not execute any tool")
  }

  @Test
  fun `verify with blank tool name fails without executing`() = runTest {
    // Defensive: a blank/whitespace recommendedTool is not a known verification tool.
    val executor = RecordingExecutor()
    val toolSet = StepToolSet(
      screenAnalyzer = stubAnalyzer(analysis(recommendedTool = "   ")),
      executor = executor,
      screenStateProvider = { _, _, _ -> dummyScreenState },
    )

    val result = toolSet.blaze(objective = "Anything", hint = "VERIFY")

    assertContains(result, "FAILED")
    assertFalse(executor.calls > 0, "Blank tool name must not be treated as verification")
  }
}
