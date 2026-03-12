package xyz.block.trailblaze.mcp.integration

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Test
import xyz.block.trailblaze.agent.Confidence
import xyz.block.trailblaze.agent.DefaultOuterStrategy
import xyz.block.trailblaze.agent.ExecutionResult
import xyz.block.trailblaze.agent.OuterLoopAgent
import xyz.block.trailblaze.agent.OuterAgentDecision
import xyz.block.trailblaze.agent.RecommendationContext
import xyz.block.trailblaze.agent.ScreenAnalysis
import xyz.block.trailblaze.agent.ScreenAnalyzer
import xyz.block.trailblaze.agent.StrategistResult
import xyz.block.trailblaze.agent.UiActionExecutor
import xyz.block.trailblaze.api.ScreenState
import xyz.block.trailblaze.api.ViewHierarchyTreeNode
import xyz.block.trailblaze.devices.TrailblazeDeviceClassifier
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.llm.TrailblazeLlmModel
import xyz.block.trailblaze.llm.TrailblazeLlmProvider
import xyz.block.trailblaze.logs.model.TraceId
import xyz.block.trailblaze.toolcalls.TrailblazeToolDescriptor
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * End-to-end integration tests for the two-tier agent architecture.
 *
 * These tests verify:
 * 1. OuterLoopAgent properly orchestrates inner/outer agents
 * 2. DefaultOuterStrategy makes correct decisions based on analysis
 * 3. Complete flow from objective → analysis → execution → completion
 * 4. Error handling and recovery paths
 *
 * Run with:
 * ```bash
 * ./gradlew :trailblaze-server:test --tests "xyz.block.trailblaze.mcp.integration.TwoTierAgentIntegrationTest"
 * ```
 *
 * @see OuterLoopAgent
 * @see ScreenAnalyzer
 * @see UiActionExecutor
 */
class TwoTierAgentIntegrationTest {

  companion object {
    /** Test model for cost tracking (not actually used for LLM calls in tests) */
    private val TEST_OUTER_MODEL = TrailblazeLlmModel(
      trailblazeLlmProvider = TrailblazeLlmProvider.OPENAI,
      modelId = "test-outer-model",
      inputCostPerOneMillionTokens = 10.0,
      outputCostPerOneMillionTokens = 30.0,
      contextLength = 128_000,
      maxOutputTokens = 4_096,
      capabilityIds = listOf("vision", "tools"),
    )
  }

  // ==========================================================================
  // Happy Path Tests
  // ==========================================================================

  @Test
  fun `two-tier agent completes objective on first analysis`() = runTest {
    // Setup: Inner agent immediately reports objective achieved
    val mockAnalyzer = MockScreenAnalyzer { _, _ ->
      createAnalysis(objectiveAppearsAchieved = true, screenSummary = "Already on home screen")
    }

    val agent = OuterLoopAgent(
      innerAgent = mockAnalyzer,
      executor = MockUiActionExecutor(),
      outerModel = TEST_OUTER_MODEL,
    )

    val result = agent.run("Go to home screen")

    assertIs<StrategistResult.Success>(result)
    assertTrue(result.summary.contains("home screen"))
    assertEquals(1, result.iterations)
    assertTrue(result.actionsTaken.isEmpty()) // No actions needed
  }

  @Test
  fun `two-tier agent executes recommended action and completes`() = runTest {
    var analysisCount = 0
    var executionCount = 0

    // Setup: First analysis recommends tap, second reports complete
    val mockAnalyzer = MockScreenAnalyzer { context, _ ->
      analysisCount++
      if (analysisCount == 1) {
        createAnalysis(
          recommendedTool = "tapOnElementByNodeId",
          recommendedArgs = buildJsonObject { put("nodeId", "login_button") },
          confidence = Confidence.HIGH,
        )
      } else {
        createAnalysis(objectiveAppearsAchieved = true, screenSummary = "Logged in successfully")
      }
    }

    val mockExecutor = MockUiActionExecutor { toolName, args, _ ->
      executionCount++
      assertEquals("tapOnElementByNodeId", toolName)
      ExecutionResult.Success(
        screenSummaryAfter = "Login form submitted",
        durationMs = 100,
      )
    }

    val agent = OuterLoopAgent(
      innerAgent = mockAnalyzer,
      executor = mockExecutor,
      outerModel = TEST_OUTER_MODEL,
    )

    val result = agent.run("Log into the app")

    assertIs<StrategistResult.Success>(result)
    assertEquals(2, analysisCount) // Two analyses
    assertEquals(1, executionCount) // One action executed
    assertEquals(1, result.actionsTaken.size)
  }

  @Test
  fun `two-tier agent follows multi-step flow`() = runTest {
    var analysisCount = 0
    val actions = mutableListOf<String>()

    // Setup: Three steps - tap username, tap password, tap login
    val mockAnalyzer = MockScreenAnalyzer { _, _ ->
      analysisCount++
      when (analysisCount) {
        1 -> createAnalysis(
          recommendedTool = "tapOnElementByNodeId",
          recommendedArgs = buildJsonObject { put("nodeId", "username_field") },
        )
        2 -> createAnalysis(
          recommendedTool = "inputText",
          recommendedArgs = buildJsonObject { put("text", "testuser") },
        )
        3 -> createAnalysis(
          recommendedTool = "tapOnElementByNodeId",
          recommendedArgs = buildJsonObject { put("nodeId", "login_button") },
        )
        else -> createAnalysis(objectiveAppearsAchieved = true)
      }
    }

    val mockExecutor = MockUiActionExecutor { toolName, args, _ ->
      actions.add(toolName)
      ExecutionResult.Success(screenSummaryAfter = "Step $toolName completed", durationMs = 50)
    }

    val agent = OuterLoopAgent(
      innerAgent = mockAnalyzer,
      executor = mockExecutor,
      outerModel = TEST_OUTER_MODEL,
    )

    val result = agent.run("Login with test credentials")

    assertIs<StrategistResult.Success>(result)
    assertEquals(4, analysisCount)
    assertEquals(3, actions.size)
    assertEquals(listOf("tapOnElementByNodeId", "inputText", "tapOnElementByNodeId"), actions)
  }

  // ==========================================================================
  // Error Handling Tests
  // ==========================================================================

  @Test
  fun `two-tier agent handles objective impossible`() = runTest {
    val mockAnalyzer = MockScreenAnalyzer { _, _ ->
      createAnalysis(
        objectiveAppearsImpossible = true,
        screenSummary = "Login button not found - app crashed",
      )
    }

    val agent = OuterLoopAgent(
      innerAgent = mockAnalyzer,
      executor = MockUiActionExecutor(),
      outerModel = TEST_OUTER_MODEL,
    )

    val result = agent.run("Tap the login button")

    assertIs<StrategistResult.Failed>(result)
    assertTrue(result.reason.contains("impossible"))
    assertEquals(1, result.iterations)
  }

  @Test
  fun `two-tier agent handles execution failure gracefully`() = runTest {
    var analysisCount = 0

    // Setup: First recommends action that fails, then reports impossible
    val mockAnalyzer = MockScreenAnalyzer { _, _ ->
      analysisCount++
      if (analysisCount <= 2) {
        createAnalysis(
          recommendedTool = "tapOnElementByNodeId",
          recommendedArgs = buildJsonObject { put("nodeId", "missing_element") },
          hint = if (analysisCount == 2) "Previous action failed: Element not found" else null,
        )
      } else {
        createAnalysis(objectiveAppearsImpossible = true, screenSummary = "Element cannot be found")
      }
    }

    val mockExecutor = MockUiActionExecutor { _, _, _ ->
      ExecutionResult.Failure(
        error = "Element not found: missing_element",
        recoverable = true,
      )
    }

    val agent = OuterLoopAgent(
      innerAgent = mockAnalyzer,
      executor = mockExecutor,
      outerModel = TEST_OUTER_MODEL,
      maxIterations = 5,
    )

    val result = agent.run("Tap the missing button")

    // Should either fail or report impossible
    assertTrue(result is StrategistResult.Failed || result is StrategistResult.Error)
  }

  @Test
  fun `two-tier agent respects max iterations`() = runTest {
    var analysisCount = 0
    var executionCount = 0

    // Setup: Always recommends an action, never completes
    val mockAnalyzer = MockScreenAnalyzer { _, _ ->
      analysisCount++
      createAnalysis(
        recommendedTool = "tapOnElementByNodeId",
        recommendedArgs = buildJsonObject { put("nodeId", "button_$analysisCount") },
      )
    }

    // Vary screen summary to avoid stuck detection
    val mockExecutor = MockUiActionExecutor { _, _, _ ->
      executionCount++
      ExecutionResult.Success(screenSummaryAfter = "Screen after action $executionCount", durationMs = 10)
    }

    val maxIterations = 5
    val agent = OuterLoopAgent(
      innerAgent = mockAnalyzer,
      executor = mockExecutor,
      outerModel = TEST_OUTER_MODEL,
      maxIterations = maxIterations,
    )

    val result = agent.run("Infinite loop objective")

    assertIs<StrategistResult.Error>(result)
    assertTrue(result.message.contains("Max iterations"))
    assertEquals(maxIterations, result.iterations)
  }

  @Test
  fun `two-tier agent handles screen capture failure`() = runTest {
    val mockAnalyzer = MockScreenAnalyzer { _, _ ->
      createAnalysis()
    }

    val mockExecutor = MockUiActionExecutor(
      screenStateProvider = { null }, // No screen state available
    )

    val agent = OuterLoopAgent(
      innerAgent = mockAnalyzer,
      executor = mockExecutor,
      outerModel = TEST_OUTER_MODEL,
    )

    val result = agent.run("Do something")

    assertIs<StrategistResult.Error>(result)
    assertTrue(result.message.contains("screen state"))
  }

  // ==========================================================================
  // Strategy Tests
  // ==========================================================================

  @Test
  fun `DefaultOuterStrategy executes high confidence recommendations`() = runTest {
    val strategy = DefaultOuterStrategy()

    val decision = strategy.decide(
      objective = "Tap login",
      analysisResult = createAnalysis(confidence = Confidence.HIGH),
      history = emptyList(),
    )

    assertIs<OuterAgentDecision.Execute>(decision)
    assertEquals("tapOnElementByNodeId", decision.tool)
  }

  @Test
  fun `DefaultOuterStrategy completes when objective achieved`() = runTest {
    val strategy = DefaultOuterStrategy()

    val decision = strategy.decide(
      objective = "Tap login",
      analysisResult = createAnalysis(objectiveAppearsAchieved = true),
      history = emptyList(),
    )

    assertIs<OuterAgentDecision.Complete>(decision)
  }

  @Test
  fun `DefaultOuterStrategy fails when objective impossible`() = runTest {
    val strategy = DefaultOuterStrategy()

    val decision = strategy.decide(
      objective = "Tap login",
      analysisResult = createAnalysis(objectiveAppearsImpossible = true),
      history = emptyList(),
    )

    assertIs<OuterAgentDecision.Fail>(decision)
  }

  @Test
  fun `DefaultOuterStrategy requests new analysis on low confidence`() = runTest {
    val strategy = DefaultOuterStrategy()

    val decision = strategy.decide(
      objective = "Tap login",
      analysisResult = createAnalysis(confidence = Confidence.LOW),
      history = emptyList(),
    )

    assertIs<OuterAgentDecision.RequestNewAnalysis>(decision)
    assertTrue(decision.hint.isNotEmpty())
  }

  // ==========================================================================
  // Helper Methods and Mocks
  // ==========================================================================

  private fun createAnalysis(
    recommendedTool: String = "tapOnElementByNodeId",
    recommendedArgs: JsonObject = buildJsonObject { put("nodeId", "default_button") },
    reasoning: String = "Test reasoning",
    screenSummary: String = "Test screen",
    confidence: Confidence = Confidence.HIGH,
    objectiveAppearsAchieved: Boolean = false,
    objectiveAppearsImpossible: Boolean = false,
    hint: String? = null,
  ): ScreenAnalysis {
    return ScreenAnalysis(
      recommendedTool = recommendedTool,
      recommendedArgs = recommendedArgs,
      reasoning = reasoning,
      screenSummary = screenSummary,
      progressIndicators = emptyList(),
      potentialBlockers = emptyList(),
      alternativeApproaches = emptyList(),
      confidence = confidence,
      objectiveAppearsAchieved = objectiveAppearsAchieved,
      objectiveAppearsImpossible = objectiveAppearsImpossible,
    )
  }

  /**
   * Mock ScreenAnalyzer for testing.
   */
  private class MockScreenAnalyzer(
    private val onAnalyze: (RecommendationContext, ScreenState) -> ScreenAnalysis,
  ) : ScreenAnalyzer {
    override suspend fun analyze(
      context: RecommendationContext,
      screenState: ScreenState,
      traceId: TraceId?,
      availableTools: List<TrailblazeToolDescriptor>,
    ): ScreenAnalysis {
      return onAnalyze(context, screenState)
    }
  }

  /**
   * Mock UiActionExecutor for testing.
   */
  private class MockUiActionExecutor(
    private val screenStateProvider: () -> ScreenState? = { createStaticMockScreenState() },
    private val onExecute: (String, JsonObject, TraceId?) -> ExecutionResult = { _, _, _ ->
      ExecutionResult.Success(
        screenSummaryAfter = "Action completed",
        durationMs = 100,
      )
    },
  ) : UiActionExecutor {

    companion object {
      private fun createStaticMockScreenState(): ScreenState {
        val emptyVh = ViewHierarchyTreeNode()
        return object : ScreenState {
          override val screenshotBytes: ByteArray? = byteArrayOf(1, 2, 3)
          override val deviceWidth: Int = 1080
          override val deviceHeight: Int = 1920
          override val viewHierarchyOriginal: ViewHierarchyTreeNode = emptyVh
          override val viewHierarchy: ViewHierarchyTreeNode = emptyVh
          override val trailblazeDevicePlatform: TrailblazeDevicePlatform = TrailblazeDevicePlatform.ANDROID
          override val deviceClassifiers: List<TrailblazeDeviceClassifier> = emptyList()
        }
      }
    }

    override suspend fun execute(
      toolName: String,
      args: JsonObject,
      traceId: TraceId?,
    ): ExecutionResult {
      return onExecute(toolName, args, traceId)
    }

    override suspend fun captureScreenState(): ScreenState? {
      return screenStateProvider()
    }
  }
}
