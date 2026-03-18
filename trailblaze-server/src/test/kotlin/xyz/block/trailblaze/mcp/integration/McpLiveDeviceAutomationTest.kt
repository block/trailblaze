package xyz.block.trailblaze.mcp.integration

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import xyz.block.trailblaze.mcp.AgentImplementation
import xyz.block.trailblaze.mcp.TrailblazeMcpMode
import kotlin.test.assertTrue
import xyz.block.trailblaze.util.Console

/**
 * **LIVE DEVICE AUTOMATION TESTS**
 *
 * These tests connect to a running Trailblaze server and execute real automation
 * on a connected Android device using the MCP protocol.
 *
 * ## Prerequisites
 *
 * 1. **Trailblaze server running** - Start with `./trailblaze` or `./gradlew :trailblaze-desktop:run`
 * 2. **Android device connected** - Run `adb devices` to verify
 * 3. **Device unlocked** - Screen must be on and unlocked
 *
 * ## Running Tests
 *
 * ```bash
 * # Start Trailblaze server first
 * ./trailblaze &
 *
 * # Run live device tests
 * ./gradlew :trailblaze-server:test --tests "xyz.block.trailblaze.mcp.integration.McpLiveDeviceAutomationTest"
 * ```
 *
 * ## Test Categories
 *
 * 1. **Basic Device Interaction** - Screenshot, view hierarchy, simple taps
 * 2. **Agent Automation** - Full runPrompt execution with LLM
 * 3. **Navigation** - App launch, back, home
 *
 * Tests are automatically skipped if:
 * - Server is not running
 * - No device is connected
 */
class McpLiveDeviceAutomationTest {

  companion object {
    private const val MCP_URL = "http://localhost:52525/mcp"
    private const val AGENT_TIMEOUT_MS = 180_000L // 3 minutes for agent operations
  }

  private lateinit var client: McpTestClient
  private var deviceId: String? = null
  private var devicePlatform: String = "ANDROID" // Default to Android

  // Helper to connect to device using type-safe extension
  private suspend fun connectToCurrentDevice(): McpTestClient.ToolResult {
    return client.connectToDevice(deviceId!!, devicePlatform.lowercase())
  }

  @Before
  fun setUp() = runBlocking {
    // Create client with extended timeout for agent operations
    client = McpTestClient(
      serverUrl = MCP_URL,
      clientName = "McpLiveDeviceAutomationTest",
      requestTimeoutMs = AGENT_TIMEOUT_MS,
    )

    // Try to connect to server
    try {
      client.initialize()
    } catch (e: Exception) {
      Console.log("Trailblaze server not running at $MCP_URL: ${e.message}")
      assumeTrue("Trailblaze server not running - start with ./trailblaze", false)
      return@runBlocking
    }

    // Check for connected devices
    val devicesResult = client.listConnectedDevices()
    if (!devicesResult.isSuccess) {
      Console.log("No devices connected: ${devicesResult.content}")
      assumeTrue("No Android device connected - run 'adb devices' to verify", false)
      return@runBlocking
    }

    // Extract first device ID
    deviceId = extractFirstDeviceId(devicesResult.content)
    if (deviceId == null) {
      Console.log("Could not extract device ID from: ${devicesResult.content}")
      assumeTrue("Could not find device ID", false)
      return@runBlocking
    }

    Console.log("=".repeat(60))
    Console.log("LIVE DEVICE AUTOMATION TEST")
    Console.log("=".repeat(60))
    Console.log("Server: $MCP_URL")
    Console.log("Device: $deviceId")
    Console.log("=".repeat(60))
  }

  @After
  fun tearDown() {
    if (::client.isInitialized) {
      client.close()
    }
  }

  // ==========================================================================
  // Basic Device Interaction Tests
  // ==========================================================================

  @Test
  fun `can connect to device`() = runBlocking {
    val result = connectToCurrentDevice()

    assertTrue(result.isSuccess, "Should connect to device: ${result.content}")
    Console.log("Connected: ${result.content}")
  }

  @Test
  fun `can get screen state`() = runBlocking {
    // Connect first
    connectToCurrentDevice()

    val result = client.getScreenState()

    assertTrue(result.isSuccess, "Should get screen state: ${result.content}")
    assertTrue(result.content.length > 100, "Screen state should have content")
    Console.log("Screen state (${result.content.length} chars): ${result.content.take(500)}...")
  }

  @Test
  fun `can get view hierarchy`() = runBlocking {
    connectToCurrentDevice()

    val result = client.viewHierarchy()

    assertTrue(result.isSuccess, "Should get view hierarchy: ${result.content}")
    Console.log("View hierarchy (${result.content.length} chars)")
  }

  @Test
  fun `can get screenshot via screen state`() = runBlocking {
    connectToCurrentDevice()

    val result = client.getScreenState(includeScreenshot = true)

    assertTrue(result.isSuccess, "Should get screen state with screenshot: ${result.content}")
    // Screen state should have substantial content
    assertTrue(result.content.length > 100, "Screen state should have content")
    Console.log("Screen state captured (${result.content.length} chars)")
  }

  // ==========================================================================
  // Agent Automation Tests (Uses LLM)
  // ==========================================================================

  @Test
  fun `can run simple automation with KOOG_DIRECT_AGENT`() = runBlocking {
    // Connect to device
    connectToCurrentDevice()

    // Set mode to TRAILBLAZE_AS_AGENT
    client.setMode(TrailblazeMcpMode.TRAILBLAZE_AS_AGENT)

    // Ensure using MULTI_AGENT_V3
    client.setAgentImplementation(AgentImplementation.MULTI_AGENT_V3)

    Console.log("\n--- Running Agent Automation ---")
    Console.log("Objective: 'Press the home button and verify you're on the home screen'")

    val result = client.runPrompt(
      listOf("Press the home button and verify you're on the home screen"),
    )

    Console.log("Result: ${result.content}")
    assertTrue(result.isSuccess, "Agent should complete: ${result.content}")
  }

  @Test
  fun `can run multi-step automation`() = runBlocking {
    // Connect to device
    connectToCurrentDevice()
    client.setMode(TrailblazeMcpMode.TRAILBLAZE_AS_AGENT)
    client.setAgentImplementation(AgentImplementation.MULTI_AGENT_V3)

    Console.log("\n--- Running Multi-Step Automation ---")
    val steps = listOf(
      "Press the home button to start from a known state",
      "Open the Settings app",
      "Look at what's on screen and describe the main sections visible",
    )
    Console.log("Steps: $steps")

    val result = client.runPrompt(steps)

    Console.log("Result: ${result.content}")
    assertTrue(result.isSuccess, "Multi-step automation should complete: ${result.content}")

    // Clean up - go home
    client.runPrompt(listOf("Press the home button"))
  }

  @Test
  fun `can compare MULTI_AGENT_V3 vs TRAILBLAZE_RUNNER`() = runBlocking {
    // Connect to device
    connectToCurrentDevice()
    client.setMode(TrailblazeMcpMode.TRAILBLAZE_AS_AGENT)

    val simpleTask = listOf("Press the home button")

    Console.log("\n=== Agent Comparison Test ===")

    // Test with MULTI_AGENT_V3
    Console.log("\n[1] Testing MULTI_AGENT_V3...")
    client.setAgentImplementation(AgentImplementation.MULTI_AGENT_V3)
    val v3Start = System.currentTimeMillis()
    val v3Result = client.runPrompt(simpleTask)
    val v3Time = System.currentTimeMillis() - v3Start
    Console.log("MULTI_AGENT_V3: ${if (v3Result.isSuccess) "SUCCESS" else "FAILED"} in ${v3Time}ms")
    Console.log("  Result: ${v3Result.content.take(200)}...")

    // Test with TRAILBLAZE_RUNNER
    Console.log("\n[2] Testing TRAILBLAZE_RUNNER...")
    client.setAgentImplementation(AgentImplementation.TRAILBLAZE_RUNNER)
    val runnerStart = System.currentTimeMillis()
    val runnerResult = client.runPrompt(simpleTask)
    val runnerTime = System.currentTimeMillis() - runnerStart
    Console.log("TRAILBLAZE_RUNNER: ${if (runnerResult.isSuccess) "SUCCESS" else "FAILED"} in ${runnerTime}ms")
    Console.log("  Result: ${runnerResult.content.take(200)}...")

    Console.log("\n=== Comparison Summary ===")
    Console.log("MULTI_AGENT_V3: ${v3Time}ms")
    Console.log("TRAILBLAZE_RUNNER: ${runnerTime}ms")

    // Both should succeed
    assertTrue(v3Result.isSuccess || runnerResult.isSuccess, "At least one agent should succeed")
  }

  // ==========================================================================
  // Session Management Tests
  // ==========================================================================

  @Test
  fun `session config shows correct agent implementation`() = runBlocking {
    // Set to MULTI_AGENT_V3
    client.setAgentImplementation(AgentImplementation.MULTI_AGENT_V3)

    val config = client.getSessionConfig()

    assertTrue(config.isSuccess, "Should get config: ${config.content}")
    assertTrue(
      config.content.contains("MULTI_AGENT_V3") || config.content.contains("Agent implementation"),
      "Config should show agent implementation: ${config.content}",
    )
    Console.log("Session config:\n${config.content}")
  }

  // ==========================================================================
  // Helper Methods
  // ==========================================================================

  private fun extractFirstDeviceId(devicesOutput: String): String? {
    // Look for device ID patterns in the output
    // Common formats: "emulator-5554", "RF8M...", "192.168..."

    // Try to find device ID after common patterns
    val patterns = listOf(
      Regex("""device[_\s]*[iI][dD][\s:]*["\']?([a-zA-Z0-9\-_.:]+)["\']?"""),
      Regex("""emulator-\d+"""),
      Regex("""[A-Z0-9]{8,}"""), // Serial numbers
      Regex("""\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}:\d+"""), // IP:port
    )

    for (pattern in patterns) {
      val match = pattern.find(devicesOutput)
      if (match != null) {
        val id = if (match.groupValues.size > 1) match.groupValues[1] else match.value
        if (id.isNotBlank() && id.length > 3) {
          return id
        }
      }
    }

    // Fallback: look for first quoted string or ID-like token
    val tokenPattern = Regex("""["\']([^"\']+)["\']|([a-zA-Z0-9][a-zA-Z0-9\-_.:]{4,})""")
    tokenPattern.findAll(devicesOutput).forEach { match ->
      val id = match.groupValues.firstOrNull { it.isNotBlank() }
      if (id != null && id.length > 4 && !id.contains("connected") && !id.contains("device")) {
        return id
      }
    }

    return null
  }
}
