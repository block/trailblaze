package xyz.block.trailblaze.mcp.integration

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import xyz.block.trailblaze.mcp.AgentImplementation
import xyz.block.trailblaze.mcp.TrailblazeMcpMode
import java.net.URL
import kotlin.test.assertTrue
import xyz.block.trailblaze.util.Console

/**
 * Basic validation tests for MCP functionality.
 * 
 * These tests validate each MCP operation in isolation to identify issues.
 * Run with a Trailblaze server already running:
 * 
 * ```bash
 * ./gradlew :trailblaze-desktop:run &
 * ./gradlew :trailblaze-server:test --tests "*McpBasicValidationTest"
 * ```
 */
class McpBasicValidationTest {

  private lateinit var client: McpTestClient
  private var serverRunning = false

  @Before
  fun setUp() {
    // Check if server is running
    serverRunning = try {
      URL("http://localhost:52525/ping").openStream().close()
      true
    } catch (_: Exception) {
      false
    }

    if (serverRunning) {
      client = McpTestClient("http://localhost:52525/mcp")
      runBlocking { client.initialize() }
    }
  }

  @After
  fun tearDown() {
    if (serverRunning && ::client.isInitialized) {
      runBlocking { client.close() }
    }
  }

  @Test
  fun `01 - server is reachable via ping`() {
    assumeTrue("Trailblaze server not running - start with ./gradlew :trailblaze-desktop:run", serverRunning)
    Console.log("Server is reachable via /ping endpoint")
  }

  @Test
  fun `02 - can list connected devices`() {
    assumeTrue("Server not running", serverRunning)

    runBlocking {
      val result = client.listConnectedDevices()
      Console.log("listConnectedDevices result: ${result.content}")
      assertTrue(result.isSuccess, "Should list devices: ${result.content}")
      Console.log("Devices found: ${result.content.take(500)}")
    }
  }

  @Test
  fun `03 - can connect to iOS simulator`() {
    assumeTrue("Server not running", serverRunning)

    runBlocking {
      // List devices first
      val devicesResult = client.listConnectedDevices()
      assumeTrue("Failed to list devices", devicesResult.isSuccess)
      
      // Find iOS device
      val iosDeviceId = extractDeviceId(devicesResult.content, "ios")
      assumeTrue("No iOS device found. Devices: ${devicesResult.content}", iosDeviceId != null)
      
      Console.log("Found iOS device: $iosDeviceId")
      
      // Connect
      val connectResult = client.connectToDevice(iosDeviceId!!, "ios")
      Console.log("connectToDevice result: ${connectResult.content}")
      assertTrue(connectResult.isSuccess, "Should connect: ${connectResult.content}")
    }
  }

  @Test
  fun `04 - can connect to Android emulator`() {
    assumeTrue("Server not running", serverRunning)

    runBlocking {
      // List devices first
      val devicesResult = client.listConnectedDevices()
      assumeTrue("Failed to list devices", devicesResult.isSuccess)
      
      // Find Android device
      val androidDeviceId = extractDeviceId(devicesResult.content, "android")
      assumeTrue("No Android device found. Devices: ${devicesResult.content}", androidDeviceId != null)
      
      Console.log("Found Android device: $androidDeviceId")
      
      // Connect
      val connectResult = client.connectToDevice(androidDeviceId!!, "android")
      Console.log("connectToDevice result: ${connectResult.content}")
      assertTrue(connectResult.isSuccess, "Should connect: ${connectResult.content}")
    }
  }

  @Test
  fun `05 - can get screen state from iOS after connect`() {
    assumeTrue("Server not running", serverRunning)

    runBlocking {
      // List and connect to iOS
      val devicesResult = client.listConnectedDevices()
      val iosDeviceId = extractDeviceId(devicesResult.content, "ios")
      assumeTrue("No iOS device found", iosDeviceId != null)
      
      val connectResult = client.connectToDevice(iosDeviceId!!, "ios")
      assumeTrue("Failed to connect", connectResult.isSuccess)
      
      // Wait for connection to fully initialize
      Console.log("Waiting for connection to initialize...")
      delay(2000)
      
      // Get screen state
      val screenState = client.getScreenState()
      Console.log("getScreenState result: success=${screenState.isSuccess}")
      Console.log("Content length: ${screenState.content.length}")
      Console.log("Content preview: ${screenState.content.take(300)}")
      
      assertTrue(screenState.isSuccess, "Should get screen state: ${screenState.content}")
    }
  }

  @Test
  fun `06 - can get screen state from Android after connect`() {
    assumeTrue("Server not running", serverRunning)

    runBlocking {
      // List and connect to Android
      val devicesResult = client.listConnectedDevices()
      val androidDeviceId = extractDeviceId(devicesResult.content, "android")
      assumeTrue("No Android device found", androidDeviceId != null)
      
      val connectResult = client.connectToDevice(androidDeviceId!!, "android")
      assumeTrue("Failed to connect", connectResult.isSuccess)
      
      // Wait for connection to initialize
      Console.log("Waiting for connection to initialize...")
      delay(3000)
      
      // Get screen state
      val screenState = client.getScreenState()
      Console.log("getScreenState result: success=${screenState.isSuccess}")
      Console.log("Content length: ${screenState.content.length}")
      Console.log("Content preview: ${screenState.content.take(300)}")
      
      assertTrue(screenState.isSuccess, "Should get screen state: ${screenState.content}")
    }
  }

  @Test
  fun `07 - can configure session and run simple prompt on Android`() {
    assumeTrue("Server not running", serverRunning)

    runBlocking {
      // Connect to Android
      val devicesResult = client.listConnectedDevices()
      val androidDeviceId = extractDeviceId(devicesResult.content, "android")
      assumeTrue("No Android device found", androidDeviceId != null)
      
      val connectResult = client.connectToDevice(androidDeviceId!!, "android")
      assumeTrue("Failed to connect: ${connectResult.content}", connectResult.isSuccess)
      delay(3000)  // Give more time for on-device instrumentation to initialize
      
      // Configure
      val modeResult = client.setMode(TrailblazeMcpMode.TRAILBLAZE_AS_AGENT)
      Console.log("setMode result: ${modeResult.content}")
      
      val agentResult = client.setAgentImplementation(AgentImplementation.TWO_TIER_AGENT)
      Console.log("setAgentImplementation result: ${agentResult.content}")
      
      // Run a very simple prompt
      Console.log("\n--- Running simple prompt: Press the home button ---")
      val promptResult = client.runPrompt(listOf("Press the home button"))
      Console.log("runPrompt result (isSuccess=${promptResult.isSuccess}): ${promptResult.content.take(500)}")
      
      assertTrue(promptResult.isSuccess, "Should run prompt: ${promptResult.content.take(500)}")
    }
  }
  
  @Test
  fun `08 - can configure session and run simple prompt on iOS`() {
    assumeTrue("Server not running", serverRunning)

    runBlocking {
      // Connect to iOS
      val devicesResult = client.listConnectedDevices()
      val iosDeviceId = extractDeviceId(devicesResult.content, "ios")
      assumeTrue("No iOS device found", iosDeviceId != null)
      
      val connectResult = client.connectToDevice(iosDeviceId!!, "ios")
      assumeTrue("Failed to connect: ${connectResult.content}", connectResult.isSuccess)
      delay(2000)
      
      // Configure
      val modeResult = client.setMode(TrailblazeMcpMode.TRAILBLAZE_AS_AGENT)
      Console.log("setMode result: ${modeResult.content}")
      
      val agentResult = client.setAgentImplementation(AgentImplementation.TWO_TIER_AGENT)
      Console.log("setAgentImplementation result: ${agentResult.content}")
      
      // Run a very simple prompt
      Console.log("\n--- Running simple prompt: Press the home button ---")
      val promptResult = client.runPrompt(listOf("Press the home button"))
      Console.log("runPrompt result (isSuccess=${promptResult.isSuccess}): ${promptResult.content.take(500)}")
      
      assertTrue(promptResult.isSuccess, "Should run prompt: ${promptResult.content.take(500)}")
    }
  }

  // Helper to extract device ID from JSON response
  private fun extractDeviceId(json: String, platform: String): String? {
    // Match based on driverType first
    val driverTypeRegex = when (platform.lowercase()) {
      "ios" -> """"trailblazeDriverType"\s*:\s*"IOS_HOST"[^}]*"instanceId"\s*:\s*"([^"]+)""""
        .toRegex(RegexOption.DOT_MATCHES_ALL)
      "android" -> """"trailblazeDriverType"\s*:\s*"ANDROID[^"]*"[^}]*"instanceId"\s*:\s*"([^"]+)""""
        .toRegex(RegexOption.DOT_MATCHES_ALL)
      else -> return null
    }
    
    // Try driver type match first
    driverTypeRegex.find(json)?.groupValues?.getOrNull(1)?.let { return it }
    
    // Fallback to simple instanceId patterns
    val fallbackRegex = when (platform.lowercase()) {
      "ios" -> """"instanceId"\s*:\s*"([A-F0-9]{8}-[A-F0-9]{4}-[A-F0-9]{4}-[A-F0-9]{4}-[A-F0-9]{12})"""".toRegex(RegexOption.IGNORE_CASE)
      "android" -> """"instanceId"\s*:\s*"(emulator-\d+)"""".toRegex()
      else -> return null
    }
    return fallbackRegex.find(json)?.groupValues?.getOrNull(1)
  }
}
