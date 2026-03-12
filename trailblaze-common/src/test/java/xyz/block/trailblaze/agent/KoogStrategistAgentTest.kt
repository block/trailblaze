package xyz.block.trailblaze.agent

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.block.trailblaze.api.ScreenState
import xyz.block.trailblaze.api.ViewHierarchyTreeNode
import xyz.block.trailblaze.devices.TrailblazeDeviceClassifier
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.llm.TrailblazeLlmModels
import xyz.block.trailblaze.logs.model.TraceId
import xyz.block.trailblaze.toolcalls.TrailblazeToolDescriptor

/**
 * Unit tests for [OuterLoopAgent].
 *
 * Uses mock implementations of [ScreenAnalyzer] and [UiActionExecutor] to test
 * the agent's behavior in various scenarios.
 */
class KoogStrategistAgentTest {

  @Test
  fun `happy path - analyze, execute, complete`() = runBlocking {
    // Given: Inner agent recommends tap, objective achieved after execution
    val mockAnalyzer = MockScreenAnalyzer(
      responses = listOf(
        // First analysis: recommend tap
        createAnalysis(
          tool = "tap",
          args = buildJsonObject { put("nodeId", "login_button") },
          confidence = Confidence.HIGH,
        ),
        // Second analysis: objective achieved
        createAnalysis(
          tool = "none",
          args = buildJsonObject { },
          confidence = Confidence.HIGH,
          objectiveAchieved = true,
        ),
      )
    )
    val mockExecutor = MockUiActionExecutor(
      results = listOf(
        ExecutionResult.Success(
          screenSummaryAfter = "Login screen transitioned to home screen",
          durationMs = 150,
        ),
      )
    )

    val agent = OuterLoopAgent(
      innerAgent = mockAnalyzer,
      executor = mockExecutor,
      outerModel = TrailblazeLlmModels.GPT_4O,
    )

    // When
    val result = agent.run("Log in to the app")

    // Then
    assertTrue("Expected Success result", result is StrategistResult.Success)
    val success = result as StrategistResult.Success
    assertEquals(1, success.actionsTaken.size)
    assertEquals("tap", success.actionsTaken[0].tool)
  }

  @Test
  fun `objective already achieved on first screen`() = runBlocking {
    // Given: Inner agent immediately sees objective is achieved
    val mockAnalyzer = MockScreenAnalyzer(
      responses = listOf(
        createAnalysis(
          tool = "none",
          args = buildJsonObject { },
          confidence = Confidence.HIGH,
          objectiveAchieved = true,
          screenSummary = "Already on the home screen",
        ),
      )
    )
    val mockExecutor = MockUiActionExecutor()

    val agent = OuterLoopAgent(
      innerAgent = mockAnalyzer,
      executor = mockExecutor,
      outerModel = TrailblazeLlmModels.GPT_4O,
    )

    // When
    val result = agent.run("Navigate to home screen")

    // Then
    assertTrue("Expected Success result", result is StrategistResult.Success)
    val success = result as StrategistResult.Success
    assertTrue("No actions should be taken", success.actionsTaken.isEmpty())
    assertTrue(success.summary.contains("Already on the home screen"))
  }

  @Test
  fun `objective appears impossible`() = runBlocking {
    // Given: Inner agent determines objective is impossible
    val mockAnalyzer = MockScreenAnalyzer(
      responses = listOf(
        createAnalysis(
          tool = "none",
          args = buildJsonObject { },
          confidence = Confidence.LOW,
          objectiveImpossible = true,
          screenSummary = "App shows 'Account Locked' error",
        ),
      )
    )
    val mockExecutor = MockUiActionExecutor()

    val agent = OuterLoopAgent(
      innerAgent = mockAnalyzer,
      executor = mockExecutor,
      outerModel = TrailblazeLlmModels.GPT_4O,
    )

    // When
    val result = agent.run("Log in with test credentials")

    // Then
    assertTrue("Expected Failed result", result is StrategistResult.Failed)
    val failed = result as StrategistResult.Failed
    assertTrue(failed.reason.contains("Account Locked"))
  }

  @Test
  fun `unrecoverable error stops execution`() = runBlocking {
    // Given: Executor returns unrecoverable error
    val mockAnalyzer = MockScreenAnalyzer(
      responses = listOf(
        createAnalysis(
          tool = "tap",
          args = buildJsonObject { put("nodeId", "button") },
          confidence = Confidence.HIGH,
        ),
      )
    )
    val mockExecutor = MockUiActionExecutor(
      results = listOf(
        ExecutionResult.Failure(
          error = "Device disconnected",
          recoverable = false,
        ),
      )
    )

    val agent = OuterLoopAgent(
      innerAgent = mockAnalyzer,
      executor = mockExecutor,
      outerModel = TrailblazeLlmModels.GPT_4O,
    )

    // When
    val result = agent.run("Tap button")

    // Then
    assertTrue("Expected Failed result", result is StrategistResult.Failed)
    val failed = result as StrategistResult.Failed
    assertTrue(failed.reason.contains("Device disconnected"))
  }

  @Test
  fun `recoverable error continues with hint`() = runBlocking {
    // Given: First attempt fails (recoverable), second succeeds
    val mockAnalyzer = MockScreenAnalyzer(
      responses = listOf(
        createAnalysis(
          tool = "tap",
          args = buildJsonObject { put("nodeId", "button") },
          confidence = Confidence.HIGH,
        ),
        createAnalysis(
          tool = "tap",
          args = buildJsonObject { put("nodeId", "button_retry") },
          confidence = Confidence.HIGH,
        ),
        createAnalysis(
          tool = "none",
          args = buildJsonObject { },
          confidence = Confidence.HIGH,
          objectiveAchieved = true,
        ),
      )
    )
    val mockExecutor = MockUiActionExecutor(
      results = listOf(
        ExecutionResult.Failure(error = "Element not found", recoverable = true),
        ExecutionResult.Success(screenSummaryAfter = "Success", durationMs = 100),
      )
    )

    val agent = OuterLoopAgent(
      innerAgent = mockAnalyzer,
      executor = mockExecutor,
      outerModel = TrailblazeLlmModels.GPT_4O,
    )

    // When
    val result = agent.run("Tap the button")

    // Then
    assertTrue("Expected Success result", result is StrategistResult.Success)
    val success = result as StrategistResult.Success
    assertEquals(2, success.actionsTaken.size)
  }

  @Test
  fun `max iterations reached`() = runBlocking {
    // Given: Analyzer never completes objective
    val infiniteResponses = (1..100).map {
      createAnalysis(
        tool = "tap",
        args = buildJsonObject { put("iteration", it) },
        confidence = Confidence.MEDIUM,
      )
    }
    val mockAnalyzer = MockScreenAnalyzer(responses = infiniteResponses)

    val infiniteResults = (1..100).map {
      ExecutionResult.Success(screenSummaryAfter = "Same screen $it", durationMs = 50)
    }
    val mockExecutor = MockUiActionExecutor(results = infiniteResults)

    val agent = OuterLoopAgent(
      innerAgent = mockAnalyzer,
      executor = mockExecutor,
      outerModel = TrailblazeLlmModels.GPT_4O,
      maxIterations = 5,
    )

    // When
    val result = agent.run("Do something forever")

    // Then
    assertTrue("Expected Error result", result is StrategistResult.Error)
    val error = result as StrategistResult.Error
    assertTrue(error.message.contains("Max iterations"))
    assertEquals(5, error.iterations)
  }

  @Test
  fun `screen state capture failure`() = runBlocking {
    // Given: Executor fails to capture screen state
    val mockAnalyzer = MockScreenAnalyzer(responses = emptyList())
    val mockExecutor = MockUiActionExecutor(screenState = null)

    val agent = OuterLoopAgent(
      innerAgent = mockAnalyzer,
      executor = mockExecutor,
      outerModel = TrailblazeLlmModels.GPT_4O,
    )

    // When
    val result = agent.run("Do something")

    // Then
    assertTrue("Expected Error result", result is StrategistResult.Error)
    val error = result as StrategistResult.Error
    assertTrue(error.message.contains("Failed to capture screen state"))
  }

  // ==========================================================================
  // Helper Methods and Mock Classes
  // ==========================================================================

  private fun createAnalysis(
    tool: String,
    args: JsonObject,
    confidence: Confidence,
    objectiveAchieved: Boolean = false,
    objectiveImpossible: Boolean = false,
    screenSummary: String = "Test screen summary",
    reasoning: String = "Test reasoning",
  ): ScreenAnalysis = ScreenAnalysis(
    recommendedTool = tool,
    recommendedArgs = args,
    reasoning = reasoning,
    screenSummary = screenSummary,
    progressIndicators = emptyList(),
    potentialBlockers = emptyList(),
    alternativeApproaches = emptyList(),
    confidence = confidence,
    objectiveAppearsAchieved = objectiveAchieved,
    objectiveAppearsImpossible = objectiveImpossible,
  )

  /**
   * Mock implementation of [ScreenAnalyzer] for testing.
   */
  private class MockScreenAnalyzer(
    private val responses: List<ScreenAnalysis>,
  ) : ScreenAnalyzer {
    private var callIndex = 0

    override suspend fun analyze(
      context: RecommendationContext,
      screenState: ScreenState,
      traceId: TraceId?,
      availableTools: List<TrailblazeToolDescriptor>,
    ): ScreenAnalysis {
      if (callIndex >= responses.size) {
        throw IllegalStateException("MockScreenAnalyzer ran out of responses at index $callIndex")
      }
      return responses[callIndex++]
    }
  }

  /**
   * Mock implementation of [UiActionExecutor] for testing.
   */
  private class MockUiActionExecutor(
    private val results: List<ExecutionResult> = emptyList(),
    private val screenState: ScreenState? = FakeScreenState(),
  ) : UiActionExecutor {
    private var callIndex = 0

    override suspend fun execute(
      toolName: String,
      args: JsonObject,
      traceId: TraceId?,
    ): ExecutionResult {
      if (callIndex >= results.size) {
        throw IllegalStateException("MockUiActionExecutor ran out of results at index $callIndex")
      }
      return results[callIndex++]
    }

    override suspend fun captureScreenState(): ScreenState? = screenState
  }

  /**
   * Minimal fake implementation of [ScreenState] for testing.
   */
  private class FakeScreenState : ScreenState {
    override val screenshotBytes: ByteArray? = null
    override val annotatedScreenshotBytes: ByteArray? = null
    override val deviceWidth: Int = 1080
    override val deviceHeight: Int = 1920
    override val viewHierarchyOriginal: ViewHierarchyTreeNode = ViewHierarchyTreeNode(
      nodeId = 0,
      className = "root",
      text = null,
      accessibilityText = null,
      resourceId = null,
      children = emptyList(),
    )
    override val viewHierarchy: ViewHierarchyTreeNode = viewHierarchyOriginal
    override val trailblazeDevicePlatform: TrailblazeDevicePlatform =
      TrailblazeDevicePlatform.ANDROID
    override val deviceClassifiers: List<TrailblazeDeviceClassifier> = emptyList()
  }
}
