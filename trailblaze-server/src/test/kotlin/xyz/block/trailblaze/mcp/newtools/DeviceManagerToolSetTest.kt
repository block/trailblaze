package xyz.block.trailblaze.mcp.newtools

import kotlinx.coroutines.test.runTest
import org.junit.Test
import xyz.block.trailblaze.api.ScreenState
import xyz.block.trailblaze.api.ScreenshotScalingConfig
import xyz.block.trailblaze.devices.TrailblazeConnectedDeviceSummary
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.mcp.AgentImplementation
import xyz.block.trailblaze.mcp.TrailblazeMcpBridge
import xyz.block.trailblaze.mcp.TrailblazeMcpMode
import xyz.block.trailblaze.mcp.TrailblazeMcpSessionContext
import xyz.block.trailblaze.mcp.android.ondevice.rpc.GetScreenStateResponse
import xyz.block.trailblaze.mcp.models.McpSessionId
import xyz.block.trailblaze.model.TrailblazeHostAppTarget
import xyz.block.trailblaze.toolcalls.TrailblazeTool
import kotlin.test.assertContains
import kotlin.test.assertEquals

/**
 * Tests for [DeviceManagerToolSet].
 *
 * Verifies that the device tool correctly:
 * - Lists available devices
 * - Connects to devices by platform (ANDROID, IOS)
 * - Connects to devices by ID
 * - Handles no devices available
 */
class DeviceManagerToolSetTest {

  private val testSessionId = McpSessionId("test-session")

  private fun createSessionContext() = TrailblazeMcpSessionContext(
    mcpServerSession = null,
    mcpSessionId = testSessionId,
    mode = TrailblazeMcpMode.MCP_CLIENT_AS_AGENT,
  )

  private val androidDevice = TrailblazeConnectedDeviceSummary(
    trailblazeDriverType = TrailblazeDriverType.ANDROID_HOST,
    instanceId = "emulator-5554",
    description = "Pixel 6 API 34",
  )

  private val iosDevice = TrailblazeConnectedDeviceSummary(
    trailblazeDriverType = TrailblazeDriverType.IOS_HOST,
    instanceId = "iphone-15-sim",
    description = "iPhone 15 Simulator",
  )

  // ── LIST action ───────────────────────────────────────────────────────────

  @Test
  fun `device LIST returns available devices`() = runTest {
    val bridge = DeviceTestBridge(devices = setOf(androidDevice, iosDevice))
    val toolSet = DeviceManagerToolSet(
      sessionContext = createSessionContext(),
      mcpBridge = bridge,
    )

    val result = toolSet.device(
      action = DeviceManagerToolSet.DeviceAction.LIST,
    )

    assertContains(result, "emulator-5554")
    assertContains(result, "iphone-15-sim")
  }

  @Test
  fun `device LIST returns message when no devices available`() = runTest {
    val bridge = DeviceTestBridge(devices = emptySet())
    val toolSet = DeviceManagerToolSet(
      sessionContext = createSessionContext(),
      mcpBridge = bridge,
    )

    val result = toolSet.device(
      action = DeviceManagerToolSet.DeviceAction.LIST,
    )

    assertContains(result, "No devices available")
  }

  // ── ANDROID action ────────────────────────────────────────────────────────

  @Test
  fun `device ANDROID connects to first Android device`() = runTest {
    val bridge = DeviceTestBridge(devices = setOf(androidDevice, iosDevice))
    val toolSet = DeviceManagerToolSet(
      sessionContext = createSessionContext(),
      mcpBridge = bridge,
    )

    val result = toolSet.device(
      action = DeviceManagerToolSet.DeviceAction.ANDROID,
    )

    assertContains(result, "emulator-5554")
    assertEquals("emulator-5554", bridge.lastSelectedDeviceId?.instanceId)
  }

  @Test
  fun `device ANDROID returns error when no Android devices`() = runTest {
    val bridge = DeviceTestBridge(devices = setOf(iosDevice))
    val toolSet = DeviceManagerToolSet(
      sessionContext = createSessionContext(),
      mcpBridge = bridge,
    )

    val result = toolSet.device(
      action = DeviceManagerToolSet.DeviceAction.ANDROID,
    )

    assertContains(result, "No Android")
  }

  // ── LIST action - description shown ─────────────────────────────────────

  @Test
  fun `device LIST includes device description`() = runTest {
    val bridge = DeviceTestBridge(devices = setOf(androidDevice))
    val toolSet = DeviceManagerToolSet(
      sessionContext = createSessionContext(),
      mcpBridge = bridge,
    )

    val result = toolSet.device(
      action = DeviceManagerToolSet.DeviceAction.LIST,
    )

    assertContains(result, "Pixel 6 API 34")
  }

  // ── INFO action ─────────────────────────────────────────────────────────

  @Test
  fun `device INFO returns summary of connected device`() = runTest {
    val bridge = DeviceTestBridge(
      devices = setOf(androidDevice),
      driverType = TrailblazeDriverType.ANDROID_HOST,
    )
    val toolSet = DeviceManagerToolSet(
      sessionContext = createSessionContext(),
      mcpBridge = bridge,
    )
    // Connect first
    toolSet.device(action = DeviceManagerToolSet.DeviceAction.ANDROID)

    val result = toolSet.device(
      action = DeviceManagerToolSet.DeviceAction.INFO,
    )

    assertContains(result, "emulator-5554")
    assertContains(result, "Android")
    assertContains(result, "ANDROID_HOST")
  }

  @Test
  fun `device INFO returns error when no device connected`() = runTest {
    val bridge = DeviceTestBridge(devices = setOf(androidDevice))
    val toolSet = DeviceManagerToolSet(
      sessionContext = createSessionContext(),
      mcpBridge = bridge,
    )

    val result = toolSet.device(
      action = DeviceManagerToolSet.DeviceAction.INFO,
    )

    assertContains(result, "No device connected")
  }

  @Test
  fun `device INFO APPS returns installed apps`() = runTest {
    val bridge = DeviceTestBridge(
      devices = setOf(androidDevice),
      installedApps = setOf("com.example.app1", "com.example.app2"),
    )
    val toolSet = DeviceManagerToolSet(
      sessionContext = createSessionContext(),
      mcpBridge = bridge,
    )
    toolSet.device(action = DeviceManagerToolSet.DeviceAction.ANDROID)

    val result = toolSet.device(
      action = DeviceManagerToolSet.DeviceAction.INFO,
      detail = DeviceManagerToolSet.DeviceDetail.APPS,
    )

    assertContains(result, "com.example.app1")
    assertContains(result, "com.example.app2")
    assertContains(result, "2")
  }

  @Test
  fun `device INFO FULL returns summary and apps`() = runTest {
    val bridge = DeviceTestBridge(
      devices = setOf(androidDevice),
      driverType = TrailblazeDriverType.ANDROID_HOST,
      installedApps = setOf("com.example.app"),
    )
    val toolSet = DeviceManagerToolSet(
      sessionContext = createSessionContext(),
      mcpBridge = bridge,
    )
    toolSet.device(action = DeviceManagerToolSet.DeviceAction.ANDROID)

    val result = toolSet.device(
      action = DeviceManagerToolSet.DeviceAction.INFO,
      detail = DeviceManagerToolSet.DeviceDetail.FULL,
    )

    assertContains(result, "emulator-5554")
    assertContains(result, "ANDROID_HOST")
    assertContains(result, "com.example.app")
  }

  // ── WEB action ──────────────────────────────────────────────────────────

  @Test
  fun `device WEB connects to web browser`() = runTest {
    val webDevice = TrailblazeConnectedDeviceSummary(
      trailblazeDriverType = TrailblazeDriverType.PLAYWRIGHT_NATIVE,
      instanceId = "playwright-native",
      description = "Playwright Browser (Native)",
    )
    val bridge = DeviceTestBridge(devices = setOf(androidDevice, webDevice))
    val toolSet = DeviceManagerToolSet(
      sessionContext = createSessionContext(),
      mcpBridge = bridge,
    )

    val result = toolSet.device(
      action = DeviceManagerToolSet.DeviceAction.WEB,
    )

    assertContains(result, "playwright-native")
    assertEquals("playwright-native", bridge.lastSelectedDeviceId?.instanceId)
  }

  @Test
  fun `device WEB returns error when no web devices`() = runTest {
    val bridge = DeviceTestBridge(devices = setOf(androidDevice))
    val toolSet = DeviceManagerToolSet(
      sessionContext = createSessionContext(),
      mcpBridge = bridge,
    )

    val result = toolSet.device(
      action = DeviceManagerToolSet.DeviceAction.WEB,
    )

    assertContains(result, "No web browser")
  }

  // ── CONNECT action ────────────────────────────────────────────────────────

  @Test
  fun `device CONNECT connects to specified device`() = runTest {
    val bridge = DeviceTestBridge(devices = setOf(androidDevice))
    val toolSet = DeviceManagerToolSet(
      sessionContext = createSessionContext(),
      mcpBridge = bridge,
    )

    val result = toolSet.device(
      action = DeviceManagerToolSet.DeviceAction.CONNECT,
      deviceId = "emulator-5554",
    )

    assertEquals("emulator-5554", bridge.lastSelectedDeviceId?.instanceId)
  }

  @Test
  fun `device CONNECT returns error without deviceId`() = runTest {
    val bridge = DeviceTestBridge(devices = setOf(androidDevice))
    val toolSet = DeviceManagerToolSet(
      sessionContext = createSessionContext(),
      mcpBridge = bridge,
    )

    val result = toolSet.device(
      action = DeviceManagerToolSet.DeviceAction.CONNECT,
      deviceId = null,
    )

    assertContains(result.lowercase(), "device")
  }
}

/**
 * Mock bridge for device manager tests.
 */
class DeviceTestBridge(
  private val devices: Set<TrailblazeConnectedDeviceSummary> = emptySet(),
  private val driverType: TrailblazeDriverType? = null,
  private val installedApps: Set<String> = emptySet(),
) : TrailblazeMcpBridge {

  var lastSelectedDeviceId: TrailblazeDeviceId? = null

  override suspend fun getAvailableDevices(): Set<TrailblazeConnectedDeviceSummary> = devices

  override suspend fun selectDevice(trailblazeDeviceId: TrailblazeDeviceId): TrailblazeConnectedDeviceSummary {
    lastSelectedDeviceId = trailblazeDeviceId
    return devices.first { it.instanceId == trailblazeDeviceId.instanceId }
  }

  override suspend fun executeTrailblazeTool(tool: TrailblazeTool, blocking: Boolean): String = "[OK]"
  override suspend fun getInstalledAppIds(): Set<String> = installedApps
  override fun getAvailableAppTargets(): Set<TrailblazeHostAppTarget> = emptySet()
  override suspend fun runYaml(yaml: String, startNewSession: Boolean, agentImplementation: AgentImplementation) = ""
  override fun getCurrentlySelectedDeviceId(): TrailblazeDeviceId? = lastSelectedDeviceId
  override suspend fun getCurrentScreenState(): ScreenState? = null
  override fun getDirectScreenStateProvider(): ((ScreenshotScalingConfig) -> ScreenState)? = null
  override suspend fun endSession(): Boolean = true
  override fun isOnDeviceInstrumentation(): Boolean = false
  override fun getDriverType(): TrailblazeDriverType? = if (lastSelectedDeviceId != null) driverType else null
  override suspend fun getScreenStateViaRpc(
    includeScreenshot: Boolean,
    screenshotScalingConfig: ScreenshotScalingConfig,
  ): GetScreenStateResponse? = null
  override fun getActiveSessionId(): SessionId? = null
  override fun cancelAutomation(deviceId: TrailblazeDeviceId) {}
  override fun selectAppTarget(appTargetId: String): String? = null
  override fun getCurrentAppTargetId(): String? = null
}
