package xyz.block.trailblaze.mcp

import java.io.File
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.reflect.KClass
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.host.driver.HostDriverDescriptorRegistry
import xyz.block.trailblaze.host.lease.DeviceLeaseClient
import xyz.block.trailblaze.host.lease.DeviceLeaseRegistry
import xyz.block.trailblaze.host.lease.LeasableDevice
import xyz.block.trailblaze.host.lease.Lease
import xyz.block.trailblaze.host.lease.LeaseRequest
import xyz.block.trailblaze.host.lease.LeaseResult
import xyz.block.trailblaze.host.lease.RemoteDeviceCall
import xyz.block.trailblaze.host.lease.RemoteDevicePools
import xyz.block.trailblaze.host.lease.RemoteDeviceReply
import xyz.block.trailblaze.host.lease.RemoteToolResult
import xyz.block.trailblaze.llm.TrailblazeLlmModel
import xyz.block.trailblaze.llm.TrailblazeLlmProvider
import xyz.block.trailblaze.model.TrailblazeHostAppTarget
import xyz.block.trailblaze.report.utils.LogsRepo
import xyz.block.trailblaze.toolcalls.TrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult
import xyz.block.trailblaze.toolcalls.commands.ExecTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.InputTextTrailblazeTool
import xyz.block.trailblaze.ui.TrailblazeAnalytics
import xyz.block.trailblaze.ui.TrailblazeDeviceManager
import xyz.block.trailblaze.ui.TrailblazeSettingsRepo
import xyz.block.trailblaze.ui.models.TrailblazeServerState.SavedTrailblazeAppConfig

/** CLI and MCP tool calls on a browser borrowed from another daemon, through a real bridge. */
class BorrowedDeviceBridgeTest {

  private val tempDir: File = Files.createTempDirectory("borrowed-device-bridge").toFile()

  private val target = object : TrailblazeHostAppTarget(id = "example", displayName = "Example") {
    override fun getPossibleAppIdsForPlatform(platform: TrailblazeDevicePlatform): List<String> =
      listOf("com.example.app")

    override fun internalGetCustomToolsForDriver(
      driverType: TrailblazeDriverType,
    ): Set<KClass<out TrailblazeTool>> = emptySet()
  }

  /** A lender with one browser, answering as its endpoint does. */
  private inner class FakeLender : DeviceLeaseClient(POOL_URL) {
    val registry = DeviceLeaseRegistry()
    val attached = listOf(LeasableDevice("web/playwright-native", TrailblazeDriverType.PLAYWRIGHT_NATIVE))
    val calls = CopyOnWriteArrayList<RemoteDeviceCall>()

    override fun devices() = registry.status(attached)

    override fun lease(request: LeaseRequest): LeaseOutcome =
      when (val result = registry.lease(attached, request)) {
        is LeaseResult.Granted -> LeaseOutcome.Granted(result.lease)
        is LeaseResult.Busy -> LeaseOutcome.Busy(result.reason, result.holder)
      }

    override fun renew(lease: Lease, busy: Boolean) = RenewOutcome.RENEWED

    override fun call(lease: Lease, request: RemoteDeviceCall): RemoteDeviceReply {
      calls += request
      return when (request.op) {
        RemoteDeviceCall.Op.RUN_TOOL -> RemoteDeviceReply(toolResult = RemoteToolResult(success = true, message = "ran"))
        RemoteDeviceCall.Op.INSTALLED_APPS -> RemoteDeviceReply(installedAppIds = setOf("com.example.app"))
        else -> RemoteDeviceReply()
      }
    }

    override fun release(lease: Lease) {
      registry.release(lease.leaseId, lease.secret)?.forEach { registry.finishReturn(it.id) }
    }
  }

  private val lender = FakeLender()

  private val pools = RemoteDevicePools(
    poolUrls = { listOf(POOL_URL) },
    holder = "me@laptop",
    clientFor = { lender },
    repeater = { _, _, _ -> },
  )

  private val logsRepo = LogsRepo(logsDir = File(tempDir, "logs").also { it.mkdirs() }, watchFileSystem = false)

  private val deviceManager = TrailblazeDeviceManager(
    logsRepo = logsRepo,
    settingsRepo = TrailblazeSettingsRepo(
      settingsFile = File(tempDir, "settings.json"),
      initialConfig = SavedTrailblazeAppConfig(
        selectedTrailblazeDriverTypes = mapOf(TrailblazeDevicePlatform.WEB to TrailblazeDriverType.PLAYWRIGHT_NATIVE),
      ),
      defaultHostAppTarget = TrailblazeHostAppTarget.DefaultTrailblazeHostAppTarget,
      allTargetApps = { setOf(target) },
      supportedDriverTypes = emptySet(),
    ),
    defaultHostAppTarget = TrailblazeHostAppTarget.DefaultTrailblazeHostAppTarget,
    currentTrailblazeLlmModelProvider = {
      TrailblazeLlmModel(
        trailblazeLlmProvider = TrailblazeLlmProvider(id = "test", display = "Test"),
        modelId = "test-model",
        inputCostPerOneMillionTokens = 0.0,
        outputCostPerOneMillionTokens = 0.0,
        contextLength = 1000,
        maxOutputTokens = 1000,
        capabilityIds = emptyList(),
      )
    },
    initialAppTargets = setOf(target),
    runYamlLambda = { error("YAML runner not available in tests") },
    installedAppIdsProviderBlocking = { error("a borrowed device's apps are listed by its lender") },
    onDeviceInstrumentationArgsProvider = { emptyMap() },
    trailblazeAnalytics = TrailblazeAnalytics.NoOp,
    hostDriverDescriptors = HostDriverDescriptorRegistry.EMPTY,
    remoteDevicePools = pools,
  )

  private val bridge = TrailblazeMcpBridgeImpl(
    trailblazeDeviceManager = deviceManager,
    logsRepo = logsRepo,
    dynamicLlmClientProvider = { error("No LLM in tests") },
  )

  private val browser = pools.remoteDevices().single().trailblazeDeviceId

  @AfterTest
  fun tearDown() {
    pools.returnAll()
    tempDir.deleteRecursively()
  }

  private fun selectBrowserWithTarget() {
    assertEquals(TrailblazeDevicePlatform.WEB, browser.trailblazeDevicePlatform)
    bridge.selectedDeviceId = browser
    bridge.setSessionTargetForDevice(browser, target.id)
  }

  @Test
  fun `a tool call on a borrowed browser runs on its lender under this session's target`() = runBlocking {
    selectBrowserWithTarget()
    val result = bridge.executeTrailblazeToolForResult(InputTextTrailblazeTool(text = "hi"), blocking = true, traceId = null)
    assertTrue(result is TrailblazeToolResult.Success, "$result")
    val sent = lender.calls.single { it.op == RemoteDeviceCall.Op.RUN_TOOL }
    assertEquals("example", sent.target)
  }

  @Test
  fun `a scripted tool on a borrowed browser runs here, with its apps listed by the lender`() = runBlocking {
    selectBrowserWithTarget()
    // This machine has no browser for it, so the local Playwright path would fail "not ready".
    val result = bridge.executeTrailblazeToolForResult(ExecTrailblazeTool(argv = listOf("printf", "hi")), blocking = true, traceId = null)
    assertTrue(result is TrailblazeToolResult.Success, "$result")
    assertTrue(lender.calls.none { it.op == RemoteDeviceCall.Op.RUN_TOOL }, "${lender.calls}")
    assertEquals(listOf(RemoteDeviceCall.Op.INSTALLED_APPS), lender.calls.map { it.op })
  }

  private companion object {
    const val POOL_URL = "http://pool:31900"
  }
}
