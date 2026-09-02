package xyz.block.trailblaze.logs.server

import kotlin.test.assertFalse
import kotlin.test.assertTrue
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
import xyz.block.trailblaze.mcp.TrailblazeMcpSessionContext
import xyz.block.trailblaze.mcp.android.ondevice.rpc.GetScreenStateResponse
import xyz.block.trailblaze.mcp.models.McpSessionId
import xyz.block.trailblaze.model.TrailblazeHostAppTarget
import xyz.block.trailblaze.report.utils.LogsRepo
import xyz.block.trailblaze.toolcalls.SessionDeviceBindings
import xyz.block.trailblaze.toolcalls.TrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.SwitchDeviceTrailblazeTool

/**
 * Pins WHEN `switchDevice` is advertised to an MCP client: only to a session holding two or more
 * named device bindings.
 *
 * Both directions matter and are asserted against the SDK server's actual tool map, not the Koog
 * registry. A tool that appears too early gives a single-device session an unusable verb; one that
 * survives the roster dropping back to a single device keeps offering a handover that can no longer
 * happen.
 *
 * This drives registration directly, so what it pins is the GATE: given a roster of size n, is the
 * tool in the server's tool map. That a roster change actually chains a re-registration is the other
 * half, and it is pinned where the `device` tool can be driven end to end —
 * `DeviceManagerToolSetNamedBindingsTest.every bind and unbind re-registers the session's tools`.
 */
class TrailblazeMcpServerSwitchDeviceAdvertisementTest {

  @get:Rule
  val tempFolder = TemporaryFolder()

  @Test
  fun `switchDevice is advertised only while the session has more than one named device`() {
    val server = newServer()
    val mcpServer = server.configureMcpServer()
    val sessionId = McpSessionId("test-session")
    val sessionContext = TrailblazeMcpSessionContext(
      mcpServerSession = null,
      mcpSessionId = sessionId,
    )

    server.registerTools(mcpServer, sessionId, sessionContext)
    assertFalse(
      mcpServer.advertisesSwitchDevice(),
      "A session with no named bindings has nothing to switch between.",
    )

    sessionContext.bindNamedDevice("seller", boundDevice("emulator-5554"))
    server.registerTools(mcpServer, sessionId, sessionContext)
    assertFalse(
      mcpServer.advertisesSwitchDevice(),
      "One named device is still a single-device session — switchDevice has no other name to take.",
    )

    sessionContext.bindNamedDevice("buyer", boundDevice("emulator-5556"))
    server.registerTools(mcpServer, sessionId, sessionContext)
    assertTrue(
      mcpServer.advertisesSwitchDevice(),
      "With two named devices bound the session must be able to hand itself over.",
    )

    sessionContext.unbindNamedDevice("buyer")
    server.registerTools(mcpServer, sessionId, sessionContext)
    assertFalse(
      mcpServer.advertisesSwitchDevice(),
      "Dropping back to one device must retract the tool, not leave it callable.",
    )
  }

  // ---- helpers ---------------------------------------------------------------------------------

  private fun io.modelcontextprotocol.kotlin.sdk.server.Server.advertisesSwitchDevice(): Boolean =
    tools.containsKey(SwitchDeviceTrailblazeTool.ADVERTISED_TOOL_NAME)

  private fun newServer(): TrailblazeMcpServer = TrailblazeMcpServer(
    logsRepo = LogsRepo(logsDir = tempFolder.newFolder("logs"), watchFileSystem = false),
    mcpBridge = InertBridge(),
    trailsDirProvider = { tempFolder.newFolder("trails") },
    targetTestAppProvider = { TrailblazeHostAppTarget.DefaultTrailblazeHostAppTarget },
    llmModelListsProvider = { emptySet() },
  )

  private fun boundDevice(instanceId: String) = SessionDeviceBindings.BoundDevice(
    trailblazeDeviceId = TrailblazeDeviceId(instanceId, TrailblazeDevicePlatform.ANDROID),
    trailblazeDeviceInfo = null,
    description = null,
    targetId = null,
  )

  /** No devices, no driver: registration resolves an empty device-scoped surface. */
  private class InertBridge : TrailblazeMcpBridge {
    override suspend fun selectDevice(
      trailblazeDeviceId: TrailblazeDeviceId,
    ): TrailblazeConnectedDeviceSummary = throw NotImplementedError()
    override suspend fun getAvailableDevices(): Set<TrailblazeConnectedDeviceSummary> = emptySet()
    override fun getCurrentlySelectedDeviceId(): TrailblazeDeviceId? = null
    override suspend fun getInstalledAppIds(): Set<String> = emptySet()
    override fun getAvailableAppTargets(): Set<TrailblazeHostAppTarget> = emptySet()
    override suspend fun runYaml(
      yaml: String,
      startNewSession: Boolean,
      agentImplementation: AgentImplementation,
    ): String = throw NotImplementedError()
    override suspend fun getCurrentScreenState(): ScreenState? = null
    override suspend fun executeTrailblazeTool(
      tool: TrailblazeTool,
      blocking: Boolean,
      traceId: TraceId?,
    ): String = throw NotImplementedError()
    override suspend fun endSession(): Boolean = false
    override fun selectAppTarget(appTargetId: String): String? = null
    override fun getCurrentAppTargetId(): String? = null
    override fun getDriverType(): TrailblazeDriverType? = null
    override suspend fun getScreenStateViaRpc(
      includeScreenshot: Boolean,
      screenshotScalingConfig: ScreenshotScalingConfig,
      includeAnnotatedScreenshot: Boolean,
      includeAllElements: Boolean,
    ): GetScreenStateResponse? = null
    override fun getActiveSessionId(): SessionId? = null
    override suspend fun ensureSessionAndGetId(testName: String?): SessionId? = null
  }
}
