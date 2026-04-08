package xyz.block.trailblaze.mcp.executor

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Test
import xyz.block.trailblaze.api.ScreenState
import xyz.block.trailblaze.api.ScreenshotScalingConfig
import xyz.block.trailblaze.devices.TrailblazeConnectedDeviceSummary
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.mcp.AgentImplementation
import xyz.block.trailblaze.mcp.TrailblazeMcpBridge
import xyz.block.trailblaze.mcp.android.ondevice.rpc.GetScreenStateResponse
import xyz.block.trailblaze.mcp.toolsets.ToolSetCategory
import xyz.block.trailblaze.model.TrailblazeHostAppTarget
import xyz.block.trailblaze.toolcalls.TrailblazeTool
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Unit tests for [DirectMcpToolExecutor].
 *
 * These tests verify that the executor correctly:
 * - Returns available tools based on configured categories
 * - Executes tools by name with JSON args
 * - Handles unknown tools gracefully
 * - Handles bridge exceptions properly
 */
class DirectMcpToolExecutorTest {

  private fun createExecutor(
    categories: Set<ToolSetCategory> =
      setOf(
        ToolSetCategory.CORE_INTERACTION,
        ToolSetCategory.NAVIGATION,
      ),
    mockBridge: ConfigurableMockBridge = ConfigurableMockBridge(),
  ): DirectMcpToolExecutor = DirectMcpToolExecutor(mockBridge, categories)

  // region getAvailableTools tests

  @Test
  fun `getAvailableTools returns tools for configured categories`() {
    val executor = createExecutor(setOf(ToolSetCategory.CORE_INTERACTION))
    val tools = executor.getAvailableTools()

    assertTrue(tools.isNotEmpty(), "Should have at least one tool")
    assertTrue(
      tools.any { it.name == "tapOnPoint" },
      "Should include tapOnPoint from CORE_INTERACTION",
    )
  }

  @Test
  fun `getAvailableTools returns tools from multiple categories`() {
    val executor = createExecutor(setOf(ToolSetCategory.CORE_INTERACTION, ToolSetCategory.NAVIGATION))
    val tools = executor.getAvailableTools()

    assertTrue(tools.isNotEmpty(), "Should have tools")
    assertTrue(
      tools.any { it.name == "tapOnPoint" },
      "Should include tapOnPoint from CORE_INTERACTION",
    )
    assertTrue(
      tools.any { it.name == "openUrl" },
      "Should include openUrl from NAVIGATION",
    )
  }

  @Test
  fun `getAvailableToolNames returns correct set`() {
    val executor = createExecutor(setOf(ToolSetCategory.CORE_INTERACTION))
    val names = executor.getAvailableToolNames()

    assertTrue("tapOnPoint" in names, "Should include tapOnPoint")
    assertTrue("swipe" in names, "Should include swipe")
  }

  @Test
  fun `isToolAvailable returns true for available tools`() {
    val executor = createExecutor()

    assertTrue(executor.isToolAvailable("tapOnPoint"), "tapOnPoint should be available")
  }

  @Test
  fun `isToolAvailable returns false for unavailable tools`() {
    // Only include CORE_INTERACTION, not MEMORY
    val executor = createExecutor(setOf(ToolSetCategory.CORE_INTERACTION))

    // rememberText is in MEMORY, not CORE_INTERACTION
    assertFalse(executor.isToolAvailable("rememberText"), "rememberText should not be available")
  }

  @Test
  fun `isToolAvailable returns false for unknown tools`() {
    val executor = createExecutor()

    assertFalse(executor.isToolAvailable("unknownTool"), "unknownTool should not be available")
  }

  // endregion

  // region executeToolByName tests

  @Test
  fun `executeToolByName returns ToolNotFound for unknown tool`() =
    runTest {
      val executor = createExecutor()
      val args = buildJsonObject {}

      val result = executor.executeToolByName("unknownTool", args)

      assertIs<ToolExecutionResult.ToolNotFound>(result)
      assertEquals("unknownTool", result.requestedTool)
      assertTrue(result.availableTools.contains("tapOnPoint"), "Should list available tools")
    }

  @Test
  fun `executeToolByName executes tapOnPoint successfully`() =
    runTest {
      val mockBridge =
        ConfigurableMockBridge().apply {
          executeToolResult = "[OK] Tapped at (100, 200)"
        }
      val executor = createExecutor(mockBridge = mockBridge)
      val args =
        buildJsonObject {
          put("x", 100)
          put("y", 200)
        }

      val result = executor.executeToolByName("tapOnPoint", args)

      assertIs<ToolExecutionResult.Success>(result)
      assertEquals("tapOnPoint", result.toolName)
      assertTrue(result.output.contains("OK"), "Output should contain OK")
    }

  @Test
  fun `executeToolByName returns Failure on bridge exception`() =
    runTest {
      val mockBridge =
        ConfigurableMockBridge().apply {
          executeToolException = RuntimeException("Device not connected")
        }
      val executor = createExecutor(mockBridge = mockBridge)
      val args =
        buildJsonObject {
          put("x", 100)
          put("y", 200)
        }

      val result = executor.executeToolByName("tapOnPoint", args)

      assertIs<ToolExecutionResult.Failure>(result)
      assertTrue(result.error.contains("Device not connected"), "Error should contain exception message")
    }

  @Test
  fun `executeToolByName handles swipe with all parameters`() =
    runTest {
      val mockBridge =
        ConfigurableMockBridge().apply {
          executeToolResult = "[OK] Swiped from (100, 500) to (100, 200)"
        }
      val executor = createExecutor(mockBridge = mockBridge)
      val args =
        buildJsonObject {
          put("startX", 100)
          put("startY", 500)
          put("endX", 100)
          put("endY", 200)
        }

      val result = executor.executeToolByName("swipe", args)

      assertIs<ToolExecutionResult.Success>(result)
      assertEquals("swipe", result.toolName)
    }

  @Test
  fun `executeToolByName handles inputText`() =
    runTest {
      val mockBridge =
        ConfigurableMockBridge().apply {
          executeToolResult = "[OK] Entered text"
        }
      val executor = createExecutor(mockBridge = mockBridge)
      val args =
        buildJsonObject {
          put("text", "hello@example.com")
        }

      val result = executor.executeToolByName("inputText", args)

      assertIs<ToolExecutionResult.Success>(result)
      assertEquals("inputText", result.toolName)
    }

  @Test
  fun `executeToolByName handles pressBack from NAVIGATION category`() =
    runTest {
      val mockBridge =
        ConfigurableMockBridge().apply {
          executeToolResult = "[OK] Pressed back"
        }
      val executor =
        createExecutor(
          categories = setOf(ToolSetCategory.CORE_INTERACTION, ToolSetCategory.NAVIGATION),
          mockBridge = mockBridge,
        )
      val args = buildJsonObject {}

      val result = executor.executeToolByName("pressBack", args)

      assertIs<ToolExecutionResult.Success>(result)
      assertEquals("pressBack", result.toolName)
    }

  @Test
  fun `executeToolByName returns ToolNotFound when tool is in unconfigured category`() =
    runTest {
      // Only include CORE_INTERACTION, not MEMORY
      val executor = createExecutor(setOf(ToolSetCategory.CORE_INTERACTION))
      val args =
        buildJsonObject {
          put("key", "testKey")
          put("value", "testValue")
        }

      val result = executor.executeToolByName("rememberText", args)

      assertIs<ToolExecutionResult.ToolNotFound>(result)
      assertEquals("rememberText", result.requestedTool)
    }

  // endregion
}

/**
 * Configurable mock implementation of TrailblazeMcpBridge for testing.
 *
 * Set [executeToolResult] to control successful execution output.
 * Set [executeToolException] to simulate failures.
 */
class ConfigurableMockBridge : TrailblazeMcpBridge {
  var executeToolResult: String = "[OK] Success"
  var executeToolException: Exception? = null
  var lastExecutedTool: TrailblazeTool? = null

  override suspend fun executeTrailblazeTool(tool: TrailblazeTool, blocking: Boolean): String {
    lastExecutedTool = tool
    executeToolException?.let { throw it }
    return executeToolResult
  }

  // The rest of these methods are not needed for executor tests
  override suspend fun selectDevice(trailblazeDeviceId: TrailblazeDeviceId): TrailblazeConnectedDeviceSummary =
    throw NotImplementedError("Not needed for executor tests")

  override suspend fun getAvailableDevices(): Set<TrailblazeConnectedDeviceSummary> =
    throw NotImplementedError("Not needed for executor tests")

  override suspend fun getInstalledAppIds(): Set<String> =
    throw NotImplementedError("Not needed for executor tests")

  override fun getAvailableAppTargets(): Set<TrailblazeHostAppTarget> =
    throw NotImplementedError("Not needed for executor tests")

  override suspend fun runYaml(yaml: String, startNewSession: Boolean, agentImplementation: AgentImplementation) =
    throw NotImplementedError("Not needed for executor tests")

  override fun getCurrentlySelectedDeviceId(): TrailblazeDeviceId? =
    throw NotImplementedError("Not needed for executor tests")

  override suspend fun getCurrentScreenState(): ScreenState? =
    throw NotImplementedError("Not needed for executor tests")

  override fun getDirectScreenStateProvider(): ((ScreenshotScalingConfig) -> ScreenState)? =
    throw NotImplementedError("Not needed for executor tests")

  override suspend fun endSession(): Boolean =
    throw NotImplementedError("Not needed for executor tests")

  override fun isOnDeviceInstrumentation(): Boolean =
    throw NotImplementedError("Not needed for executor tests")

  override fun getDriverType(): TrailblazeDriverType? =
    throw NotImplementedError("Not needed for executor tests")

  override suspend fun getScreenStateViaRpc(
    includeScreenshot: Boolean,
    screenshotScalingConfig: ScreenshotScalingConfig,
  ): GetScreenStateResponse? =
    throw NotImplementedError("Not needed for executor tests")

  override fun getActiveSessionId(): SessionId? =
    throw NotImplementedError("Not needed for executor tests")

  override fun cancelAutomation(deviceId: TrailblazeDeviceId) =
    throw NotImplementedError("Not needed for executor tests")

  override fun selectAppTarget(appTargetId: String): String? = null

  override fun getCurrentAppTargetId(): String? = null
}
