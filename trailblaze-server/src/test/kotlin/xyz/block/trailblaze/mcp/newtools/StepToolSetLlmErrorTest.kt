package xyz.block.trailblaze.mcp.newtools

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
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
import xyz.block.trailblaze.mcp.ViewHierarchyVerbosity
import xyz.block.trailblaze.toolcalls.TrailblazeToolDescriptor
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A failed LLM call must come back from `step` and `ask` as a tool error — a response starting
 * with `Error:`, which is what the MCP server flags `isError` and the CLI turns into a non-zero
 * exit. Before this, the analyzer's placeholder for a failed call (LOW confidence, "wait") came
 * back as an answer from `ask` and as "needs input" from `step`, so a revoked LLM key left every
 * CLI smoke check green.
 */
class StepToolSetLlmErrorTest {

  private val screenState =
    object : ScreenState {
      override val screenshotBytes: ByteArray? = ByteArray(0)
      override val deviceWidth: Int = 1080
      override val deviceHeight: Int = 1920
      override val viewHierarchy: ViewHierarchyTreeNode = ViewHierarchyTreeNode()
      override val trailblazeDevicePlatform: TrailblazeDevicePlatform =
        TrailblazeDevicePlatform.ANDROID
      override val deviceClassifiers: List<TrailblazeDeviceClassifier> = emptyList()
    }

  private class CountingExecutor : UiActionExecutor {
    var calls = 0
      private set

    override suspend fun execute(toolName: String, args: JsonObject, traceId: TraceId?): ExecutionResult {
      calls++
      return ExecutionResult.Success(screenSummaryAfter = "after", durationMs = 0L)
    }

    override suspend fun captureScreenState(): ScreenState? = null
  }

  /** The placeholder InnerLoopScreenAnalyzer returns for a failed call, with or without the marker. */
  private fun placeholder(llmError: String?) = ScreenAnalysis(
    recommendedTool = "wait",
    recommendedArgs = buildJsonObject {},
    reasoning = "LLM sampling failed: Status code: 403",
    screenSummary = "Error during analysis",
    confidence = Confidence.LOW,
    llmError = llmError,
  )

  private fun toolSet(analysis: ScreenAnalysis, executor: UiActionExecutor = CountingExecutor()) =
    StepToolSet(
      screenAnalyzer = object : ScreenAnalyzer {
        override suspend fun analyze(
          context: RecommendationContext,
          screenState: ScreenState,
          traceId: TraceId?,
          availableTools: List<TrailblazeToolDescriptor>,
        ): ScreenAnalysis = analysis
      },
      executor = executor,
      screenStateProvider = { _, _, _ -> screenState },
    )

  @Test
  fun `step reports a failed LLM call as a tool error without executing`() = runTest {
    val executor = CountingExecutor()

    val result = toolSet(placeholder(llmError = "Status code: 403"), executor).step(objective = "Tap Sign In")

    assertTrue(result.startsWith("Error:"), "expected a tool error, got: $result")
    assertContains(result, "Status code: 403")
    assertEquals(0, executor.calls)
  }

  @Test
  fun `verify reports a failed LLM call as a tool error, not an assertion verdict`() = runTest {
    val result = toolSet(placeholder(llmError = "Status code: 403"))
      .step(objective = "Sign In is visible", hint = "VERIFY")

    assertTrue(result.startsWith("Error:"), "expected a tool error, got: $result")
    assertFalse("FAILED" in result, "an LLM outage is not an assertion failure: $result")
  }

  @Test
  fun `ask reports a failed LLM call as a tool error, not an answer`() = runTest {
    val result = toolSet(placeholder(llmError = "Status code: 403")).ask(question = "What is on screen?")

    assertTrue(result.startsWith("Error:"), "expected a tool error, got: $result")
    assertContains(result, "Status code: 403")
    assertFalse("**Answer:**" in result, result)
  }

  @Test
  fun `ask keeps the view hierarchy it was asked for when the LLM call fails`() = runTest {
    // The hierarchy comes from the device, not the LLM, and is what a caller debugging the failure
    // needs.
    val result = toolSet(placeholder(llmError = "Status code: 403"))
      .ask(question = "What is on screen?", viewHierarchy = ViewHierarchyVerbosity.FULL)

    assertTrue(result.startsWith("Error:"), result)
    assertContains(result, "**View Hierarchy:**")
  }

  @Test
  fun `a low-confidence analysis from a working LLM is still not an error`() = runTest {
    // Same placeholder shape without the marker: the marker, not LOW confidence or "wait", is what
    // makes it a failure. Without this, the tests above would pass on a check for LOW confidence.
    val step = toolSet(placeholder(llmError = null)).step(objective = "Tap Sign In")
    val ask = toolSet(placeholder(llmError = null)).ask(question = "What is on screen?")

    assertFalse(step.startsWith("Error:"), step)
    assertContains(step, "Needs input")
    assertFalse(ask.startsWith("Error:"), ask)
    assertContains(ask, "**Answer:**")
  }

  @Test
  fun `step and ask report an analyzer exception as a tool error`() = runTest {
    val throwing = StepToolSet(
      screenAnalyzer = object : ScreenAnalyzer {
        override suspend fun analyze(
          context: RecommendationContext,
          screenState: ScreenState,
          traceId: TraceId?,
          availableTools: List<TrailblazeToolDescriptor>,
        ): ScreenAnalysis = throw IllegalStateException("connection reset")
      },
      executor = CountingExecutor(),
      screenStateProvider = { _, _, _ -> screenState },
    )

    val step = throwing.step(objective = "Tap Sign In")
    val ask = throwing.ask(question = "What is on screen?")

    assertTrue(step.startsWith("Error:") && "connection reset" in step, step)
    assertTrue(ask.startsWith("Error:") && "connection reset" in ask, ask)
  }
}
