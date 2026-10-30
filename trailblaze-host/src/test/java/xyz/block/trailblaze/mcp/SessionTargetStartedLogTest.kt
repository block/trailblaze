package xyz.block.trailblaze.mcp

import java.io.File
import java.nio.file.Files
import kotlin.reflect.KClass
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.host.driver.HostDriverDescriptorRegistry
import xyz.block.trailblaze.llm.TrailblazeLlmModel
import xyz.block.trailblaze.llm.TrailblazeLlmProvider
import xyz.block.trailblaze.logs.client.TrailblazeLog
import xyz.block.trailblaze.logs.model.SessionStatus
import xyz.block.trailblaze.model.TrailblazeHostAppTarget
import xyz.block.trailblaze.report.utils.LogsRepo
import xyz.block.trailblaze.toolcalls.TrailblazeTool
import xyz.block.trailblaze.ui.TrailblazeAnalytics
import xyz.block.trailblaze.ui.TrailblazeDeviceManager
import xyz.block.trailblaze.ui.TrailblazeSettingsRepo
import xyz.block.trailblaze.ui.models.TrailblazeServerState.SavedTrailblazeAppConfig

/**
 * `trailblaze session start --target <id>` (or `TRAILBLAZE_TARGET` on a first command) creates the
 * session by setting its target. `session save` reads the device platform from that session's
 * Started log, so the session must have one.
 */
class SessionTargetStartedLogTest {

  private val tempDir: File = Files.createTempDirectory("session-target-started-log").toFile()

  @AfterTest
  fun tearDown() {
    tempDir.deleteRecursively()
  }

  private val target = object : TrailblazeHostAppTarget(id = "example", displayName = "Example") {
    override fun getPossibleAppIdsForPlatform(platform: TrailblazeDevicePlatform): List<String> =
      listOf("com.example.app")

    override fun internalGetCustomToolsForDriver(
      driverType: TrailblazeDriverType,
    ): Set<KClass<out TrailblazeTool>> = emptySet()
  }

  private val logsRepo = LogsRepo(logsDir = File(tempDir, "logs").also { it.mkdirs() }, watchFileSystem = false)

  private val deviceManager = TrailblazeDeviceManager(
    logsRepo = logsRepo,
    settingsRepo = TrailblazeSettingsRepo(
      settingsFile = File(tempDir, "settings.json"),
      initialConfig = SavedTrailblazeAppConfig(selectedTrailblazeDriverTypes = emptyMap()),
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
    installedAppIdsProviderBlocking = { emptySet() },
    appVersionInfoProviderBlocking = { _, _ -> null },
    onDeviceInstrumentationArgsProvider = { emptyMap() },
    trailblazeAnalytics = TrailblazeAnalytics.NoOp,
    hostDriverDescriptors = HostDriverDescriptorRegistry.EMPTY,
  )

  private val bridge = TrailblazeMcpBridgeImpl(
    trailblazeDeviceManager = deviceManager,
    logsRepo = logsRepo,
    dynamicLlmClientProvider = { error("No LLM in tests") },
  )

  private val device = TrailblazeDeviceId("emulator-5554", TrailblazeDevicePlatform.ANDROID)

  private fun startedLogs(): List<SessionStatus.Started> {
    val sessionId = checkNotNull(deviceManager.getCurrentSessionIdForDevice(device)) { "no session was created" }
    return logsRepo.getLogsForSession(sessionId)
      .filterIsInstance<TrailblazeLog.TrailblazeSessionStatusChangeLog>()
      .mapNotNull { it.sessionStatus as? SessionStatus.Started }
  }

  @Test
  fun `a session created by setting its target has a Started log with the device platform`() {
    assertEquals("Example", bridge.setSessionTargetForDevice(device, target.id))

    val started = startedLogs()
    assertEquals(1, started.size, "exactly one Started log for the new session")
    assertEquals(
      TrailblazeDevicePlatform.ANDROID,
      started.single().trailblazeDeviceInfo.trailblazeDeviceId.trailblazeDevicePlatform,
    )
  }

  @Test
  fun `setting the target on an existing session does not start it again`() {
    bridge.setSessionTargetForDevice(device, target.id)
    bridge.setSessionTargetForDevice(device, target.id)

    assertEquals(1, startedLogs().size)
  }
}
