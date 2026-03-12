package xyz.block.trailblaze.mcp.integration

import io.ktor.server.engine.EmbeddedServer
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import xyz.block.trailblaze.api.ScreenState
import xyz.block.trailblaze.api.ScreenshotScalingConfig
import xyz.block.trailblaze.devices.TrailblazeConnectedDeviceSummary
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.logs.server.TrailblazeMcpServer
import xyz.block.trailblaze.mcp.AgentImplementation
import xyz.block.trailblaze.mcp.TrailblazeMcpBridge
import xyz.block.trailblaze.model.TrailblazeHostAppTarget
import xyz.block.trailblaze.report.utils.LogsRepo
import xyz.block.trailblaze.toolcalls.TrailblazeTool
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Integration tests for Trailblaze MCP server.
 *
 * These tests start the MCP server in-process and connect to it using our own
 * [McpTestClient], providing a self-contained validation loop without requiring
 * external MCP clients like Firebender or Claude Desktop.
 *
 * ## Prerequisites
 *
 * Tests that interact with devices require at least one connected device.
 * Device connection tests are skipped if no device is available.
 *
 * ## Running Tests
 *
 * ```bash
 * # Run all MCP integration tests
 * ./gradlew :trailblaze-server:test --tests "xyz.block.trailblaze.mcp.integration.TrailblazeMcpIntegrationTest"
 *
 * # Run with a specific device connected
 * adb devices  # Verify device is connected
 * ./gradlew :trailblaze-server:test --tests "xyz.block.trailblaze.mcp.integration.TrailblazeMcpIntegrationTest"
 * ```
 *
 * ## Test Categories
 *
 * 1. **Session & Config Tests** - Test MCP session management and configuration tools
 * 2. **Tool Discovery Tests** - Test tool listing and dynamic tool registration
 * 3. **Device Interaction Tests** - Test device selection and screen capture (requires device)
 *
 * @see McpTestClient for the MCP client implementation
 * @see TrailblazeMcpServer for the server implementation
 */
class TrailblazeMcpIntegrationTest {

  companion object {
    // Use incrementing ports starting from 53000 to avoid conflicts
    private val portCounter = AtomicInteger(53000)
  }

  private lateinit var server: EmbeddedServer<*, *>
  private lateinit var mcpServer: TrailblazeMcpServer
  private lateinit var client: McpTestClient

  // Test infrastructure
  private lateinit var testBridge: TestTrailblazeMcpBridge
  private lateinit var logsRepo: LogsRepo

  private lateinit var tempLogsDir: File
  private var testPort: Int = 0

  @Before
  fun setUp() {
    runBlocking {
      // Use incrementing ports to avoid conflicts between parallel tests
      testPort = portCounter.getAndIncrement()

      // Create temp directories for test
      tempLogsDir = File(System.getProperty("java.io.tmpdir"), "trailblaze-test-logs-${System.currentTimeMillis()}")
      tempLogsDir.mkdirs()

      // Create test infrastructure
      logsRepo = LogsRepo(logsDir = tempLogsDir, watchFileSystem = false)
      testBridge = TestTrailblazeMcpBridge()

      // Create and start the MCP server
      mcpServer = TrailblazeMcpServer(
        logsRepo = logsRepo,
        mcpBridge = testBridge,
        trailsDirProvider = { File(System.getProperty("java.io.tmpdir"), "trailblaze-test-trails") },
        targetTestAppProvider = { TrailblazeHostAppTarget.DefaultTrailblazeHostAppTarget },
      )

      server = mcpServer.startStreamableHttpMcpServer(port = testPort, wait = false)

      // Give the server time to start
      delay(500)

      // Create and initialize the test client
      val mcpUrl = "http://localhost:$testPort/mcp"
      client = McpTestClient(serverUrl = mcpUrl, clientName = "TrailblazeMcpIntegrationTest")
      client.initialize()
    }
  }

  @After
  fun tearDown() {
    if (::client.isInitialized) {
      client.close()
    }
    if (::server.isInitialized) {
      server.stop(1000, 2000)
    }
    if (::logsRepo.isInitialized) {
      logsRepo.close()
    }
    if (::tempLogsDir.isInitialized && tempLogsDir.exists()) {
      tempLogsDir.deleteRecursively()
    }
  }

  // ==========================================================================
  // Session & Config Tests
  // ==========================================================================

  @Test
  fun `initialize creates MCP session`() {
    runBlocking {
      assertTrue(client.isInitialized, "Client should be initialized")
      assertNotNull(client.serverInfo, "Server info should be present")
    }
  }

  @Test
  fun `getSessionConfig returns current configuration`() {
    runBlocking {
      val result = client.callTool("getSessionConfig", emptyMap())

      assertTrue(result.isSuccess, "getSessionConfig should succeed: ${result.content}")
      assertTrue(result.content.contains("Mode:"), "Should contain mode info")
      assertTrue(result.content.contains("Screenshot format:"), "Should contain screenshot format")
    }
  }

  @Test
  fun `setMode changes operating mode`() {
    runBlocking {
      // Set to TRAILBLAZE_AS_AGENT mode
      val result = client.callTool("setMode", mapOf("mode" to "TRAILBLAZE_AS_AGENT"))

      assertTrue(result.isSuccess, "setMode should succeed: ${result.content}")
      assertTrue(
        result.content.contains("TRAILBLAZE_AS_AGENT"),
        "Should confirm mode change",
      )

      // Verify config reflects the change
      val config = client.callTool("getSessionConfig", emptyMap())
      assertTrue(
        config.content.contains("TRAILBLAZE_AS_AGENT"),
        "Config should show new mode",
      )
    }
  }

  @Test
  fun `setLlmCallStrategy changes LLM call strategy`() {
    runBlocking {
      val result = client.callTool("setLlmCallStrategy", mapOf("strategy" to "DIRECT"))

      assertTrue(result.isSuccess, "setLlmCallStrategy should succeed: ${result.content}")
      assertTrue(result.content.contains("DIRECT"), "Should confirm strategy change")
    }
  }

  @Test
  fun `configureSession sets multiple options at once`() {
    runBlocking {
      val result = client.callTool(
        "configureSession",
        mapOf(
          "mode" to "TRAILBLAZE_AS_AGENT",
          "screenshotFormat" to "BASE64_TEXT",
          "autoIncludeScreenshot" to true,
        ),
      )

      assertTrue(result.isSuccess, "configureSession should succeed: ${result.content}")
      assertTrue(result.content.contains("Mode: TRAILBLAZE_AS_AGENT"))
      assertTrue(result.content.contains("BASE64_TEXT"))
    }
  }

  // ==========================================================================
  // Tool Discovery Tests
  // ==========================================================================

  @Test
  fun `listTools returns available tools`() {
    runBlocking {
      val tools = client.listTools()

      assertTrue(tools.isNotEmpty(), "Should have tools available")

      // Verify core tools are present
      val expectedTools = listOf(
        "getSessionConfig",
        "setMode",
        "listConnectedDevices",
      )
      expectedTools.forEach { expected ->
        assertTrue(tools.contains(expected), "Should have '$expected' tool, got: $tools")
      }
    }
  }

  @Test
  fun `listToolDescriptors includes descriptions and schemas`() {
    runBlocking {
      val descriptors = client.listToolDescriptors()

      assertTrue(descriptors.isNotEmpty(), "Should have tool descriptors")

      // Find getSessionConfig and verify it has proper descriptor
      val getSessionConfig = descriptors.find { it.name == "getSessionConfig" }
      assertNotNull(getSessionConfig, "Should have getSessionConfig descriptor")
      assertNotNull(getSessionConfig.description, "Should have description")
    }
  }

  @Test
  fun `tool categories can be listed`() {
    runBlocking {
      val result = client.callTool("listToolCategories", emptyMap())

      assertTrue(result.isSuccess, "listToolCategories should succeed: ${result.content}")
      assertTrue(result.content.contains("CORE_INTERACTION"), "Should list CORE_INTERACTION category")
    }
  }

  // ==========================================================================
  // Device Discovery Tests (No device required)
  // ==========================================================================

  @Test
  fun `listConnectedDevices returns device list`() {
    runBlocking {
      val result = client.callTool("listConnectedDevices", emptyMap())

      // This test passes even with no devices - it just returns an empty list
      assertTrue(result.isSuccess, "listConnectedDevices should succeed: ${result.content}")
      // The result will be either "No devices connected" or a list of devices
    }
  }

  // ==========================================================================
  // Device Interaction Tests (Requires connected device)
  // ==========================================================================

  // Note: selectDevice is not directly exposed as an MCP tool in the current implementation.
  // Device selection is typically done through the desktop app UI.

  @Test
  fun `viewHierarchy returns UI structure when device connected`() {
    runBlocking {
      // Skip if no device connected
      assumeDeviceConnected()

      val result = client.callTool("viewHierarchy", emptyMap())

      assertTrue(result.isSuccess, "viewHierarchy should succeed: ${result.content}")
      // With real device, this would return actual view hierarchy
    }
  }

  @Test
  fun `getScreenshot captures screen when device connected`() {
    runBlocking {
      // Skip if no device connected
      assumeDeviceConnected()

      val result = client.callTool("getScreenshot", emptyMap())

      assertTrue(result.isSuccess, "getScreenshot should succeed: ${result.content}")
      // With real device, this would return base64 screenshot
    }
  }

  // ==========================================================================
  // Agent Metrics Tests
  // ==========================================================================

  @Test
  fun `clearAgentMetrics resets counters`() {
    runBlocking {
      val result = client.callTool("clearAgentMetrics", emptyMap())

      assertTrue(result.isSuccess, "clearAgentMetrics should succeed: ${result.content}")
      assertTrue(result.content.contains("cleared"), "Should confirm metrics cleared")
    }
  }

  @Test
  fun `getAgentMetrics returns statistics`() {
    runBlocking {
      // Clear first to ensure clean state
      client.callTool("clearAgentMetrics", emptyMap())

      val result = client.callTool("getAgentMetrics", emptyMap())

      assertTrue(result.isSuccess, "getAgentMetrics should succeed: ${result.content}")
      // Should show both DIRECT and MCP_SAMPLING sections
      assertTrue(
        result.content.contains("DIRECT") || result.content.contains("No metrics"),
        "Should show metrics or indicate none collected",
      )
    }
  }

  // ==========================================================================
  // Error Handling Tests
  // ==========================================================================

  @Test
  fun `invalid tool name returns error`() {
    runBlocking {
      val result = client.callTool("nonExistentTool", emptyMap())

      assertTrue(result.isError, "Should return error for unknown tool")
    }
  }

  @Test
  fun `setMode with invalid mode returns error`() {
    runBlocking {
      val result = client.callTool("setMode", mapOf("mode" to "INVALID_MODE"))

      assertTrue(result.isError || result.content.contains("Invalid"), "Should reject invalid mode")
    }
  }

  // ==========================================================================
  // Trail Management Tests
  // ==========================================================================

  @Test
  fun `listTestCases returns available test cases`() {
    runBlocking {
      val result = client.callTool("listTestCases", emptyMap())

      assertTrue(result.isSuccess, "listTestCases should succeed: ${result.content}")
      // May be empty if no test cases exist, which is fine
    }
  }

  // ==========================================================================
  // Helper Methods
  // ==========================================================================

  /**
   * Assumes a device is connected, skipping the test if not.
   */
  private suspend fun assumeDeviceConnected() {
    val result = client.callTool("listConnectedDevices", emptyMap())
    assumeTrue(
      "Skipping test - no devices connected",
      !result.content.contains("No devices connected") &&
        !result.content.contains("0 device") &&
        testBridge.hasConnectedDevice,
    )
  }
}

/**
 * Test implementation of TrailblazeMcpBridge for integration testing.
 *
 * This bridge provides minimal mock responses for testing MCP tools
 * without requiring actual device connections.
 */
private class TestTrailblazeMcpBridge : TrailblazeMcpBridge {

  var hasConnectedDevice = false
  var selectedDeviceId: TrailblazeDeviceId? = null

  override suspend fun selectDevice(
    trailblazeDeviceId: TrailblazeDeviceId,
  ): TrailblazeConnectedDeviceSummary {
    selectedDeviceId = trailblazeDeviceId
    hasConnectedDevice = true
    return TrailblazeConnectedDeviceSummary(
      trailblazeDriverType = TrailblazeDriverType.ANDROID_HOST,
      instanceId = trailblazeDeviceId.instanceId,
      description = "Test Device",
    )
  }

  override suspend fun getAvailableDevices(): Set<TrailblazeConnectedDeviceSummary> {
    return if (hasConnectedDevice && selectedDeviceId != null) {
      setOf(
        TrailblazeConnectedDeviceSummary(
          trailblazeDriverType = TrailblazeDriverType.ANDROID_HOST,
          instanceId = selectedDeviceId!!.instanceId,
          description = "Test Device",
        ),
      )
    } else {
      emptySet()
    }
  }

  override suspend fun getInstalledAppIds(): Set<String> = emptySet()

  override fun getAvailableAppTargets(): Set<TrailblazeHostAppTarget> =
    setOf(TrailblazeHostAppTarget.DefaultTrailblazeHostAppTarget)

  override suspend fun runYaml(yaml: String, startNewSession: Boolean, agentImplementation: AgentImplementation): String {
    // No-op for testing
    return ""
  }

  override fun getCurrentlySelectedDeviceId(): TrailblazeDeviceId? =
    selectedDeviceId

  override suspend fun getCurrentScreenState(): ScreenState? = null

  override fun getDirectScreenStateProvider(): ((ScreenshotScalingConfig) -> ScreenState)? = null

  override suspend fun executeTrailblazeTool(tool: TrailblazeTool): String =
    "[OK] Tool executed (test mode)"

  override suspend fun endSession(): Boolean = true

  override fun cancelAutomation(deviceId: TrailblazeDeviceId) {
    // No-op for testing
  }

  override fun selectAppTarget(appTargetId: String): String? = null

  override fun getCurrentAppTargetId(): String? = null
}
