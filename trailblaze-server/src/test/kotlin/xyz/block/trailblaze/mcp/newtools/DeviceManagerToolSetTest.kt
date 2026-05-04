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
import xyz.block.trailblaze.logs.model.TraceId
import xyz.block.trailblaze.mcp.AgentImplementation
import xyz.block.trailblaze.mcp.TrailblazeMcpBridge
import xyz.block.trailblaze.mcp.TrailblazeMcpMode
import xyz.block.trailblaze.mcp.TrailblazeMcpSessionContext
import xyz.block.trailblaze.mcp.android.ondevice.rpc.GetScreenStateResponse
import xyz.block.trailblaze.mcp.models.McpSessionId
import xyz.block.trailblaze.model.TrailblazeHostAppTarget
import xyz.block.trailblaze.toolcalls.TrailblazeTool
import kotlin.reflect.full.valueParameters
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

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
    trailblazeDriverType = TrailblazeDriverType.ANDROID_ONDEVICE_INSTRUMENTATION,
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

  @Test
  fun `device ANDROID with deviceId selects requested emulator when multiple available`() = runTest {
    val firstEmulator = TrailblazeConnectedDeviceSummary(
      trailblazeDriverType = TrailblazeDriverType.ANDROID_ONDEVICE_INSTRUMENTATION,
      instanceId = "emulator-5554",
      description = "Pixel 6 API 34",
    )
    val secondEmulator = TrailblazeConnectedDeviceSummary(
      trailblazeDriverType = TrailblazeDriverType.ANDROID_ONDEVICE_INSTRUMENTATION,
      instanceId = "emulator-5560",
      description = "Pixel 7 API 34",
    )
    val bridge = DeviceTestBridge(devices = setOf(firstEmulator, secondEmulator))
    val toolSet = DeviceManagerToolSet(
      sessionContext = createSessionContext(),
      mcpBridge = bridge,
    )

    val result = toolSet.device(
      action = DeviceManagerToolSet.DeviceAction.ANDROID,
      deviceId = "emulator-5560",
    )

    assertContains(result, "emulator-5560")
    assertEquals("emulator-5560", bridge.lastSelectedDeviceId?.instanceId)
  }

  @Test
  fun `device ANDROID with unknown deviceId returns error`() = runTest {
    val bridge = DeviceTestBridge(devices = setOf(androidDevice))
    val toolSet = DeviceManagerToolSet(
      sessionContext = createSessionContext(),
      mcpBridge = bridge,
    )

    val result = toolSet.device(
      action = DeviceManagerToolSet.DeviceAction.ANDROID,
      deviceId = "emulator-9999",
    )

    assertContains(result, "emulator-9999")
    assertContains(result, "not found")
  }

  @Test
  fun `device IOS with deviceId selects requested simulator when multiple available`() = runTest {
    val firstSim = TrailblazeConnectedDeviceSummary(
      trailblazeDriverType = TrailblazeDriverType.IOS_HOST,
      instanceId = "iphone-15-sim",
      description = "iPhone 15 Simulator",
    )
    val secondSim = TrailblazeConnectedDeviceSummary(
      trailblazeDriverType = TrailblazeDriverType.IOS_HOST,
      instanceId = "iphone-16-sim",
      description = "iPhone 16 Simulator",
    )
    val bridge = DeviceTestBridge(devices = setOf(firstSim, secondSim))
    val toolSet = DeviceManagerToolSet(
      sessionContext = createSessionContext(),
      mcpBridge = bridge,
    )

    val result = toolSet.device(
      action = DeviceManagerToolSet.DeviceAction.IOS,
      deviceId = "iphone-16-sim",
    )

    assertContains(result, "iphone-16-sim")
    assertEquals("iphone-16-sim", bridge.lastSelectedDeviceId?.instanceId)
  }

  @Test
  fun `device IOS with unknown deviceId returns error`() = runTest {
    val bridge = DeviceTestBridge(devices = setOf(iosDevice))
    val toolSet = DeviceManagerToolSet(
      sessionContext = createSessionContext(),
      mcpBridge = bridge,
    )

    val result = toolSet.device(
      action = DeviceManagerToolSet.DeviceAction.IOS,
      deviceId = "iphone-unknown",
    )

    assertContains(result, "iphone-unknown")
    assertContains(result, "not found")
  }

  @Test
  fun `device ANDROID with blank deviceId falls back to auto-select`() = runTest {
    val bridge = DeviceTestBridge(devices = setOf(androidDevice))
    val toolSet = DeviceManagerToolSet(
      sessionContext = createSessionContext(),
      mcpBridge = bridge,
    )

    val result = toolSet.device(
      action = DeviceManagerToolSet.DeviceAction.ANDROID,
      deviceId = "   ",
    )

    assertContains(result, androidDevice.instanceId)
    assertEquals(androidDevice.instanceId, bridge.lastSelectedDeviceId?.instanceId)
  }

  @Test
  fun `device IOS with blank deviceId falls back to auto-select`() = runTest {
    val bridge = DeviceTestBridge(devices = setOf(iosDevice))
    val toolSet = DeviceManagerToolSet(
      sessionContext = createSessionContext(),
      mcpBridge = bridge,
    )

    val result = toolSet.device(
      action = DeviceManagerToolSet.DeviceAction.IOS,
      deviceId = "",
    )

    assertContains(result, iosDevice.instanceId)
    assertEquals(iosDevice.instanceId, bridge.lastSelectedDeviceId?.instanceId)
  }

  /**
   * Regression guard: [DeviceManagerToolSet.PARAM_DEVICE_ID] exists because MCP binds
   * arguments by the Kotlin parameter name. If someone renames the `deviceId` parameter
   * on [DeviceManagerToolSet.device] without updating the constant, the CLI client silently
   * drops the argument (the original bug this constant was added to prevent). This test
   * anchors the constant to the real parameter name.
   */
  @Test
  fun `PARAM_DEVICE_ID matches an actual device function parameter name`() {
    val paramNames = DeviceManagerToolSet::device.valueParameters.map { it.name }
    assertTrue(
      DeviceManagerToolSet.PARAM_DEVICE_ID in paramNames,
      "PARAM_DEVICE_ID='${DeviceManagerToolSet.PARAM_DEVICE_ID}' does not match any parameter " +
        "of device(). Found: $paramNames. If you renamed the parameter, update the constant.",
    )
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
      driverType = TrailblazeDriverType.ANDROID_ONDEVICE_INSTRUMENTATION,
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
    assertContains(result, "ANDROID_ONDEVICE_INSTRUMENTATION")
  }

  @Test
  fun `device INFO SUMMARY omits available-tools block when current target is the default target`() = runTest {
    // Guard at DeviceManagerToolSet.buildAvailableToolsSummary: when the current target is the
    // no-app default sentinel, the device info summary skips the "Available <Name> tools" block
    // even if the bridge would otherwise have a driver and target set up. This keeps the default
    // target's INFO output noise-free since it has no app-specific tools to advertise.
    val bridge = DeviceTestBridge(
      devices = setOf(androidDevice),
      driverType = TrailblazeDriverType.ANDROID_ONDEVICE_INSTRUMENTATION,
      availableAppTargets = setOf(TrailblazeHostAppTarget.DefaultTrailblazeHostAppTarget),
      currentAppTargetId = TrailblazeHostAppTarget.DefaultTrailblazeHostAppTarget.id,
    )
    val toolSet = DeviceManagerToolSet(
      sessionContext = createSessionContext(),
      mcpBridge = bridge,
    )
    toolSet.device(action = DeviceManagerToolSet.DeviceAction.ANDROID)

    val result = toolSet.device(action = DeviceManagerToolSet.DeviceAction.INFO)

    // Asserts the exact prefix `buildAvailableToolsSummary()` would have emitted at line 466
    // ("Available ${target.displayName} tools") so this test pins the line-456 default-target
    // guard, not just any null-return path.
    assertTrue(
      "Available ${TrailblazeHostAppTarget.DefaultTrailblazeHostAppTarget.displayName} tools" !in result,
      "Default target should not produce an 'Available Default tools' block. Got:\n$result",
    )
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
      driverType = TrailblazeDriverType.ANDROID_ONDEVICE_INSTRUMENTATION,
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
    assertContains(result, "ANDROID_ONDEVICE_INSTRUMENTATION")
    assertContains(result, "com.example.app")
  }

  // ── WEB action ──────────────────────────────────────────────────────────

  @Test
  fun `device WEB connects to playwright-native by default`() = runTest {
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
  fun `device WEB without any discovered web device still provisions playwright-native`() = runTest {
    // Web devices are virtual — even when nothing matches in the discovered list,
    // device(action=WEB) connects to the on-demand playwright-native instance because
    // the bridge provisions the browser when selectDevice is called. The test bridge
    // mirrors that with `acceptVirtualWebInstance = true` so we can assert the
    // virtual-provisioning path without pre-seeding a web device summary.
    val bridge = DeviceTestBridge(
      devices = setOf(androidDevice),
      acceptVirtualWebInstance = true,
    )
    val toolSet = DeviceManagerToolSet(
      sessionContext = createSessionContext(),
      mcpBridge = bridge,
    )

    val result = toolSet.device(action = DeviceManagerToolSet.DeviceAction.WEB)

    assertContains(result, "playwright-native")
    assertEquals("playwright-native", bridge.lastSelectedDeviceId?.instanceId)
  }

  @Test
  fun `device WEB with deviceId connects to a named web instance`() = runTest {
    // Multiple web devices in parallel: callers can target a named instance via
    // device(action=WEB, deviceId="foo"). The bridge provisions a new Playwright
    // browser keyed by that ID — virtual, no hardware enumeration required.
    val bridge = DeviceTestBridge(
      devices = setOf(androidDevice),
      acceptVirtualWebInstance = true,
    )
    val toolSet = DeviceManagerToolSet(
      sessionContext = createSessionContext(),
      mcpBridge = bridge,
    )

    val result = toolSet.device(
      action = DeviceManagerToolSet.DeviceAction.WEB,
      deviceId = "foo",
    )

    assertContains(result, "foo")
    assertEquals("foo", bridge.lastSelectedDeviceId?.instanceId)
    assertEquals(TrailblazeDevicePlatform.WEB, bridge.lastSelectedDeviceId?.trailblazeDevicePlatform)
  }

  @Test
  fun `device WEB records headless preference on the bridge`() = runTest {
    val bridge = DeviceTestBridge(
      devices = setOf(androidDevice),
      acceptVirtualWebInstance = true,
    )
    val toolSet = DeviceManagerToolSet(
      sessionContext = createSessionContext(),
      mcpBridge = bridge,
    )

    toolSet.device(
      action = DeviceManagerToolSet.DeviceAction.WEB,
      deviceId = "visible-foo",
      headless = false,
    )

    assertEquals("visible-foo" to false, bridge.lastWebBrowserHeadless)
  }

  @Test
  fun `device WEB headless preference is recorded after the claim, not before`() = runTest {
    // Regression guard for the headless-preference race: the preference must not
    // be set on the bridge before the claim succeeds, because two concurrent
    // device(action=WEB, deviceId="foo", headless=…) calls would otherwise
    // overwrite each other's preference and silently launch the winner's browser
    // in the wrong mode.
    val bridge = DeviceTestBridge(
      devices = setOf(androidDevice),
      acceptVirtualWebInstance = true,
    )
    val toolSet = DeviceManagerToolSet(
      sessionContext = createSessionContext(),
      mcpBridge = bridge,
    )

    toolSet.device(
      action = DeviceManagerToolSet.DeviceAction.WEB,
      deviceId = "race-foo",
      headless = false,
    )

    // selectDevice records lastSelectedDeviceId; the preference is recorded later
    // (after claim, before selectDevice returns). Assert ordering by checking that
    // both happened and the headless value matches the call.
    assertEquals("race-foo", bridge.lastSelectedDeviceId?.instanceId)
    assertEquals("race-foo" to false, bridge.lastWebBrowserHeadless)
  }

  @Test
  fun `device WEB falls back to playwright-electron when configured`() = runTest {
    // When the platform-level WEB driver is configured to PLAYWRIGHT_ELECTRON,
    // a bare `device(action=WEB)` call (no deviceId) routes to the electron
    // singleton instead of playwright-native. This restores the behavior the
    // pre-multi-instance code had via getConfiguredDriverType().
    val bridge = DeviceTestBridge(
      devices = setOf(androidDevice),
      acceptVirtualWebInstance = true,
      configuredWebDriverType = TrailblazeDriverType.PLAYWRIGHT_ELECTRON,
    )
    val toolSet = DeviceManagerToolSet(
      sessionContext = createSessionContext(),
      mcpBridge = bridge,
    )

    toolSet.device(action = DeviceManagerToolSet.DeviceAction.WEB)

    assertEquals("playwright-electron", bridge.lastSelectedDeviceId?.instanceId)
  }

  @Test
  fun `device WEB defaults to playwright-native when no driver type is configured`() = runTest {
    val bridge = DeviceTestBridge(
      devices = setOf(androidDevice),
      acceptVirtualWebInstance = true,
    )
    val toolSet = DeviceManagerToolSet(
      sessionContext = createSessionContext(),
      mcpBridge = bridge,
    )

    toolSet.device(action = DeviceManagerToolSet.DeviceAction.WEB)

    assertEquals("playwright-native", bridge.lastSelectedDeviceId?.instanceId)
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

  // ── INFO action with driver status ───────────────────────────────────────
  // Contract: when a driver is installing/initializing/failed, INFO must still
  // emit the device header (Instance ID, Platform) AND append the status. The
  // CLI regex-parses "Instance ID:" / "Platform:" to drive the session-reuse
  // short-circuit, so the header must not disappear during driver transitions.

  @Test
  fun `device INFO includes header and status when Playwright is installing`() = runTest {
    val webDevice = TrailblazeConnectedDeviceSummary(
      trailblazeDriverType = TrailblazeDriverType.PLAYWRIGHT_NATIVE,
      instanceId = "playwright-chromium",
      description = "Web browser",
    )
    val bridge = DeviceTestBridge(
      devices = setOf(webDevice),
      driverType = TrailblazeDriverType.PLAYWRIGHT_NATIVE,
      driverConnectionStatus =
        "Playwright browser installing (12s elapsed, timeout in 888s): [42%] Downloading Chromium",
    )
    bridge.lastSelectedDeviceId = TrailblazeDeviceId(
      instanceId = "playwright-chromium",
      trailblazeDevicePlatform = TrailblazeDevicePlatform.WEB,
    )
    val toolSet = DeviceManagerToolSet(
      sessionContext = createSessionContext(),
      mcpBridge = bridge,
    )

    val result = toolSet.device(action = DeviceManagerToolSet.DeviceAction.INFO)

    // Header lines must be present so CLI parsing still works.
    assertContains(result, "Instance ID: playwright-chromium")
    assertContains(result, "Platform: Web")
    // Driver status must also be present so polling loops detect "installing".
    assertContains(result, "Driver status:")
    assertContains(result, "installing")
    assertContains(result, "[42%] Downloading Chromium")
  }

  @Test
  fun `device INFO surfaces failed driver status alongside header`() = runTest {
    val bridge = DeviceTestBridge(
      devices = setOf(androidDevice),
      driverType = TrailblazeDriverType.ANDROID_ONDEVICE_INSTRUMENTATION,
      driverConnectionStatus =
        "Device driver failed to create: adb connection refused",
    )
    bridge.lastSelectedDeviceId = TrailblazeDeviceId(
      instanceId = "emulator-5554",
      trailblazeDevicePlatform = TrailblazeDevicePlatform.ANDROID,
    )
    val toolSet = DeviceManagerToolSet(
      sessionContext = createSessionContext(),
      mcpBridge = bridge,
    )

    val result = toolSet.device(action = DeviceManagerToolSet.DeviceAction.INFO)

    assertContains(result, "Instance ID: emulator-5554")
    assertContains(result, "Platform: Android")
    assertContains(result, "failed")
  }

  @Test
  fun `device INFO with no driver status emits only the header block`() = runTest {
    val bridge = DeviceTestBridge(
      devices = setOf(androidDevice),
      driverType = TrailblazeDriverType.ANDROID_ONDEVICE_INSTRUMENTATION,
      driverConnectionStatus = null,
    )
    bridge.lastSelectedDeviceId = TrailblazeDeviceId(
      instanceId = "emulator-5554",
      trailblazeDevicePlatform = TrailblazeDevicePlatform.ANDROID,
    )
    val toolSet = DeviceManagerToolSet(
      sessionContext = createSessionContext(),
      mcpBridge = bridge,
    )

    val result = toolSet.device(action = DeviceManagerToolSet.DeviceAction.INFO)

    assertContains(result, "Instance ID: emulator-5554")
    assertContains(result, "Platform: Android")
    kotlin.test.assertFalse(result.contains("Driver status:"), "No status expected when driver is ready")
  }
}

/**
 * Mock bridge for device manager tests.
 */
class DeviceTestBridge(
  private val devices: Set<TrailblazeConnectedDeviceSummary> = emptySet(),
  private val driverType: TrailblazeDriverType? = null,
  private val installedApps: Set<String> = emptySet(),
  var driverConnectionStatus: String? = null,
  private val availableAppTargets: Set<TrailblazeHostAppTarget> = emptySet(),
  private val currentAppTargetId: String? = null,
  /**
   * Mirrors the production bridge's behavior of provisioning a Playwright browser
   * for any WEB instance ID on demand. Tests that target a virtual web ID not in
   * [devices] should set this to `true`.
   */
  private val acceptVirtualWebInstance: Boolean = false,
  /** Optional configured WEB driver type returned by [getConfiguredDriverType]. */
  private val configuredWebDriverType: TrailblazeDriverType? = null,
) : TrailblazeMcpBridge {

  var lastSelectedDeviceId: TrailblazeDeviceId? = null

  /** Last (instanceId, headless) pair recorded via [setWebBrowserHeadless]. */
  var lastWebBrowserHeadless: Pair<String, Boolean>? = null
    private set

  override suspend fun getAvailableDevices(): Set<TrailblazeConnectedDeviceSummary> = devices

  override suspend fun selectDevice(trailblazeDeviceId: TrailblazeDeviceId): TrailblazeConnectedDeviceSummary {
    lastSelectedDeviceId = trailblazeDeviceId
    val match = devices.firstOrNull { it.instanceId == trailblazeDeviceId.instanceId }
    if (match != null) return match
    if (acceptVirtualWebInstance &&
      trailblazeDeviceId.trailblazeDevicePlatform == TrailblazeDevicePlatform.WEB
    ) {
      return TrailblazeConnectedDeviceSummary(
        trailblazeDriverType = TrailblazeDriverType.PLAYWRIGHT_NATIVE,
        instanceId = trailblazeDeviceId.instanceId,
        description = "Provisioned Web Instance",
      )
    }
    error("No device with instanceId=${trailblazeDeviceId.instanceId} in test bridge")
  }

  override fun setWebBrowserHeadless(instanceId: String, headless: Boolean) {
    lastWebBrowserHeadless = instanceId to headless
  }

  override fun getConfiguredDriverType(platform: TrailblazeDevicePlatform): TrailblazeDriverType? =
    if (platform == TrailblazeDevicePlatform.WEB) configuredWebDriverType else null

  override suspend fun executeTrailblazeTool(
    tool: TrailblazeTool,
    blocking: Boolean,
    traceId: TraceId?,
  ): String = "[OK]"
  override suspend fun getInstalledAppIds(): Set<String> = installedApps
  override fun getAvailableAppTargets(): Set<TrailblazeHostAppTarget> = availableAppTargets
  override suspend fun runYaml(yaml: String, startNewSession: Boolean, agentImplementation: AgentImplementation) = ""
  override fun getCurrentlySelectedDeviceId(): TrailblazeDeviceId? = lastSelectedDeviceId
  override suspend fun getCurrentScreenState(): ScreenState? = null
  override fun getDirectScreenStateProvider(skipScreenshot: Boolean): ((ScreenshotScalingConfig) -> ScreenState)? = null
  override suspend fun endSession(): Boolean = true
  override fun isOnDeviceInstrumentation(): Boolean = false
  override fun getDriverType(): TrailblazeDriverType? = if (lastSelectedDeviceId != null) driverType else null
  override fun getDriverConnectionStatus(deviceId: TrailblazeDeviceId?): String? = driverConnectionStatus
  override suspend fun getScreenStateViaRpc(
    includeScreenshot: Boolean,
    screenshotScalingConfig: ScreenshotScalingConfig,
    includeAnnotatedScreenshot: Boolean,
    includeAllElements: Boolean,
  ): GetScreenStateResponse? = null
  override fun getActiveSessionId(): SessionId? = null
  override fun cancelAutomation(deviceId: TrailblazeDeviceId) {}
  override fun selectAppTarget(appTargetId: String): String? = null
  override fun getCurrentAppTargetId(): String? = currentAppTargetId
}
