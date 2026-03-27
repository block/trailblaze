package xyz.block.trailblaze.mcp.sampling

import io.modelcontextprotocol.kotlin.sdk.server.ServerSession
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Test
import xyz.block.trailblaze.api.ScreenState
import xyz.block.trailblaze.api.ScreenshotScalingConfig
import xyz.block.trailblaze.devices.TrailblazeConnectedDeviceSummary
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.mcp.AgentImplementation
import xyz.block.trailblaze.mcp.TrailblazeMcpBridge
import xyz.block.trailblaze.mcp.TrailblazeMcpSessionContext
import xyz.block.trailblaze.mcp.android.ondevice.rpc.GetScreenStateResponse
import xyz.block.trailblaze.mcp.TrailblazeMcpMode
import xyz.block.trailblaze.mcp.models.McpSessionId
import xyz.block.trailblaze.model.TrailblazeHostAppTarget
import xyz.block.trailblaze.toolcalls.TrailblazeTool
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit tests for [SubagentOrchestrator] JSON parsing and extraction logic.
 *
 * These tests verify that the orchestrator correctly:
 * - Parses tool call JSON from LLM responses
 * - Extracts JSON from various response formats (raw, markdown code blocks)
 * - Handles completion and failure signals
 * - Gracefully handles malformed responses
 */
class SubagentOrchestratorTest {

  /**
   * Creates an orchestrator for testing parsing methods.
   * The mock bridge is not used for parsing tests.
   */
  private fun createOrchestrator(): SubagentOrchestrator {
    val sessionContext = TrailblazeMcpSessionContext(
      mcpServerSession = null as ServerSession?,
      mcpSessionId = McpSessionId("test-session"),
      mode = TrailblazeMcpMode.MCP_CLIENT_AS_AGENT,
    )
    val mockBridge = MockTrailblazeMcpBridge()
    return SubagentOrchestrator(sessionContext, mockBridge)
  }

  // region extractJsonFromResponse tests

  @Test
  fun `extractJsonFromResponse handles direct JSON`() {
    val orchestrator = createOrchestrator()
    val input = """{"tool": "tapOnPoint", "args": {"x": 100, "y": 200}}"""

    val result = orchestrator.extractJsonFromResponse(input)

    assertNotNull(result)
    assertTrue(result.startsWith("{"))
    assertTrue(result.contains("tapOnPoint"))
  }

  @Test
  fun `extractJsonFromResponse handles JSON with leading whitespace`() {
    val orchestrator = createOrchestrator()
    val input = """
      {"tool": "tapOnPoint", "args": {"x": 100, "y": 200}}
    """

    val result = orchestrator.extractJsonFromResponse(input)

    assertNotNull(result)
    assertTrue(result.contains("tapOnPoint"))
  }

  @Test
  fun `extractJsonFromResponse handles markdown code block with json language tag`() {
    val orchestrator = createOrchestrator()
    val input = """
      I'll tap on the login button.

      ```json
      {"tool": "tapOnPoint", "args": {"x": 150, "y": 300}}
      ```

      This should log you in.
    """

    val result = orchestrator.extractJsonFromResponse(input)

    assertNotNull(result)
    assertTrue(result.contains("tapOnPoint"))
    assertTrue(result.contains("150"))
  }

  @Test
  fun `extractJsonFromResponse handles markdown code block without language tag`() {
    val orchestrator = createOrchestrator()
    val input = """
      I'll swipe up to scroll.

      ```
      {"tool": "swipe", "args": {"direction": "up"}}
      ```
    """

    val result = orchestrator.extractJsonFromResponse(input)

    assertNotNull(result)
    assertTrue(result.contains("swipe"))
  }

  @Test
  fun `extractJsonFromResponse extracts simple JSON embedded in text`() {
    val orchestrator = createOrchestrator()
    // Note: The regex \{[^{}]*\} only matches JSON without nested braces
    // Nested JSON like {"args": {"x": 1}} won't be extracted from plain text
    // but will work when wrapped in markdown code blocks
    val input = """
      Based on the screen, I should press back.
      {"tool": "pressBack"}
      Let me know if that worked.
    """

    val result = orchestrator.extractJsonFromResponse(input)

    assertNotNull(result)
    assertTrue(result.contains("pressBack"))
  }

  @Test
  fun `extractJsonFromResponse returns null for no JSON`() {
    val orchestrator = createOrchestrator()
    val input = "I don't see any buttons to press."

    val result = orchestrator.extractJsonFromResponse(input)

    assertNull(result)
  }

  // endregion

  // region parseAction tests - Tool calls

  @Test
  fun `parseAction parses tool call with simple args`() {
    val orchestrator = createOrchestrator()
    val input = """{"tool": "tapOnPoint", "args": {"x": 100, "y": 200}}"""

    val result = orchestrator.parseAction(input)

    assertIs<ParsedAction.Tool>(result)
    assertEquals("tapOnPoint", result.name)
    assertEquals("100", (result.args["x"] as JsonPrimitive).content)
    assertEquals("200", (result.args["y"] as JsonPrimitive).content)
  }

  @Test
  fun `parseAction parses tool call with string args`() {
    val orchestrator = createOrchestrator()
    val input = """{"tool": "inputText", "args": {"text": "hello@example.com"}}"""

    val result = orchestrator.parseAction(input)

    assertIs<ParsedAction.Tool>(result)
    assertEquals("inputText", result.name)
    assertEquals("hello@example.com", (result.args["text"] as JsonPrimitive).content)
  }

  @Test
  fun `parseAction parses tool call with empty args`() {
    val orchestrator = createOrchestrator()
    val input = """{"tool": "pressBack", "args": {}}"""

    val result = orchestrator.parseAction(input)

    assertIs<ParsedAction.Tool>(result)
    assertEquals("pressBack", result.name)
    assertTrue(result.args.isEmpty())
  }

  @Test
  fun `parseAction parses tool call without args field`() {
    val orchestrator = createOrchestrator()
    val input = """{"tool": "pressBack"}"""

    val result = orchestrator.parseAction(input)

    assertIs<ParsedAction.Tool>(result)
    assertEquals("pressBack", result.name)
    assertTrue(result.args.isEmpty())
  }

  @Test
  fun `parseAction parses swipe tool with direction`() {
    val orchestrator = createOrchestrator()
    val input = """{"tool": "swipe", "args": {"direction": "up", "duration": 500}}"""

    val result = orchestrator.parseAction(input)

    assertIs<ParsedAction.Tool>(result)
    assertEquals("swipe", result.name)
    assertEquals("up", (result.args["direction"] as JsonPrimitive).content)
    assertEquals("500", (result.args["duration"] as JsonPrimitive).content)
  }

  // endregion

  // region parseAction tests - Completion signals

  @Test
  fun `parseAction parses completion with summary`() {
    val orchestrator = createOrchestrator()
    val input = """{"complete": true, "summary": "Successfully logged in and navigated to dashboard"}"""

    val result = orchestrator.parseAction(input)

    assertIs<ParsedAction.Complete>(result)
    assertEquals("Successfully logged in and navigated to dashboard", result.summary)
  }

  @Test
  fun `parseAction parses completion with string true`() {
    val orchestrator = createOrchestrator()
    val input = """{"complete": "true", "summary": "Done"}"""

    val result = orchestrator.parseAction(input)

    assertIs<ParsedAction.Complete>(result)
    assertEquals("Done", result.summary)
  }

  @Test
  fun `parseAction parses completion without summary`() {
    val orchestrator = createOrchestrator()
    val input = """{"complete": true}"""

    val result = orchestrator.parseAction(input)

    assertIs<ParsedAction.Complete>(result)
    assertEquals("Objective completed", result.summary)
  }

  // endregion

  // region parseAction tests - Failure signals

  @Test
  fun `parseAction parses failure with reason`() {
    val orchestrator = createOrchestrator()
    val input = """{"failed": true, "reason": "Login button not found on screen"}"""

    val result = orchestrator.parseAction(input)

    assertIs<ParsedAction.Failed>(result)
    assertEquals("Login button not found on screen", result.reason)
  }

  @Test
  fun `parseAction parses failure with string true`() {
    val orchestrator = createOrchestrator()
    val input = """{"failed": "true", "reason": "App crashed"}"""

    val result = orchestrator.parseAction(input)

    assertIs<ParsedAction.Failed>(result)
    assertEquals("App crashed", result.reason)
  }

  @Test
  fun `parseAction parses failure without reason`() {
    val orchestrator = createOrchestrator()
    val input = """{"failed": true}"""

    val result = orchestrator.parseAction(input)

    assertIs<ParsedAction.Failed>(result)
    assertEquals("Unknown failure reason", result.reason)
  }

  // endregion

  // region parseAction tests - Error handling

  @Test
  fun `parseAction returns Unknown for plain text`() {
    val orchestrator = createOrchestrator()
    val input = "I can't find the login button anywhere on the screen."

    val result = orchestrator.parseAction(input)

    assertIs<ParsedAction.Unknown>(result)
    assertEquals(input, result.rawResponse)
  }

  @Test
  fun `parseAction returns Unknown for invalid JSON`() {
    val orchestrator = createOrchestrator()
    val input = """{"tool": "tapOnPoint" "args": }"""

    val result = orchestrator.parseAction(input)

    assertIs<ParsedAction.Unknown>(result)
  }

  @Test
  fun `parseAction returns Unknown for empty JSON object`() {
    val orchestrator = createOrchestrator()
    val input = """{}"""

    val result = orchestrator.parseAction(input)

    assertIs<ParsedAction.Unknown>(result)
  }

  @Test
  fun `parseAction extracts object from array JSON`() {
    val orchestrator = createOrchestrator()
    // Note: The implementation extracts the first JSON object from array
    // This is actually useful as LLMs sometimes wrap responses in arrays
    val input = """[{"tool": "pressBack"}]"""

    val result = orchestrator.parseAction(input)

    // Implementation extracts {"tool": "pressBack"} from the array
    assertIs<ParsedAction.Tool>(result)
    assertEquals("pressBack", result.name)
  }

  // endregion

  // region parseAction tests - Complex scenarios

  @Test
  fun `parseAction handles markdown wrapped JSON for tool call`() {
    val orchestrator = createOrchestrator()
    val input = """
      Based on the view hierarchy, I found the login button at coordinates (150, 300).
      
      ```json
      {"tool": "tapOnPoint", "args": {"x": 150, "y": 300}}
      ```
      
      This should submit the form.
    """

    val result = orchestrator.parseAction(input)

    assertIs<ParsedAction.Tool>(result)
    assertEquals("tapOnPoint", result.name)
  }

  @Test
  fun `parseAction handles markdown wrapped JSON for completion`() {
    val orchestrator = createOrchestrator()
    val input = """
      The balance is now visible on screen. Task complete!
      
      ```json
      {"complete": true, "summary": "Logged in and balance is visible: $150.00"}
      ```
    """

    val result = orchestrator.parseAction(input)

    assertIs<ParsedAction.Complete>(result)
    assertTrue(result.summary.contains("150.00"))
  }

  @Test
  fun `parseAction prioritizes completion over tool if both present`() {
    val orchestrator = createOrchestrator()
    // Edge case: if JSON contains both complete and tool, complete should take priority
    val input = """{"complete": true, "summary": "Done", "tool": "tapOnPoint"}"""

    val result = orchestrator.parseAction(input)

    assertIs<ParsedAction.Complete>(result)
  }

  // endregion
}

/**
 * Mock implementation of TrailblazeMcpBridge for testing.
 * Only the parsing methods are tested, so most bridge methods throw NotImplementedError.
 */
private class MockTrailblazeMcpBridge : TrailblazeMcpBridge {
  override suspend fun selectDevice(trailblazeDeviceId: TrailblazeDeviceId): TrailblazeConnectedDeviceSummary =
    throw NotImplementedError("Not needed for parsing tests")

  override suspend fun getAvailableDevices(): Set<TrailblazeConnectedDeviceSummary> =
    throw NotImplementedError("Not needed for parsing tests")

  override suspend fun getInstalledAppIds(): Set<String> =
    throw NotImplementedError("Not needed for parsing tests")

  override fun getAvailableAppTargets(): Set<TrailblazeHostAppTarget> =
    throw NotImplementedError("Not needed for parsing tests")

  override suspend fun runYaml(yaml: String, startNewSession: Boolean, agentImplementation: AgentImplementation) =
    throw NotImplementedError("Not needed for parsing tests")

  override fun getCurrentlySelectedDeviceId(): TrailblazeDeviceId? =
    throw NotImplementedError("Not needed for parsing tests")

  override suspend fun getCurrentScreenState(): ScreenState? =
    throw NotImplementedError("Not needed for parsing tests")

  override fun getDirectScreenStateProvider(): ((ScreenshotScalingConfig) -> ScreenState)? =
    throw NotImplementedError("Not needed for parsing tests")

  override suspend fun executeTrailblazeTool(tool: TrailblazeTool, blocking: Boolean): String =
    throw NotImplementedError("Not needed for parsing tests")

  override suspend fun endSession(): Boolean =
    throw NotImplementedError("Not needed for parsing tests")

  override fun isOnDeviceInstrumentation(): Boolean =
    throw NotImplementedError("Not needed for parsing tests")

  override fun getDriverType(): TrailblazeDriverType? =
    throw NotImplementedError("Not needed for parsing tests")

  override suspend fun getScreenStateViaRpc(
    includeScreenshot: Boolean,
    screenshotScalingConfig: ScreenshotScalingConfig,
  ): GetScreenStateResponse? =
    throw NotImplementedError("Not needed for parsing tests")

  override fun getActiveSessionId(): SessionId? =
    throw NotImplementedError("Not needed for parsing tests")

  override fun cancelAutomation(deviceId: TrailblazeDeviceId) =
    throw NotImplementedError("Not needed for parsing tests")

  override fun selectAppTarget(appTargetId: String): String? = null

  override fun getCurrentAppTargetId(): String? = null
}
