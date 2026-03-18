package xyz.block.trailblaze.mcp.integration

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Test
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.mcp.AgentImplementation
import xyz.block.trailblaze.util.Console
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Real-device MCP integration tests for Android driver types × agent implementations.
 *
 * Tests the full MCP protocol stack against a real Android device/emulator:
 * initialize → config → connect → blaze → verify.
 *
 * ## Driver Types Tested
 * - ANDROID_HOST
 * - ANDROID_ONDEVICE_INSTRUMENTATION
 * - ANDROID_ONDEVICE_ACCESSIBILITY
 *
 * ## Agent Implementations Tested
 * - TRAILBLAZE_RUNNER
 * - MULTI_AGENT_V3
 *
 * ## Running in CI
 * ```bash
 * ./gradlew :trailblaze-server:integrationTest --tests "*McpRealDeviceIntegrationTest"
 * ```
 *
 * ## Running locally
 * ```bash
 * ./trailblaze --headless &
 * ./gradlew :trailblaze-server:integrationTest --tests "*McpRealDeviceIntegrationTest"
 * ```
 */
class McpRealDeviceIntegrationTest : TrailblazeServerTestBase() {

  override val requireDevice = false
  override val forceRestart = false
  override val requestTimeoutMs = 300_000L // 5 minutes for agent operations

  // ═══════════════════════════════════════════════════════════════════════════
  // Android Driver × Agent Implementation (3 drivers × 3 agents = 9 tests)
  // ═══════════════════════════════════════════════════════════════════════════

  @Test fun `blaze - ANDROID_HOST x TRAILBLAZE_RUNNER`() =
    blazeTest(TrailblazeDriverType.ANDROID_HOST, AgentImplementation.TRAILBLAZE_RUNNER)

  @Test fun `blaze - ANDROID_HOST x MULTI_AGENT_V3`() =
    blazeTest(TrailblazeDriverType.ANDROID_HOST, AgentImplementation.MULTI_AGENT_V3)

  @Test fun `blaze - ANDROID_ONDEVICE_INSTRUMENTATION x TRAILBLAZE_RUNNER`() =
    blazeTest(TrailblazeDriverType.ANDROID_ONDEVICE_INSTRUMENTATION, AgentImplementation.TRAILBLAZE_RUNNER)

  @Test fun `blaze - ANDROID_ONDEVICE_INSTRUMENTATION x MULTI_AGENT_V3`() =
    blazeTest(TrailblazeDriverType.ANDROID_ONDEVICE_INSTRUMENTATION, AgentImplementation.MULTI_AGENT_V3)

  @Test fun `blaze - ANDROID_ONDEVICE_ACCESSIBILITY x TRAILBLAZE_RUNNER`() =
    blazeTest(TrailblazeDriverType.ANDROID_ONDEVICE_ACCESSIBILITY, AgentImplementation.TRAILBLAZE_RUNNER)

  @Test fun `blaze - ANDROID_ONDEVICE_ACCESSIBILITY x MULTI_AGENT_V3`() =
    blazeTest(TrailblazeDriverType.ANDROID_ONDEVICE_ACCESSIBILITY, AgentImplementation.MULTI_AGENT_V3)

  // ═══════════════════════════════════════════════════════════════════════════
  // Lifecycle
  // ═══════════════════════════════════════════════════════════════════════════

  /**
   * End the MCP session before the base class closes the HTTP client.
   *
   * Calling endSession triggers [TrailblazeDeviceManager.endSessionForDevice] which:
   *   1. Writes a terminal SessionStatus.Ended.Succeeded log so the Trailblaze report
   *      shows PASSED instead of ERROR/TIMEOUT.
   *   2. Releases the device claim so the next test can connect without hitting
   *      DeviceAlreadyClaimedException.
   *
   * This @After runs before TrailblazeServerTestBase.baseTearDown (JUnit 4 runs subclass
   * @After methods first), so the session is properly closed before client.close().
   */
  @After
  fun tearDown() = runBlocking {
    if (isClientInitialized()) {
      try {
        client.callTool("endSession", emptyMap())
      } catch (_: Exception) {
        // Non-fatal — base teardown will still close the client
      }
    }
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // Test Implementations
  // ═══════════════════════════════════════════════════════════════════════════

  /**
   * Blaze test: set driver type + agent implementation, connect, run a simple goal, verify.
   * Requires LLM credentials to be configured.
   */
  private fun blazeTest(driverType: TrailblazeDriverType, agentImpl: AgentImplementation) = runBlocking {
    val tag = "${driverType.name} x ${agentImpl.name}"
    Console.log("[$tag] Starting")

    // 1. Set driver type
    setDriverType(driverType.name)

    // 2. Connect to device
    val connectResult = client.callTool("device", mapOf("action" to "ANDROID", "testName" to tag))
    assumeTrue("No Android device available", connectResult.isSuccess)

    // 3. Set agent implementation
    val agentResult = client.callTool(
      "config",
      mapOf("action" to "SET", "key" to "agentImplementation", "value" to agentImpl.name),
    )
    Console.log("[$tag] Agent set: ${agentResult.content.take(200)}")
    assertTrue(agentResult.isSuccess, "[$tag] Setting agent should succeed: ${agentResult.content}")

    // 4. Run a simple blaze goal
    val blazeResult = client.callTool("blaze", mapOf("goal" to "Press the home button"))
    Console.log("[$tag] Blaze result: ${blazeResult.content.take(500)}")
    assertFalse(blazeResult.isError, "[$tag] Blaze should not error: ${blazeResult.content.take(500)}")

    // 5. Verify the result
    val verifyResult = client.callTool(
      "blaze",
      mapOf("goal" to "The home screen or launcher is visible", "hint" to "VERIFY"),
    )
    Console.log("[$tag] Verify result: ${verifyResult.content.take(500)}")
    assertFalse(
      verifyResult.isError,
      "[$tag] Verify should not error: ${verifyResult.content.take(500)}",
    )

    Console.log("[$tag] PASSED")
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // Helpers
  // ═══════════════════════════════════════════════════════════════════════════

  private suspend fun setDriverType(driverType: String) {
    val result = client.callTool(
      "config",
      mapOf("action" to "SET", "key" to "androidDriver", "value" to driverType),
    )
    Console.log("[$driverType] Config set: ${result.content.take(200)}")
  }

}
