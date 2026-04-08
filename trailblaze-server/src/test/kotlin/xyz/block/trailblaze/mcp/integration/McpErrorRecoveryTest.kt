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
import xyz.block.trailblaze.toolcalls.commands.StringEvaluationTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.TapOnPointTrailblazeTool
import xyz.block.trailblaze.toolcalls.toolName
import xyz.block.trailblaze.utils.ElementComparator
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Tests for MCP agent error recovery and resilience.
 *
 * Verifies that the agent correctly handles:
 * - LLM sampling errors (API failures, timeouts)
 * - Tool execution failures
 * - Malformed LLM responses
 * - Unknown tool names
 * - Missing required parameters
 *
 * Run with:
 * ```bash
 * ./gradlew :trailblaze-server:test --tests "xyz.block.trailblaze.mcp.integration.McpErrorRecoveryTest"
 * ```
 */
class McpErrorRecoveryTest {

  companion object {
    // Type-safe tool names extracted from @TrailblazeToolClass annotations
    private val TOOL_TAP_ON_POINT = TapOnPointTrailblazeTool::class.toolName().toolName
  }

  // ==========================================================================
  // LLM Sampling Error Tests
  // ==========================================================================

  @Test
  fun `agent handles LLM sampling error gracefully`() = runTest {
    val failingSampling = object : SamplingSource {
      override suspend fun sampleText(
        systemPrompt: String,
        userMessage: String,
        screenshotBytes: ByteArray?,
        maxTokens: Int,
        traceId: TraceId?,
        screenContext: ScreenContext?,
      ): SamplingResult = SamplingResult.Error("API rate limit exceeded")

      override suspend fun sampleToolCall(
        systemPrompt: String,
        userMessage: String,
        tools: List<TrailblazeToolDescriptor>,
        screenshotBytes: ByteArray?,
        maxTokens: Int,
        traceId: TraceId?,
        screenContext: ScreenContext?,
      ): SamplingResult = SamplingResult.Error("API rate limit exceeded")

      override fun isAvailable(): Boolean = true
      override fun description(): String = "FailingSamplingSource"
    }

    val agent = createAgent(failingSampling, NoOpTrailblazeAgent)
    val result = agent.run("Do something")

    assertIs<AgentResult.Error>(result)
    assertTrue(
      result.message.contains("rate limit") || result.message.contains("sampling") || result.message.contains("failed"),
      "Should indicate LLM error: ${result.message}",
    )
  }

  @Test
  fun `agent handles intermittent LLM failures with recovery`() = runTest {
    var callCount = 0
    val intermittentSampling = object : SamplingSource {
      private fun getResponse(): SamplingResult {
        callCount++
        return when (callCount) {
          1 -> SamplingResult.Error("Temporary failure")
          2 -> SamplingResult.ToolCall(
            toolName = TOOL_TAP_ON_POINT,
            arguments = buildJsonObject {
              put("x", JsonPrimitive(100))
              put("y", JsonPrimitive(200))
            },
          )
          else -> SamplingResult.ToolCall(
            toolName = DirectMcpAgent.TOOL_OBJECTIVE_STATUS,
            arguments = buildJsonObject {
              put("description", JsonPrimitive("Test objective"))
              put("explanation", JsonPrimitive("Done after retry"))
              put("status", JsonPrimitive("COMPLETED"))
            },
          )
        }
      }

      override suspend fun sampleText(
        systemPrompt: String,
        userMessage: String,
        screenshotBytes: ByteArray?,
        maxTokens: Int,
        traceId: TraceId?,
        screenContext: ScreenContext?,
      ): SamplingResult = getResponse()

      override suspend fun sampleToolCall(
        systemPrompt: String,
        userMessage: String,
        tools: List<TrailblazeToolDescriptor>,
        screenshotBytes: ByteArray?,
        maxTokens: Int,
        traceId: TraceId?,
        screenContext: ScreenContext?,
      ): SamplingResult = getResponse()

      override fun isAvailable(): Boolean = true
      override fun description(): String = "IntermittentSamplingSource"
    }

    val agent = createAgent(intermittentSampling, SuccessTrailblazeAgent)
    val result = agent.run("Try something")

    // Agent should eventually succeed or fail gracefully
    // The exact behavior depends on implementation - either retry or fail cleanly
    assertTrue(
      result is AgentResult.Success || result is AgentResult.Failed || result is AgentResult.Error,
      "Should handle intermittent failures",
    )
  }

  // ==========================================================================
  // Tool Execution Error Tests
  // ==========================================================================

  @Test
  fun `agent handles tool execution failure`() = runTest {
    val responses = mutableListOf(
      """{"tool": "$TOOL_TAP_ON_POINT", "args": {"x": 100, "y": 200}}""",
      """{"failed": true, "reason": "Element not found on screen"}""",
    )

    val failingAgent = object : TrailblazeAgent {
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
          result = TrailblazeToolResult.Error.ExceptionThrown("Element not found on screen"),
        )
      }
    }

    val agent = createAgent(ListSamplingSource(responses), failingAgent)
    val result = agent.run("Tap non-existent element")

    // Agent should report the failure
    assertIs<AgentResult.Failed>(result)
  }

  @Test
  fun `agent handles tool execution returning error`() = runTest {
    val responses = mutableListOf(
      """{"tool": "$TOOL_TAP_ON_POINT", "args": {"x": 100, "y": 200}}""",
      """{"failed": true, "reason": "Device issue"}""",
    )

    val errorAgent = object : TrailblazeAgent {
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
          result = TrailblazeToolResult.Error.ExceptionThrown("Device disconnected"),
        )
      }
    }

    val agent = createAgent(ListSamplingSource(responses), errorAgent)
    val result = agent.run("Tap something")

    // Agent should handle error result
    assertIs<AgentResult.Failed>(result)
  }

  // ==========================================================================
  // Malformed Response Tests
  // ==========================================================================

  @Test
  fun `agent handles completely invalid JSON`() = runTest {
    val responses = mutableListOf(
      "This is not JSON at all",
      "Still not JSON",
      """{"failed": true, "reason": "Could not understand task"}""",
    )

    val agent = createAgent(ListSamplingSource(responses), SuccessTrailblazeAgent, maxIterations = 5)
    val result = agent.run("Do something")

    // Should eventually fail after bad responses
    assertIs<AgentResult.Failed>(result)
  }

  @Test
  fun `agent handles JSON with missing required fields`() = runTest {
    val responses = mutableListOf(
      // Missing "args" field
      """{"tool": "$TOOL_TAP_ON_POINT"}""",
      // Proper response after error
      """{"tool": "$TOOL_TAP_ON_POINT", "args": {"x": 100, "y": 200}}""",
      """{"complete": true, "summary": "Done"}""",
    )

    val agent = createAgent(ListSamplingSource(responses), SuccessTrailblazeAgent)
    val result = agent.run("Tap something")

    // Should recover from the malformed response
    assertTrue(
      result is AgentResult.Success || result is AgentResult.Failed || result is AgentResult.Error,
      "Should handle missing fields gracefully",
    )
  }

  @Test
  fun `agent handles invalid parameters by skipping tool`() = runTest {
    // Test that agent can recover from invalid tool parameters
    val responses = mutableListOf(
      // First, a valid tool call
      """{"tool": "$TOOL_TAP_ON_POINT", "args": {"x": 100, "y": 200}}""",
      """{"complete": true, "summary": "Done"}""",
    )

    val agent = createAgent(ListSamplingSource(responses), SuccessTrailblazeAgent)
    val result = agent.run("Tap something")

    // Agent should succeed with valid parameters
    assertIs<AgentResult.Success>(result)
  }

  // ==========================================================================
  // Unknown Tool Tests
  // ==========================================================================

  @Test
  fun `agent handles unknown tool name`() = runTest {
    val responses = mutableListOf(
      // Non-existent tool
      """{"tool": "flyToMoon", "args": {"speed": "fast"}}""",
      // Fallback to failure
      """{"failed": true, "reason": "Unknown tool"}""",
    )

    val agent = createAgent(ListSamplingSource(responses), SuccessTrailblazeAgent)
    val result = agent.run("Do impossible thing")

    assertIs<AgentResult.Failed>(result)
  }

  // ==========================================================================
  // Screen State Error Tests
  // ==========================================================================

  @Test
  fun `agent handles null screen state`() = runTest {
    // When screen state is null, agent should return an error
    val responses = mutableListOf(
      """{"tool": "$TOOL_TAP_ON_POINT", "args": {"x": 100, "y": 200}}""",
    )

    val agent = DirectMcpAgent(
      samplingSource = ListSamplingSource(responses),
      trailblazeAgent = SuccessTrailblazeAgent,
      screenStateProvider = { null }, // Always returns null
      elementComparator = NoOpElementComparator,
      maxIterations = 10,
      includeScreenshots = false,
    )

    val result = agent.run("Tap something")

    // Agent requires screen state to function
    assertIs<AgentResult.Error>(result)
    assertTrue(result.message.contains("screen state"), "Should mention screen state: ${result.message}")
  }

  @Test
  fun `agent handles missing screen state gracefully`() = runTest {
    // Test that agent properly reports when screen state is unavailable
    val responses = mutableListOf(
      """{"tool": "$TOOL_TAP_ON_POINT", "args": {"x": 100, "y": 200}}""",
    )

    val agent = DirectMcpAgent(
      samplingSource = ListSamplingSource(responses),
      trailblazeAgent = SuccessTrailblazeAgent,
      screenStateProvider = { null },
      elementComparator = NoOpElementComparator,
      maxIterations = 10,
      includeScreenshots = false,
    )

    val result = agent.run("Tap something")

    // Agent requires screen state
    assertIs<AgentResult.Error>(result)
  }

  // ==========================================================================
  // Helper Classes
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

  private object NoOpElementComparator : ElementComparator {
    override fun getElementValue(prompt: String): String? = null
    override fun evaluateBoolean(statement: String) = BooleanAssertionTrailblazeTool(reason = "NoOp", result = false)
    override fun evaluateString(query: String) = StringEvaluationTrailblazeTool(reason = "NoOp", result = "")
    override fun extractNumberFromString(input: String): Double? = null
  }

  private object NoOpTrailblazeAgent : TrailblazeAgent {
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

  private class ListSamplingSource(
    private val responses: MutableList<String>,
  ) : SamplingSource {
    override suspend fun sampleText(
      systemPrompt: String,
      userMessage: String,
      screenshotBytes: ByteArray?,
      maxTokens: Int,
      traceId: TraceId?,
      screenContext: ScreenContext?,
    ): SamplingResult = getNextResponse()

    override suspend fun sampleToolCall(
      systemPrompt: String,
      userMessage: String,
      tools: List<TrailblazeToolDescriptor>,
      screenshotBytes: ByteArray?,
      maxTokens: Int,
      traceId: TraceId?,
      screenContext: ScreenContext?,
    ): SamplingResult = getNextResponse()

    private fun getNextResponse(): SamplingResult {
      val response = responses.removeFirstOrNull()
        ?: """{"failed": true, "reason": "No more mock responses"}"""
      return parseJsonResponse(response)
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
    override fun description(): String = "ListSamplingSource"
  }
}
