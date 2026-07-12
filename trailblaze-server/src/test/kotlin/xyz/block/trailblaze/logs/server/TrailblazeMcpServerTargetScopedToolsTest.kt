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
import xyz.block.trailblaze.mcp.McpToolNames
import xyz.block.trailblaze.mcp.TrailblazeMcpBridge
import xyz.block.trailblaze.mcp.TrailblazeMcpSessionContext
import xyz.block.trailblaze.mcp.android.ondevice.rpc.GetScreenStateResponse
import xyz.block.trailblaze.mcp.models.McpSessionId
import xyz.block.trailblaze.mcp.newtools.DeviceManagerToolSet
import xyz.block.trailblaze.model.TrailblazeHostAppTarget
import xyz.block.trailblaze.report.utils.LogsRepo
import xyz.block.trailblaze.toolcalls.TrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolSetCatalog
import kotlin.reflect.KClass
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins the target-scoped MCP tool surface in
 * [TrailblazeMcpServer.resolveTargetScopedToolClasses] — the resolution that
 * replaced the FULL/MINIMAL `McpToolProfile` split. Every MCP session
 * advertises the CURRENT target's driver-filtered TrailblazeTools (mirroring
 * the inner-agent tools provider), not the whole catalog:
 *
 *   - no driver bound → no TrailblazeTools (device connect re-registers)
 *   - driver bound, no target → the catalog's always-enabled surface for
 *     that driver only
 *   - target bound → plus the target's custom tools, minus its
 *     `excluded_tools:` opt-outs
 *   - bridge-supplied driver replacement (WEB/Playwright-style) → replaces
 *     the catalog surface entirely
 *
 * A regression here silently changes what external MCP clients (Claude Code,
 * Cursor, Goose) see in tools/list.
 */
class TrailblazeMcpServerTargetScopedToolsTest {

  @get:Rule
  val tempFolder = TemporaryFolder()

  private val androidDriver = TrailblazeDriverType.ANDROID_ONDEVICE_ACCESSIBILITY

  private fun newServer(bridge: TrailblazeMcpBridge): TrailblazeMcpServer = TrailblazeMcpServer(
    logsRepo = LogsRepo(logsDir = tempFolder.newFolder("logs"), watchFileSystem = false),
    mcpBridge = bridge,
    trailsDirProvider = { tempFolder.newFolder("trails") },
    targetTestAppProvider = { TrailblazeHostAppTarget.DefaultTrailblazeHostAppTarget },
    llmModelListsProvider = { emptySet() },
  )

  private fun newSessionContext(): TrailblazeMcpSessionContext = TrailblazeMcpSessionContext(
    mcpServerSession = null,
    mcpSessionId = McpSessionId("test-session"),
  )

  @Test
  fun `no driver bound resolves to an empty TrailblazeTool surface`() {
    val bridge = ToolSurfaceBridge(driverType = null)
    val server = newServer(bridge)

    assertEquals(
      emptySet(),
      server.resolveTargetScopedToolClasses(newSessionContext()),
      "With no device/driver bound there is nothing the tools could run against — " +
        "the surface must stay empty until device-connect re-registers.",
    )
  }

  @Test
  fun `driver bound with no target resolves the catalog always-enabled surface for that driver`() {
    val bridge = ToolSurfaceBridge(driverType = androidDriver)
    val server = newServer(bridge)

    val resolved = server.resolveTargetScopedToolClasses(newSessionContext())

    val expected = TrailblazeToolSetCatalog.resolveForDriver(androidDriver, emptyList()).toolClasses
    assertTrue(expected.isNotEmpty(), "Catalog sanity: always-enabled Android surface must be non-empty")
    assertEquals(
      expected,
      resolved,
      "With a driver but no target, only the catalog's always-enabled toolsets for " +
        "that driver are advertised — NOT the full cross-target catalog.",
    )
  }

  @Test
  fun `target custom tools are added and its excluded tools removed`() {
    val target = TestAppTarget(
      id = "sampleapp",
      customTools = setOf(FakeSignInTool::class, FakeSeedDataTool::class),
      excludedTools = setOf(FakeSeedDataTool::class),
    )
    val bridge = ToolSurfaceBridge(
      driverType = androidDriver,
      availableTargets = setOf(target),
      daemonWideTargetId = target.id,
    )
    val server = newServer(bridge)

    val resolved = server.resolveTargetScopedToolClasses(newSessionContext())

    assertTrue(
      FakeSignInTool::class in resolved,
      "The active target's custom tools must be part of the advertised MCP surface.",
    )
    assertFalse(
      FakeSeedDataTool::class in resolved,
      "The active target's excluded_tools opt-outs must be removed from the surface.",
    )
  }

  @Test
  fun `bridge-supplied driver replacement tools replace the catalog surface`() {
    val target = TestAppTarget(
      id = "sampleapp",
      customTools = setOf(FakeSignInTool::class),
    )
    val bridge = ToolSurfaceBridge(
      driverType = androidDriver,
      availableTargets = setOf(target),
      daemonWideTargetId = target.id,
      innerAgentBuiltInToolClasses = setOf(FakeDriverNativeTool::class),
    )
    val server = newServer(bridge)

    val resolved = server.resolveTargetScopedToolClasses(newSessionContext())

    assertEquals(
      setOf(FakeSignInTool::class, FakeDriverNativeTool::class),
      resolved,
      "When the bridge supplies driver-native replacement tools (the WEB/Playwright " +
        "shape), they replace the catalog surface entirely; the target's custom " +
        "tools still ride along.",
    )
  }

  @Test
  fun `with no daemon LLM, step and ask are advertised but runPrompt is withheld`() {
    // The test server has no llmClientProvider/llmModelProvider → llmConfigured == false,
    // the default external-MCP-client shape (Claude Code / Cursor / Codex bring their own
    // model). step() (direct-tools mode) and ask() (raw-screen-state fallback) both work
    // with no daemon LLM and MUST stay advertised; runPrompt runs the daemon-side agent
    // loop over prompts and MUST be withheld. Guards the regression where ask was gated
    // alongside runPrompt and vanished for LLM-less clients.
    val server = newServer(ToolSurfaceBridge(driverType = null))
    val mcpServer = server.configureMcpServer()

    server.registerTools(mcpServer, McpSessionId("test-session"), newSessionContext())

    val toolNames = mcpServer.tools.keys
    assertTrue(
      McpToolNames.TOOL_STEP in toolNames,
      "step must be advertised without a daemon LLM. Registered: $toolNames",
    )
    assertTrue(
      McpToolNames.TOOL_ASK in toolNames,
      "ask must be advertised without a daemon LLM — it falls back to raw screen state " +
        "for external agents that bring their own reasoning. Registered: $toolNames",
    )
    assertTrue(
      DeviceManagerToolSet.TOOL_END_SESSION in toolNames,
      "sanity: the device-manager toolset registered (endSession is one of its tools), so " +
        "runPrompt's absence below is the LLM gate — not a wholesale registration failure. " +
        "Registered: $toolNames",
    )
    assertFalse(
      DeviceManagerToolSet.TOOL_RUN_PROMPT in toolNames,
      "runPrompt must be withheld without a daemon LLM — it runs the daemon-side agent " +
        "loop and can't work. Registered: $toolNames",
    )
  }

  // ── Test scaffolding ──────────────────────────────────────────────────────

  private class FakeSignInTool : TrailblazeTool
  private class FakeSeedDataTool : TrailblazeTool
  private class FakeDriverNativeTool : TrailblazeTool

  private class TestAppTarget(
    id: String,
    private val customTools: Set<KClass<out TrailblazeTool>> = emptySet(),
    private val excludedTools: Set<KClass<out TrailblazeTool>> = emptySet(),
  ) : TrailblazeHostAppTarget(id, id) {
    override fun getPossibleAppIdsForPlatform(
      platform: TrailblazeDevicePlatform,
    ): List<String>? = null

    override fun internalGetCustomToolsForDriver(
      driverType: TrailblazeDriverType,
    ): Set<KClass<out TrailblazeTool>> = customTools

    override fun getExcludedToolsForDriver(
      driverType: TrailblazeDriverType,
    ): Set<KClass<out TrailblazeTool>> = excludedTools
  }

  /** Bridge stub exposing just the inputs the target-scoped resolution reads. */
  private class ToolSurfaceBridge(
    private val driverType: TrailblazeDriverType?,
    private val availableTargets: Set<TrailblazeHostAppTarget> = emptySet(),
    private val daemonWideTargetId: String? = null,
    private val innerAgentBuiltInToolClasses: Set<KClass<out TrailblazeTool>> = emptySet(),
  ) : TrailblazeMcpBridge {
    override fun getDriverType(): TrailblazeDriverType? = driverType
    override fun getAvailableAppTargets(): Set<TrailblazeHostAppTarget> = availableTargets
    override fun getCurrentAppTargetId(): String? = daemonWideTargetId
    override fun getSessionTargetAppIdForDevice(deviceId: TrailblazeDeviceId): String? =
      daemonWideTargetId
    override fun getInnerAgentBuiltInToolClasses(): Set<KClass<out TrailblazeTool>> =
      innerAgentBuiltInToolClasses

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
