package xyz.block.trailblaze.logs.server

import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
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
import xyz.block.trailblaze.mcp.android.ondevice.rpc.GetScreenStateResponse
import xyz.block.trailblaze.model.TrailblazeHostAppTarget
import xyz.block.trailblaze.report.utils.LogsRepo
import xyz.block.trailblaze.toolcalls.TrailblazeTool
import java.io.File
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins the platform/claim gating that prevents `releasePersistentDeviceConnection`
 * from wedging `system_server` after Android session displacement (build 5463).
 * Companion to host-side fast-fail (PR #2848) and CI retry-on-timeout (PR #2865).
 */
class TrailblazeMcpServerReleaseConnectionGateTest {

  @get:Rule
  val tempFolder = TemporaryFolder()

  private fun newServer(): TrailblazeMcpServer = TrailblazeMcpServer(
    logsRepo = LogsRepo(logsDir = tempFolder.newFolder("logs"), watchFileSystem = false),
    mcpBridge = NoopBridge,
    trailsDirProvider = { tempFolder.newFolder("trails") },
    targetTestAppProvider = { TrailblazeHostAppTarget.DefaultTrailblazeHostAppTarget },
    llmModelListsProvider = { emptySet() },
  )

  private fun androidDevice(id: String = "emulator-5554") =
    TrailblazeDeviceId(instanceId = id, trailblazeDevicePlatform = TrailblazeDevicePlatform.ANDROID)

  private fun iosDevice(id: String = "iphone-15-sim") =
    TrailblazeDeviceId(instanceId = id, trailblazeDevicePlatform = TrailblazeDevicePlatform.IOS)

  @Test
  fun `iOS always releases regardless of claim state`() {
    val server = newServer()
    val device = iosDevice()
    server.deviceClaimRegistry.claim(device, "session-other")

    assertTrue(
      server.shouldReleasePersistentDeviceConnectionOnSessionClose(device, "session-closing"),
      "iOS must always release — XCTest connections go stale across MCP sessions",
    )
  }

  @Test
  fun `Android skips release when another active session claims the device`() {
    val server = newServer()
    val device = androidDevice()
    server.deviceClaimRegistry.claim(device, "session-displacer")

    assertFalse(
      server.shouldReleasePersistentDeviceConnectionOnSessionClose(device, "session-closing"),
      "Android must skip release when a different session still claims the device",
    )
  }

  @Test
  fun `Android releases when no other session claims the device`() {
    val server = newServer()
    val device = androidDevice()

    assertTrue(
      server.shouldReleasePersistentDeviceConnectionOnSessionClose(device, "session-closing"),
      "Android must release when the registry has no claim (last claimant case)",
    )
  }

  @Test
  fun `Android releases when the closing session is the current claimant`() {
    val server = newServer()
    val device = androidDevice()
    server.deviceClaimRegistry.claim(device, "session-closing")

    assertTrue(
      server.shouldReleasePersistentDeviceConnectionOnSessionClose(device, "session-closing"),
      "Android must release when the closing session still owns the claim",
    )
  }
}

private object NoopBridge : TrailblazeMcpBridge {
  override suspend fun selectDevice(trailblazeDeviceId: TrailblazeDeviceId): TrailblazeConnectedDeviceSummary =
    error("not used")
  override suspend fun getAvailableDevices(): Set<TrailblazeConnectedDeviceSummary> = emptySet()
  override suspend fun executeTrailblazeTool(
    tool: TrailblazeTool,
    blocking: Boolean,
    traceId: TraceId?,
  ): String = "[OK]"
  override suspend fun getInstalledAppIds(): Set<String> = emptySet()
  override fun getAvailableAppTargets(): Set<TrailblazeHostAppTarget> = emptySet()
  override suspend fun runYaml(
    yaml: String,
    startNewSession: Boolean,
    agentImplementation: AgentImplementation,
  ): String = ""
  override fun getCurrentlySelectedDeviceId(): TrailblazeDeviceId? = null
  override suspend fun getCurrentScreenState(): ScreenState? = null
  override fun getDirectScreenStateProvider(skipScreenshot: Boolean): ((ScreenshotScalingConfig) -> ScreenState)? = null
  override suspend fun endSession(): Boolean = true
  override fun isOnDeviceInstrumentation(): Boolean = false
  override fun getDriverType(): TrailblazeDriverType? = null
  override fun getDriverConnectionStatus(deviceId: TrailblazeDeviceId?): String? = null
  override suspend fun getScreenStateViaRpc(
    includeScreenshot: Boolean,
    screenshotScalingConfig: ScreenshotScalingConfig,
    includeAnnotatedScreenshot: Boolean,
    includeAllElements: Boolean,
  ): GetScreenStateResponse? = null
  override fun getActiveSessionId(): SessionId? = null
  override fun cancelAutomation(deviceId: TrailblazeDeviceId) {}
  override fun selectAppTarget(appTargetId: String): String? = null
  override fun getCurrentAppTargetId(): String? = null
}
