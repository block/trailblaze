package xyz.block.trailblaze.mcp.integration

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Test
import xyz.block.trailblaze.agent.AgentResult
import xyz.block.trailblaze.agent.DirectMcpAgent
import xyz.block.trailblaze.agent.SamplingResult
import xyz.block.trailblaze.agent.SamplingSource
import xyz.block.trailblaze.agent.ScreenContext
import xyz.block.trailblaze.api.ScreenState
import xyz.block.trailblaze.api.TrailblazeAgent
import xyz.block.trailblaze.api.ViewHierarchyTreeNode
import xyz.block.trailblaze.devices.TrailblazeDeviceClassifier
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.logs.model.TraceId
import xyz.block.trailblaze.toolcalls.TrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolDescriptor
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult
import xyz.block.trailblaze.toolcalls.commands.BooleanAssertionTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.InputTextTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.StringEvaluationTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.TapOnPointTrailblazeTool
import xyz.block.trailblaze.toolcalls.toolName
import xyz.block.trailblaze.utils.ElementComparator
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import xyz.block.trailblaze.util.Console

/**
 * Tests for multi-objective agent execution.
 *
 * Verifies that multiple objectives/prompts can be executed in sequence,
 * with proper state management and aggregated results.
 *
 * Run with:
 * ```bash
 * ./gradlew :trailblaze-server:test --tests "xyz.block.trailblaze.mcp.integration.MultiObjectiveTest"
 * ```
 */
class MultiObjectiveTest {

  companion object {
    // Type-safe tool names extracted from @TrailblazeToolClass annotations
    private val TOOL_TAP_ON_POINT = TapOnPointTrailblazeTool::class.toolName().toolName
    private val TOOL_INPUT_TEXT = InputTextTrailblazeTool::class.toolName().toolName
  }

  // ==========================================================================
  // Sequential Objective Tests
  // ==========================================================================

  @Test
  fun `multiple objectives execute in sequence`() = runTest {
    val objectiveResponses = mapOf(
      "Open settings" to listOf(
        """{"tool": "$TOOL_TAP_ON_POINT", "args": {"x": 100, "y": 200}}""",
        """{"complete": true, "summary": "Opened settings"}""",
      ),
      "Navigate to WiFi" to listOf(
        """{"tool": "$TOOL_TAP_ON_POINT", "args": {"x": 150, "y": 300}}""",
        """{"complete": true, "summary": "Navigated to WiFi"}""",
      ),
      "Toggle WiFi" to listOf(
        """{"tool": "$TOOL_TAP_ON_POINT", "args": {"x": 200, "y": 400}}""",
        """{"complete": true, "summary": "Toggled WiFi"}""",
      ),
    )

    val objectives = listOf("Open settings", "Navigate to WiFi", "Toggle WiFi")
    val results = mutableListOf<AgentResult>()

    val samplingSource = ObjectiveSamplingSource(objectiveResponses)

    for (objective in objectives) {
      val agent = createAgent(samplingSource)
      results.add(agent.run(objective))
    }

    assertEquals(3, results.size, "Should have 3 results")
    assertTrue(results.all { it is AgentResult.Success }, "All objectives should succeed")

    results.forEachIndexed { index, result ->
      assertIs<AgentResult.Success>(result)
      Console.log("Objective ${index + 1}: ${result.summary}")
    }
  }

  @Test
  fun `second objective fails but first succeeds`() = runTest {
    val objectiveResponses = mapOf(
      "Open app" to listOf(
        """{"tool": "$TOOL_TAP_ON_POINT", "args": {"x": 100, "y": 200}}""",
        """{"complete": true, "summary": "App opened"}""",
      ),
      "Find non-existent button" to listOf(
        """{"tool": "$TOOL_TAP_ON_POINT", "args": {"x": 150, "y": 300}}""",
        """{"failed": true, "reason": "Button not found on screen"}""",
      ),
    )

    val objectives = listOf("Open app", "Find non-existent button")
    val results = mutableListOf<AgentResult>()

    val samplingSource = ObjectiveSamplingSource(objectiveResponses)

    for (objective in objectives) {
      val agent = createAgent(samplingSource)
      results.add(agent.run(objective))
    }

    assertIs<AgentResult.Success>(results[0])
    assertIs<AgentResult.Failed>(results[1])
  }

  @Test
  fun `tool count aggregates across objectives`() = runTest {
    val objectiveResponses = mapOf(
      "Step 1" to listOf(
        """{"tool": "$TOOL_TAP_ON_POINT", "args": {"x": 100, "y": 200}}""",
        """{"tool": "$TOOL_TAP_ON_POINT", "args": {"x": 110, "y": 210}}""",
        """{"complete": true, "summary": "Step 1 done"}""",
      ),
      "Step 2" to listOf(
        """{"tool": "swipe", "args": {"direction": "UP"}}""",
        """{"complete": true, "summary": "Step 2 done"}""",
      ),
    )

    val objectives = listOf("Step 1", "Step 2")
    var totalToolExecutions = 0

    val mockAgent = object : TrailblazeAgent {
      override fun runTrailblazeTools(
        tools: List<TrailblazeTool>,
        traceId: TraceId?,
        screenState: ScreenState?,
        elementComparator: ElementComparator,
        screenStateProvider: (() -> ScreenState)?,
      ): TrailblazeAgent.RunTrailblazeToolsResult {
        totalToolExecutions++
        return TrailblazeAgent.RunTrailblazeToolsResult(
          inputTools = tools,
          executedTools = tools,
          result = TrailblazeToolResult.Success(),
        )
      }
    }

    val samplingSource = ObjectiveSamplingSource(objectiveResponses)

    for (objective in objectives) {
      val agent = DirectMcpAgent(
        samplingSource = samplingSource,
        trailblazeAgent = mockAgent,
        screenStateProvider = { createMockScreenState() },
        elementComparator = NoOpElementComparator,
        maxIterations = 10,
        includeScreenshots = false,
      )
      agent.run(objective)
    }

    assertEquals(3, totalToolExecutions, "Should have 2 taps + 1 swipe = 3 tool executions")
  }

  // ==========================================================================
  // State Persistence Tests
  // ==========================================================================

  @Test
  fun `objectives share state via common TrailblazeAgent`() = runTest {
    val objectiveResponses = mapOf(
      "Tap button A" to listOf(
        """{"tool": "$TOOL_TAP_ON_POINT", "args": {"x": 100, "y": 200}}""",
        """{"complete": true, "summary": "Tapped A"}""",
      ),
      "Tap button B" to listOf(
        """{"tool": "$TOOL_TAP_ON_POINT", "args": {"x": 300, "y": 400}}""",
        """{"complete": true, "summary": "Tapped B"}""",
      ),
    )

    val tappedCoordinates = mutableListOf<Pair<Int, Int>>()
    val sharedAgent = object : TrailblazeAgent {
      override fun runTrailblazeTools(
        tools: List<TrailblazeTool>,
        traceId: TraceId?,
        screenState: ScreenState?,
        elementComparator: ElementComparator,
        screenStateProvider: (() -> ScreenState)?,
      ): TrailblazeAgent.RunTrailblazeToolsResult {
        // Track coordinates (simplified - in real impl would extract from tool)
        tappedCoordinates.add(100 to 200) // Placeholder
        return TrailblazeAgent.RunTrailblazeToolsResult(
          inputTools = tools,
          executedTools = tools,
          result = TrailblazeToolResult.Success(),
        )
      }
    }

    val samplingSource = ObjectiveSamplingSource(objectiveResponses)

    for (objective in listOf("Tap button A", "Tap button B")) {
      val agent = DirectMcpAgent(
        samplingSource = samplingSource,
        trailblazeAgent = sharedAgent,
        screenStateProvider = { createMockScreenState() },
        elementComparator = NoOpElementComparator,
        maxIterations = 10,
        includeScreenshots = false,
      )
      agent.run(objective)
    }

    assertEquals(2, tappedCoordinates.size, "Both objectives should have executed")
  }

  // ==========================================================================
  // Complex Workflow Tests
  // ==========================================================================

  @Test
  fun `complex login workflow with multiple objectives`() = runTest {
    val loginWorkflow = mapOf(
      "Open the app" to listOf(
        """{"tool": "$TOOL_TAP_ON_POINT", "args": {"x": 540, "y": 1200}}""",
        """{"complete": true, "summary": "App launched"}""",
      ),
      "Enter username" to listOf(
        """{"tool": "$TOOL_TAP_ON_POINT", "args": {"x": 540, "y": 600}}""",
        """{"tool": "$TOOL_INPUT_TEXT", "args": {"text": "testuser@example.com"}}""",
        """{"complete": true, "summary": "Username entered"}""",
      ),
      "Enter password" to listOf(
        """{"tool": "$TOOL_TAP_ON_POINT", "args": {"x": 540, "y": 800}}""",
        """{"tool": "$TOOL_INPUT_TEXT", "args": {"text": "password123"}}""",
        """{"complete": true, "summary": "Password entered"}""",
      ),
      "Tap login button" to listOf(
        """{"tool": "$TOOL_TAP_ON_POINT", "args": {"x": 540, "y": 1000}}""",
        """{"complete": true, "summary": "Logged in successfully"}""",
      ),
    )

    val objectives = listOf(
      "Open the app",
      "Enter username",
      "Enter password",
      "Tap login button",
    )

    val samplingSource = ObjectiveSamplingSource(loginWorkflow)
    val results = mutableListOf<AgentResult>()

    for (objective in objectives) {
      val agent = createAgent(samplingSource)
      results.add(agent.run(objective))
    }

    assertEquals(4, results.size)
    assertTrue(results.all { it is AgentResult.Success }, "All login steps should succeed")

    Console.log("\n=== Login Workflow Results ===")
    objectives.zip(results).forEach { (objective, result) ->
      assertIs<AgentResult.Success>(result)
      Console.log("  $objective: ${result.summary}")
    }
  }

  // ==========================================================================
  // Helper Classes
  // ==========================================================================

  private fun createAgent(samplingSource: SamplingSource): DirectMcpAgent {
    return DirectMcpAgent(
      samplingSource = samplingSource,
      trailblazeAgent = SuccessTrailblazeAgent,
      screenStateProvider = { createMockScreenState() },
      elementComparator = NoOpElementComparator,
      maxIterations = 10,
      includeScreenshots = false,
    )
  }

  private fun createMockScreenState(): ScreenState {
    val emptyVh = ViewHierarchyTreeNode()
    return object : ScreenState {
      override val screenshotBytes: ByteArray? = null
      override val deviceWidth: Int = 1080
      override val deviceHeight: Int = 1920
      override val viewHierarchy: ViewHierarchyTreeNode = emptyVh
      override val trailblazeDevicePlatform: TrailblazeDevicePlatform = TrailblazeDevicePlatform.ANDROID
      override val deviceClassifiers: List<TrailblazeDeviceClassifier> = emptyList()
    }
  }

  private object NoOpElementComparator : ElementComparator {
    override fun getElementValue(prompt: String): String? = null
    override fun evaluateBoolean(statement: String) = BooleanAssertionTrailblazeTool(reason = "NoOp", result = false)
    override fun evaluateString(query: String) = StringEvaluationTrailblazeTool(reason = "NoOp", result = "")
    override fun extractNumberFromString(input: String): Double? = null
  }

  private object SuccessTrailblazeAgent : TrailblazeAgent {
    override fun runTrailblazeTools(
      tools: List<TrailblazeTool>,
      traceId: TraceId?,
      screenState: ScreenState?,
      elementComparator: ElementComparator,
      screenStateProvider: (() -> ScreenState)?,
    ): TrailblazeAgent.RunTrailblazeToolsResult {
      return TrailblazeAgent.RunTrailblazeToolsResult(
        inputTools = tools,
        executedTools = tools,
        result = TrailblazeToolResult.Success(),
      )
    }
  }

  /**
   * SamplingSource that returns responses based on the objective in the user message.
   */
  private class ObjectiveSamplingSource(
    private val objectiveResponses: Map<String, List<String>>,
  ) : SamplingSource {
    private val responseQueues = objectiveResponses.mapValues { it.value.toMutableList() }
    private var currentObjective: String? = null

    override suspend fun sampleText(
      systemPrompt: String,
      userMessage: String,
      screenshotBytes: ByteArray?,
      maxTokens: Int,
      traceId: TraceId?,
      screenContext: ScreenContext?,
    ): SamplingResult = getResponseFor(userMessage)

    override suspend fun sampleToolCall(
      systemPrompt: String,
      userMessage: String,
      tools: List<TrailblazeToolDescriptor>,
      screenshotBytes: ByteArray?,
      maxTokens: Int,
      traceId: TraceId?,
      screenContext: ScreenContext?,
    ): SamplingResult = getResponseFor(userMessage)

    private fun getResponseFor(userMessage: String): SamplingResult {
      // Find matching objective
      val objective = objectiveResponses.keys.find { userMessage.contains(it, ignoreCase = true) }
        ?: currentObjective

      if (objective != null) {
        currentObjective = objective
        val queue = responseQueues[objective]
        if (queue != null && queue.isNotEmpty()) {
          return parseJsonResponse(queue.removeFirst())
        }
      }

      return SamplingResult.ToolCall(
        toolName = DirectMcpAgent.TOOL_OBJECTIVE_STATUS,
        arguments = buildJsonObject {
          put("description", JsonPrimitive("Test objective"))
          put("explanation", JsonPrimitive("No response for: $userMessage"))
          put("status", JsonPrimitive("FAILED"))
        },
      )
    }

    private fun parseJsonResponse(json: String): SamplingResult {
      return try {
        val jsonObject = Json.decodeFromString<JsonObject>(json)
        
        // Check for completion signal
        if (jsonObject["complete"]?.toString()?.contains("true") == true) {
          val summary = jsonObject["summary"]?.let { 
            it.toString().trim('"') 
          } ?: "Objective completed"
          return SamplingResult.ToolCall(
            toolName = DirectMcpAgent.TOOL_OBJECTIVE_STATUS,
            arguments = buildJsonObject {
              put("description", JsonPrimitive("Test objective"))
              put("explanation", JsonPrimitive(summary))
              put("status", JsonPrimitive("COMPLETED"))
            },
          )
        }

        // Check for failure signal
        if (jsonObject["failed"]?.toString()?.contains("true") == true) {
          val reason = jsonObject["reason"]?.let { 
            it.toString().trim('"') 
          } ?: "Objective failed"
          return SamplingResult.ToolCall(
            toolName = DirectMcpAgent.TOOL_OBJECTIVE_STATUS,
            arguments = buildJsonObject {
              put("description", JsonPrimitive("Test objective"))
              put("explanation", JsonPrimitive(reason))
              put("status", JsonPrimitive("FAILED"))
            },
          )
        }

        // Check for tool call
        val toolName = jsonObject["tool"]?.toString()?.trim('"')
        if (toolName != null) {
          val args = jsonObject["args"] as? JsonObject 
            ?: JsonObject(emptyMap())
          return SamplingResult.ToolCall(
            toolName = toolName,
            arguments = args,
          )
        }

        // Fallback to text
        SamplingResult.Text(json)
      } catch (e: Exception) {
        SamplingResult.Text(json)
      }
    }

    override fun isAvailable(): Boolean = true
    override fun description(): String = "ObjectiveSamplingSource"
  }
}
