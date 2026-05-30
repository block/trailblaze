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
import kotlin.reflect.KClass
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Pins the per-device → daemon-wide → null resolution chain in
 * [TrailblazeMcpServer.findCurrentTarget].
 *
 * The function is consulted by every surface that builds a per-device tool
 * list (the inner-agent tools provider, the script-tool runtime gate, the
 * tool-discovery `currentTargetProvider`). If a future refactor breaks any
 * link of the chain — e.g. someone reverts to reading `getCurrentAppTargetId`
 * directly, or stops calling `getSessionTargetAppIdForDevice` — every
 * YAML-defined and TypeScript-scripted tool silently drops from the
 * dispatch gate on bound devices whose `--target` doesn't match the
 * daemon-wide setting. That's the regression PR #3463 closed and this test
 * is the guard.
 */
class TrailblazeMcpServerFindCurrentTargetTest {

  @get:Rule
  val tempFolder = TemporaryFolder()

  private val androidDevice = TrailblazeDeviceId(
    instanceId = "emulator-5554",
    trailblazeDevicePlatform = TrailblazeDevicePlatform.ANDROID,
  )

  private val perDeviceTarget = TestAppTarget(id = "perdeviceapp", displayName = "Per Device App")
  private val daemonWideTarget = TestAppTarget(id = "daemonwideapp", displayName = "Daemon Wide App")

  private fun newServer(bridge: TrailblazeMcpBridge): TrailblazeMcpServer = TrailblazeMcpServer(
    logsRepo = LogsRepo(logsDir = tempFolder.newFolder("logs"), watchFileSystem = false),
    mcpBridge = bridge,
    trailsDirProvider = { tempFolder.newFolder("trails") },
    targetTestAppProvider = { TrailblazeHostAppTarget.DefaultTrailblazeHostAppTarget },
    llmModelListsProvider = { emptySet() },
  )

  @Test
  fun `per-device override wins over daemon-wide when deviceId is supplied`() {
    val bridge = TargetResolutionBridge(
      availableTargets = setOf(perDeviceTarget, daemonWideTarget),
      daemonWideTargetId = daemonWideTarget.id,
      sessionTargetsByDevice = mapOf(androidDevice to perDeviceTarget.id),
    )
    val server = newServer(bridge)

    val resolved = server.findCurrentTarget(androidDevice)

    assertEquals(
      perDeviceTarget.id,
      resolved?.id,
      "findCurrentTarget must prefer the per-device override (set via --target / " +
        "setSessionTargetForBoundDevice) so YAML / TypeScript tools defined on " +
        "the per-device target's trailmap reach the dispatch gate.",
    )
  }

  @Test
  fun `daemon-wide is returned when deviceId is supplied but has no per-device override`() {
    val bridge = TargetResolutionBridge(
      availableTargets = setOf(daemonWideTarget),
      daemonWideTargetId = daemonWideTarget.id,
      sessionTargetsByDevice = emptyMap(),
    )
    val server = newServer(bridge)

    val resolved = server.findCurrentTarget(androidDevice)

    assertEquals(daemonWideTarget.id, resolved?.id)
  }

  @Test
  fun `daemon-wide is returned when deviceId is null`() {
    val bridge = TargetResolutionBridge(
      availableTargets = setOf(daemonWideTarget),
      daemonWideTargetId = daemonWideTarget.id,
      sessionTargetsByDevice = mapOf(androidDevice to perDeviceTarget.id),
    )
    val server = newServer(bridge)

    val resolved = server.findCurrentTarget(deviceId = null)

    assertEquals(
      daemonWideTarget.id,
      resolved?.id,
      "When the caller has no device context, the per-device override on a " +
        "different device must NOT leak through — fall back to daemon-wide.",
    )
  }

  @Test
  fun `null is returned when neither per-device nor daemon-wide is set`() {
    val bridge = TargetResolutionBridge(
      availableTargets = setOf(daemonWideTarget),
      daemonWideTargetId = null,
      sessionTargetsByDevice = emptyMap(),
    )
    val server = newServer(bridge)

    assertNull(server.findCurrentTarget(androidDevice))
  }

  @Test
  fun `null is returned when the resolved id does not match a registered target`() {
    val bridge = TargetResolutionBridge(
      availableTargets = setOf(daemonWideTarget),
      daemonWideTargetId = "ghost-target", // resolves to nothing in availableTargets
      sessionTargetsByDevice = emptyMap(),
    )
    val server = newServer(bridge)

    assertNull(
      server.findCurrentTarget(androidDevice),
      "An unresolvable target id (e.g. daemon-wide pointing at a target that " +
        "has since been unregistered) must produce null, not a half-built result.",
    )
  }

  // ── Test scaffolding ──────────────────────────────────────────────────────

  /** Minimal app-target stub with no custom tools. */
  private class TestAppTarget(id: String, displayName: String) :
    TrailblazeHostAppTarget(id, displayName) {
    override fun getPossibleAppIdsForPlatform(
      platform: TrailblazeDevicePlatform,
    ): List<String>? = null

    override fun internalGetCustomToolsForDriver(
      driverType: TrailblazeDriverType,
    ): Set<KClass<out TrailblazeTool>> = emptySet()
  }

  /**
   * Bridge that models the production target-resolution chain — per-device
   * override → daemon-wide — and lets the test pin each input independently
   * so the resolution chain is exercised through [TrailblazeMcpServer.findCurrentTarget]
   * rather than re-implemented inline.
   */
  private class TargetResolutionBridge(
    private val availableTargets: Set<TrailblazeHostAppTarget>,
    private val daemonWideTargetId: String?,
    private val sessionTargetsByDevice: Map<TrailblazeDeviceId, String>,
  ) : TrailblazeMcpBridge {
    override fun getAvailableAppTargets(): Set<TrailblazeHostAppTarget> = availableTargets
    override fun getCurrentAppTargetId(): String? = daemonWideTargetId
    override fun getSessionTargetAppIdForDevice(deviceId: TrailblazeDeviceId): String? =
      sessionTargetsByDevice[deviceId] ?: daemonWideTargetId

    // ── Unused — defaults / no-ops just to satisfy the interface ────────────
    override suspend fun selectDevice(trailblazeDeviceId: TrailblazeDeviceId): TrailblazeConnectedDeviceSummary =
      error("not used in this test")
    override suspend fun getAvailableDevices(): Set<TrailblazeConnectedDeviceSummary> = emptySet()
    override suspend fun executeTrailblazeTool(
      tool: TrailblazeTool,
      blocking: Boolean,
      traceId: TraceId?,
    ): String = "[OK]"
    override suspend fun getInstalledAppIds(): Set<String> = emptySet()
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
  }
}
