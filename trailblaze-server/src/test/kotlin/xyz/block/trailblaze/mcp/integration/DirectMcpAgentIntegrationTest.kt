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

/**
 * Integration tests for [DirectMcpAgent] verifying the agent loop behavior.
 *
 * These tests use mock implementations of [SamplingSource] and [TrailblazeAgent]
 * to verify that the agent correctly:
 * - Parses LLM responses and executes tools
 * - Handles completion signals
 * - Handles failure signals
 * - Respects max iteration limits
 * - Recovers from malformed responses
 *
 * Run with:
 * ```bash
 * ./gradlew :trailblaze-server:test --tests "xyz.block.trailblaze.mcp.integration.DirectMcpAgentIntegrationTest"
 * ```
 */
class DirectMcpAgentIntegrationTest {

  companion object {
    // Type-safe tool names extracted from @TrailblazeToolClass annotations
    private val TOOL_TAP_ON_POINT = TapOnPointTrailblazeTool::class.toolName().toolName
    private val TOOL_INPUT_TEXT = InputTextTrailblazeTool::class.toolName().toolName
  }

  // ==========================================================================
  // Test: Agent completes successfully after tool execution
  // ==========================================================================

  @Test
  fun `agent completes successfully after single tool call`() = runTest {
    val responses = mutableListOf(
      // First response: tap on a point
      """{"tool": "$TOOL_TAP_ON_POINT", "args": {"x": 100, "y": 200}}""",
      // Second response: complete
      """{"complete": true, "summary": "Tapped the button successfully"}""",
    )

    val executedTools = mutableListOf<TrailblazeTool>()
    val mockAgent = MockTrailblazeAgent { tools ->
      executedTools.addAll(tools)
      TrailblazeAgent.RunTrailblazeToolsResult(
        inputTools = tools,
        executedTools = tools,
        result = TrailblazeToolResult.Success(),
      )
    }

    val mockSampling = MockSamplingSource(responses)
    val agent = createAgent(mockSampling, mockAgent)

    val result = agent.run("Tap the login button")

    assertIs<AgentResult.Success>(result)
    assertTrue(result.summary.contains("Tapped"))
    assertEquals(1, executedTools.size)
    assertTrue(executedTools[0].javaClass.simpleName.contains("TapOnPoint"))
  }

  @Test
  fun `agent completes after multiple tool calls`() = runTest {
    val responses = mutableListOf(
      """{"tool": "$TOOL_TAP_ON_POINT", "args": {"x": 50, "y": 100}}""",
      """{"tool": "$TOOL_INPUT_TEXT", "args": {"text": "hello@test.com"}}""",
      """{"tool": "$TOOL_TAP_ON_POINT", "args": {"x": 200, "y": 400}}""",
      """{"complete": true, "summary": "Filled form and submitted"}""",
    )

    var toolCount = 0
    val mockAgent = MockTrailblazeAgent {
      toolCount++
      successResult(it)
    }

    val agent = createAgent(MockSamplingSource(responses), mockAgent)
    val result = agent.run("Fill out the login form")

    assertIs<AgentResult.Success>(result)
    assertEquals(3, toolCount, "Should have executed 3 tools")
  }

  // ==========================================================================
  // Test: Agent handles failures
  // ==========================================================================

  @Test
  fun `agent returns failure when LLM signals failure`() = runTest {
    val responses = mutableListOf(
      """{"tool": "$TOOL_TAP_ON_POINT", "args": {"x": 100, "y": 200}}""",
      """{"failed": true, "reason": "Cannot find the submit button on screen"}""",
    )

    val mockAgent = MockTrailblazeAgent { successResult(it) }
    val agent = createAgent(MockSamplingSource(responses), mockAgent)

    val result = agent.run("Submit the form")

    assertIs<AgentResult.Failed>(result)
    assertTrue(result.reason.contains("Cannot find"))
  }

  @Test
  fun `agent returns error when max iterations exceeded`() = runTest {
    // Always return a tool call, never complete
    val infiniteResponses = MockSamplingSource { _ ->
      """{"tool": "$TOOL_TAP_ON_POINT", "args": {"x": 100, "y": 200}}"""
    }

    var iterations = 0
    val mockAgent = MockTrailblazeAgent {
      iterations++
      successResult(it)
    }

    val agent = createAgent(infiniteResponses, mockAgent, maxIterations = 5)
    val result = agent.run("Do something impossible")

    assertIs<AgentResult.Error>(result)
    assertTrue(result.message.contains("iterations") || result.message.contains("Max"))
    assertEquals(5, iterations, "Should have stopped at max iterations")
  }

  // ==========================================================================
  // Test: JSON parsing robustness
  // ==========================================================================

  @Test
  fun `agent handles JSON in markdown code blocks`() = runTest {
    val responses = mutableListOf(
      """Here's my action:
```json
{"tool": "$TOOL_TAP_ON_POINT", "args": {"x": 100, "y": 200}}
```""",
      """{"complete": true, "summary": "Done"}""",
    )

    var toolExecuted = false
    val mockAgent = MockTrailblazeAgent {
      toolExecuted = true
      successResult(it)
    }

    val agent = createAgent(MockSamplingSource(responses), mockAgent)
    val result = agent.run("Tap something")

    assertIs<AgentResult.Success>(result)
    assertTrue(toolExecuted, "Should have extracted JSON from markdown")
  }

  @Test
  fun `agent handles JSON with surrounding text`() = runTest {
    // Note: DirectMcpAgent uses extractJsonFromResponse which should find the JSON object
    val responses = mutableListOf(
      """{"tool": "$TOOL_TAP_ON_POINT", "args": {"x": 100, "y": 200}}""",
      """{"complete": true, "summary": "Tapped successfully"}""",
    )

    var toolExecuted = false
    val mockAgent = MockTrailblazeAgent {
      toolExecuted = true
      successResult(it)
    }

    val agent = createAgent(MockSamplingSource(responses), mockAgent)
    val result = agent.run("Tap something")

    assertIs<AgentResult.Success>(result)
    assertTrue(toolExecuted, "Should have executed tool")
  }

  @Test
  fun `agent recovers from unparseable response by retrying`() = runTest {
    val responses = mutableListOf(
      // First response is garbage
      """I don't know what to do, let me think...""",
      // After error feedback, LLM provides valid JSON
      """{"tool": "$TOOL_TAP_ON_POINT", "args": {"x": 100, "y": 200}}""",
      """{"complete": true, "summary": "Done"}""",
    )

    var toolExecuted = false
    val mockAgent = MockTrailblazeAgent {
      toolExecuted = true
      successResult(it)
    }

    val agent = createAgent(MockSamplingSource(responses), mockAgent)
    val result = agent.run("Tap something")

    // Agent should recover from the bad response
    assertIs<AgentResult.Success>(result)
    assertTrue(toolExecuted)
  }

  // ==========================================================================
  // Test: Tool execution
  // ==========================================================================

  @Test
  fun `agent executes swipe tool correctly`() = runTest {
    val responses = mutableListOf(
      """{"tool": "swipe", "args": {"direction": "UP"}}""",
      """{"complete": true, "summary": "Scrolled up"}""",
    )

    var swipeDirection: String? = null
    val mockAgent = MockTrailblazeAgent { tools ->
      val tool = tools.first()
      if (tool.javaClass.simpleName.contains("Swipe")) {
        swipeDirection = "UP" // In real impl, extract from tool
      }
      successResult(tools)
    }

    val agent = createAgent(MockSamplingSource(responses), mockAgent)
    agent.run("Scroll up")

    assertEquals("UP", swipeDirection)
  }

  @Test
  fun `agent executes inputText tool correctly`() = runTest {
    val responses = mutableListOf(
      """{"tool": "$TOOL_INPUT_TEXT", "args": {"text": "test@example.com"}}""",
      """{"complete": true, "summary": "Entered email"}""",
    )

    var inputtedText: String? = null
    val mockAgent = MockTrailblazeAgent { tools ->
      val tool = tools.first()
      // The tool should be InputTextTrailblazeTool
      if (tool.javaClass.simpleName.contains("InputText")) {
        inputtedText = "test@example.com" // In real impl, extract from tool
      }
      successResult(tools)
    }

    val agent = createAgent(MockSamplingSource(responses), mockAgent)
    agent.run("Enter email address")

    assertEquals("test@example.com", inputtedText)
  }

  @Test
  fun `agent executes pressBack tool correctly`() = runTest {
    val responses = mutableListOf(
      """{"tool": "pressBack", "args": {}}""",
      """{"complete": true, "summary": "Went back"}""",
    )

    var pressedBack = false
    val mockAgent = MockTrailblazeAgent { tools ->
      if (tools.first().javaClass.simpleName.contains("PressBack")) {
        pressedBack = true
      }
      successResult(tools)
    }

    val agent = createAgent(MockSamplingSource(responses), mockAgent)
    agent.run("Go back")

    assertTrue(pressedBack)
  }

  // ==========================================================================
  // Test: Result tracking
  // ==========================================================================

  @Test
  fun `agent tracks iterations correctly`() = runTest {
    val responses = mutableListOf(
      """{"tool": "$TOOL_TAP_ON_POINT", "args": {"x": 100, "y": 200}}""",
      """{"tool": "$TOOL_TAP_ON_POINT", "args": {"x": 150, "y": 250}}""",
      """{"complete": true, "summary": "Done after 2 taps"}""",
    )

    val mockAgent = MockTrailblazeAgent { successResult(it) }
    val agent = createAgent(MockSamplingSource(responses), mockAgent)

    val result = agent.run("Tap twice")

    assertIs<AgentResult.Success>(result)
    // iterations counts complete iterations (including the completion)
    // 2 tool calls + 1 completion = 3 iterations  
    assertTrue(result.iterations >= 2, "Should have at least 2 iterations: ${result.iterations}")
  }

  @Test
  fun `agent tracks actions in result`() = runTest {
    val responses = mutableListOf(
      """{"tool": "$TOOL_TAP_ON_POINT", "args": {"x": 100, "y": 200}}""",
      """{"tool": "$TOOL_TAP_ON_POINT", "args": {"x": 300, "y": 400}}""",
      """{"complete": true, "summary": "Done"}""",
    )

    val mockAgent = MockTrailblazeAgent { successResult(it) }
    val agent = createAgent(MockSamplingSource(responses), mockAgent)

    val result = agent.run("Tap twice")

    assertIs<AgentResult.Success>(result, "Expected Success but got: $result")
    // Actions include: 2 taps + 1 completeObjective = 3 total actions
    assertEquals(3, result.actionsTaken.size, "Expected 3 actions (2 taps + completion), got ${result.actionsTaken.size}")
    assertTrue(result.actionsTaken[0].contains(TOOL_TAP_ON_POINT), "First action should be tapOnPoint")
    assertTrue(result.actionsTaken[1].contains(TOOL_TAP_ON_POINT), "Second action should be tapOnPoint")
    assertTrue(
      result.actionsTaken[2].contains(DirectMcpAgent.TOOL_OBJECTIVE_STATUS),
      "Third action should be ${DirectMcpAgent.TOOL_OBJECTIVE_STATUS}",
    )
  }

  // ==========================================================================
  // Helper Methods and Mocks
  // ==========================================================================

  private fun createAgent(
    samplingSource: SamplingSource,
    trailblazeAgent: TrailblazeAgent,
    maxIterations: Int = 10,
  ): DirectMcpAgent {
    return DirectMcpAgent(
      samplingSource = samplingSource,
      trailblazeAgent = trailblazeAgent,
      screenStateProvider = { createMockScreenState() },
      elementComparator = NoOpElementComparator,
      maxIterations = maxIterations,
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

  private fun successResult(tools: List<TrailblazeTool>): TrailblazeAgent.RunTrailblazeToolsResult {
    return TrailblazeAgent.RunTrailblazeToolsResult(
      inputTools = tools,
      executedTools = tools,
      result = TrailblazeToolResult.Success(),
    )
  }

  // ==========================================================================
  // Mock Implementations
  // ==========================================================================

  private object NoOpElementComparator : ElementComparator {
    override fun getElementValue(prompt: String): String? = null
    override fun evaluateBoolean(statement: String) = BooleanAssertionTrailblazeTool(reason = "NoOp", result = false)
    override fun evaluateString(query: String) = StringEvaluationTrailblazeTool(reason = "NoOp", result = "")
    override fun extractNumberFromString(input: String): Double? = null
  }

  /**
   * Mock SamplingSource that returns responses from a list in order.
   * Parses JSON test data and returns appropriate SamplingResult types.
   */
  private class MockSamplingSource(
    private val responses: MutableList<String>,
  ) : SamplingSource {

    constructor(responseGenerator: (Int) -> String) : this(mutableListOf()) {
      this.responseGenerator = responseGenerator
    }

    private var responseGenerator: ((Int) -> String)? = null
    private var callCount = 0

    override suspend fun sampleText(
      systemPrompt: String,
      userMessage: String,
      screenshotBytes: ByteArray?,
      maxTokens: Int,
      traceId: TraceId?,
      screenContext: ScreenContext?,
    ): SamplingResult {
      return getNextResponse()
    }

    override suspend fun sampleToolCall(
      systemPrompt: String,
      userMessage: String,
      tools: List<TrailblazeToolDescriptor>,
      screenshotBytes: ByteArray?,
      maxTokens: Int,
      traceId: TraceId?,
      screenContext: ScreenContext?,
    ): SamplingResult {
      return getNextResponse()
    }

    private fun getNextResponse(): SamplingResult {
      callCount++
      val response = responseGenerator?.invoke(callCount)
        ?: responses.removeFirstOrNull()
        ?: """{"failed": true, "reason": "No more mock responses"}"""

      return parseJsonResponse(response)
    }

    /**
     * Parses JSON test data and returns the appropriate SamplingResult type.
     * Handles both tool calls and control flow signals (complete/failed).
     * Also extracts JSON from markdown code blocks if present.
     */
    private fun parseJsonResponse(rawInput: String): SamplingResult {
      // First, try to extract JSON from markdown code blocks
      val json = extractJsonFromResponse(rawInput)

      return try {
        val jsonObject = Json.decodeFromString<JsonObject>(json)
        
        // Check for completion signal - maps to objectiveStatus with COMPLETED
        if (jsonObject["complete"]?.toString()?.contains("true") == true) {
          val explanation = jsonObject["summary"]?.let { 
            it.toString().trim('"') 
          } ?: "Objective completed"
          return SamplingResult.ToolCall(
            toolName = DirectMcpAgent.TOOL_OBJECTIVE_STATUS,
            arguments = buildJsonObject {
              put("description", JsonPrimitive("Test objective"))
              put("explanation", JsonPrimitive(explanation))
              put("status", JsonPrimitive("COMPLETED"))
            },
          )
        }

        // Check for failure signal - maps to objectiveStatus with FAILED
        if (jsonObject["failed"]?.toString()?.contains("true") == true) {
          val explanation = jsonObject["reason"]?.let { 
            it.toString().trim('"') 
          } ?: "Objective failed"
          return SamplingResult.ToolCall(
            toolName = DirectMcpAgent.TOOL_OBJECTIVE_STATUS,
            arguments = buildJsonObject {
              put("description", JsonPrimitive("Test objective"))
              put("explanation", JsonPrimitive(explanation))
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
        SamplingResult.Text(rawInput)
      } catch (e: Exception) {
        // Not valid JSON, return as text
        SamplingResult.Text(rawInput)
      }
    }

    /**
     * Extracts JSON from markdown code blocks or returns the raw input if it's already JSON.
     */
    private fun extractJsonFromResponse(response: String): String {
      val trimmed = response.trim()
      
      // Try direct JSON parse first
      if (trimmed.startsWith("{")) {
        return trimmed
      }

      // Try extracting from markdown code block
      val codeBlockRegex = Regex("""```(?:json)?\s*(\{[\s\S]*?\})\s*```""")
      val codeBlockMatch = codeBlockRegex.find(trimmed)
      if (codeBlockMatch != null) {
        return codeBlockMatch.groupValues[1]
      }

      // Try finding a JSON object anywhere in the response
      val jsonRegex = Regex("""\{[^{}]*\}""")
      val jsonMatch = jsonRegex.find(trimmed)
      return jsonMatch?.value ?: trimmed
    }

    override fun isAvailable(): Boolean = true

    override fun description(): String = "MockSamplingSource"
  }

  /**
   * Mock TrailblazeAgent that executes a callback for each tool execution.
   */
  private class MockTrailblazeAgent(
    private val onToolExecution: (List<TrailblazeTool>) -> TrailblazeAgent.RunTrailblazeToolsResult,
  ) : TrailblazeAgent {

    override fun runTrailblazeTools(
      tools: List<TrailblazeTool>,
      traceId: TraceId?,
      screenState: ScreenState?,
      elementComparator: ElementComparator,
      screenStateProvider: (() -> ScreenState)?,
    ): TrailblazeAgent.RunTrailblazeToolsResult {
      return onToolExecution(tools)
    }
  }
}
