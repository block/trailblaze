package xyz.block.trailblaze.mcp.integration

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import xyz.block.trailblaze.util.Console

/**
 * Live integration tests that connect to a running Trailblaze server with real devices.
 *
 * Unlike [TrailblazeMcpIntegrationTest] which uses a mock bridge, these tests require:
 * 1. Trailblaze server running (`./trailblaze` or `./gradlew :trailblaze-desktop:run`)
 * 2. At least one connected device (Android emulator, physical device, or iOS simulator)
 *
 * ## Running Tests
 *
 * ```bash
 * # Start Trailblaze server first
 * ./trailblaze &
 *
 * # Or run in development mode
 * ./gradlew :trailblaze-desktop:run &
 *
 * # Then run live device tests
 * ./gradlew :trailblaze-server:test --tests "xyz.block.trailblaze.mcp.integration.TrailblazeMcpLiveDeviceTest"
 * ```
 *
 * ## Driver Types Tested
 *
 * - **ANDROID_ONDEVICE_INSTRUMENTATION** - On-device instrumentation
 * - **ANDROID_ONDEVICE_ACCESSIBILITY** - On-device accessibility
 * - **IOS_HOST** - iOS simulator/device automation
 *
 * Tests are skipped if server is not running or no devices are connected.
 */
class TrailblazeMcpLiveDeviceTest {

  companion object {
    private const val MCP_URL = "http://localhost:52525/mcp"
  }

  private lateinit var client: McpTestClient

  @Before
  fun setUp() {
    runBlocking {
      client = McpTestClient(
        serverUrl = MCP_URL,
        clientName = "TrailblazeMcpLiveDeviceTest",
        requestTimeoutMs = 60_000L, // 1 minute timeout for device operations
      )

      // Try to initialize - skip all tests if server not running
      try {
        client.initialize()
      } catch (e: Exception) {
        Console.log("Trailblaze server not running at $MCP_URL: ${e.message}")
        assumeTrue("Trailblaze server not running - start with ./trailblaze", false)
      }
    }
  }

  @After
  fun tearDown() {
    if (::client.isInitialized) {
      client.close()
    }
  }

  // Server Connection Tests

  @Test
  fun `server is running and responds`() {
    runBlocking {
      assertTrue(client.isInitialized, "Client should be initialized")
      assertNotNull(client.serverInfo, "Server info should be present")

      Console.log("Connected to Trailblaze MCP server:")
      Console.log("  Server info: ${client.serverInfo}")
    }
  }

  // Device Discovery Tests

  @Test
  fun `list connected devices shows available devices`() {
    runBlocking {
      val result = client.callTool("listConnectedDevices", emptyMap())

      assertTrue(result.isSuccess, "listConnectedDevices should succeed: ${result.content}")
      Console.log("\n=== Connected Devices ===")
      Console.log(result.content)
      Console.log("========================")
    }
  }

  @Test
  fun `view hierarchy on connected device`() {
    runBlocking {
      // Skip if no devices connected
      val devices = client.callTool("listConnectedDevices", emptyMap())
      assumeTrue(
        "No devices connected - connect a device or start an emulator",
        !devices.content.contains("No devices") && !devices.content.contains("[]"),
      )

      val result = client.callTool("viewHierarchy", emptyMap())

      assertTrue(result.isSuccess, "viewHierarchy should succeed: ${result.content}")
      Console.log("\n=== View Hierarchy (truncated) ===")
      Console.log(result.content.take(2000))
      Console.log("... (truncated)")
      Console.log("==================================")

      // Verify we got actual UI elements - check for common patterns in view hierarchies
      assertTrue(
        result.content.contains("id:") || result.content.contains("@(") ||
          result.content.contains("text") || result.content.contains("bounds") ||
          result.content.contains("class") || result.content.contains("View") ||
          result.content.length > 100, // Non-trivial content
        "View hierarchy should contain UI elements, got: ${result.content.take(200)}",
      )
    }
  }

  @Test
  fun `capture screenshot on connected device`() {
    runBlocking {
      // Skip if no devices connected
      val devices = client.callTool("listConnectedDevices", emptyMap())
      assumeTrue(
        "No devices connected - connect a device or start an emulator",
        !devices.content.contains("No devices") && !devices.content.contains("[]"),
      )

      val result = client.callTool("getScreenshot", emptyMap())

      assertTrue(result.isSuccess, "getScreenshot should succeed: ${result.content}")
      Console.log("\n=== Screenshot Result ===")
      // Just show first 200 chars (base64 data is long)
      Console.log("${result.content.take(200)}...")
      Console.log("========================")

      // Verify we got base64 data (screenshots start with data: or are raw base64)
      assertTrue(
        result.content.contains("data:image") ||
          result.content.matches(Regex("^[A-Za-z0-9+/=]+.*")),
        "Should return image data",
      )
    }
  }

  // ==========================================================================
  // Android-Specific Tests
  // ==========================================================================

  @Test
  fun `test ANDROID_ONDEVICE_INSTRUMENTATION driver - view hierarchy`() {
    runBlocking {
      val devices = client.callTool("listConnectedDevices", emptyMap())

      // Check if we have an Android on-device instrumentation device
      assumeTrue(
        "No ANDROID_ONDEVICE_INSTRUMENTATION device connected",
        devices.content.contains("ANDROID_ONDEVICE_INSTRUMENTATION") ||
          devices.content.contains("ANDROID_ONDEVICE_ACCESSIBILITY"),
      )

      Console.log("\n=== Testing ANDROID_ONDEVICE_INSTRUMENTATION Device ===")
      val viewHierarchy = client.callTool("viewHierarchy", emptyMap())
      assertTrue(viewHierarchy.isSuccess, "viewHierarchy failed: ${viewHierarchy.content}")

      Console.log("View hierarchy captured successfully (${viewHierarchy.content.length} chars)")
      Console.log("Sample: ${viewHierarchy.content.take(500)}...")
    }
  }

  // ==========================================================================
  // iOS-Specific Tests
  // ==========================================================================

  @Test
  fun `test IOS_HOST driver - view hierarchy`() {
    runBlocking {
      val devices = client.callTool("listConnectedDevices", emptyMap())

      // Check if we have an iOS device/simulator
      assumeTrue(
        "No IOS_HOST device connected - open iOS Simulator",
        devices.content.contains("IOS_HOST"),
      )

      Console.log("\n=== Testing IOS_HOST Device (Simulator) ===")
      val viewHierarchy = client.callTool("viewHierarchy", emptyMap())
      assertTrue(viewHierarchy.isSuccess, "viewHierarchy failed: ${viewHierarchy.content}")

      Console.log("View hierarchy captured successfully (${viewHierarchy.content.length} chars)")
      Console.log("Sample: ${viewHierarchy.content.take(500)}...")
    }
  }

  @Test
  fun `test IOS_HOST driver - screenshot`() {
    runBlocking {
      val devices = client.callTool("listConnectedDevices", emptyMap())

      assumeTrue(
        "No IOS_HOST device connected - open iOS Simulator",
        devices.content.contains("IOS_HOST"),
      )

      Console.log("\n=== Testing IOS_HOST Screenshot ===")
      val screenshot = client.callTool("getScreenshot", emptyMap())
      assertTrue(screenshot.isSuccess, "getScreenshot failed: ${screenshot.content}")

      Console.log("Screenshot captured successfully (${screenshot.content.length} chars)")
    }
  }

  // ==========================================================================
  // Multi-Driver Comparison Tests
  // ==========================================================================

  @Test
  fun `compare view hierarchy across all connected devices`() {
    runBlocking {
      val devices = client.callTool("listConnectedDevices", emptyMap())

      Console.log("\n=== Device Comparison ===")
      Console.log("Available devices:\n${devices.content}")

      // Test each available driver type
      val driverTypes = listOf(
        "ANDROID_ONDEVICE_INSTRUMENTATION" to "Android (On-Device)",
        "ANDROID_ONDEVICE_ACCESSIBILITY" to "Android (Accessibility)",
        "IOS_HOST" to "iOS",
      )

      var testedCount = 0
      for ((driverType, displayName) in driverTypes) {
        if (devices.content.contains(driverType)) {
          Console.log("\n--- $displayName ($driverType) ---")
          val hierarchy = client.callTool("viewHierarchy", emptyMap())
          if (hierarchy.isSuccess) {
            Console.log("  View hierarchy: ${hierarchy.content.length} chars")
            testedCount++
          } else {
            Console.log("  Failed: ${hierarchy.content}")
          }
        }
      }

      assertTrue(testedCount > 0 || devices.content.contains("No devices"), "Should test at least one device")
      Console.log("\nTested $testedCount device(s)")
    }
  }
}
