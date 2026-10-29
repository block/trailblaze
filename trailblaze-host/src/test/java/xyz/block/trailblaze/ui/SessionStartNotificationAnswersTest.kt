package xyz.block.trailblaze.ui

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import xyz.block.trailblaze.capture.CaptureSession
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.host.axe.NotificationAlertAnswers
import xyz.block.trailblaze.host.capture.SessionCaptureCoordinator
import xyz.block.trailblaze.host.driver.HostDriverDescriptorRegistry
import xyz.block.trailblaze.mcp.BoundDeviceRosterMember
import xyz.block.trailblaze.model.TrailblazeHostAppTarget
import xyz.block.trailblaze.report.utils.LogsRepo
import xyz.block.trailblaze.ui.models.TrailblazeServerState.SavedTrailblazeAppConfig

/**
 * An iOS notifications answer belongs to the session that armed it, so a simulator forgets its
 * answers when the device manager moves it onto another session: opening one, or joining one
 * through its cast.
 */
class SessionStartNotificationAnswersTest {

  private val tempDir: File = Files.createTempDirectory("session-start-notification-answers").toFile()

  @AfterTest
  fun tearDown() {
    tempDir.deleteRecursively()
  }

  private val udid = "session-start-answers-sim"
  private val deviceId = TrailblazeDeviceId(udid, TrailblazeDevicePlatform.IOS)

  private val logsRepo = LogsRepo(logsDir = File(tempDir, "logs").also { it.mkdirs() }, watchFileSystem = false)

  private val deviceManager = TrailblazeDeviceManager(
    logsRepo = logsRepo,
    settingsRepo = TrailblazeSettingsRepo(
      settingsFile = File(tempDir, "settings.json"),
      initialConfig = SavedTrailblazeAppConfig(selectedTrailblazeDriverTypes = emptyMap()),
      defaultHostAppTarget = TrailblazeHostAppTarget.DefaultTrailblazeHostAppTarget,
      allTargetApps = { emptySet() },
      supportedDriverTypes = emptySet(),
    ),
    defaultHostAppTarget = TrailblazeHostAppTarget.DefaultTrailblazeHostAppTarget,
    currentTrailblazeLlmModelProvider = { error("LLM not available in tests") },
    initialAppTargets = emptySet(),
    runYamlLambda = { error("YAML runner not available in tests") },
    installedAppIdsProviderBlocking = { emptySet() },
    appVersionInfoProviderBlocking = { _, _ -> null },
    onDeviceInstrumentationArgsProvider = { emptyMap() },
    trailblazeAnalytics = TrailblazeAnalytics.NoOp,
    hostDriverDescriptors = HostDriverDescriptorRegistry.EMPTY,
    // No capture streams: nothing here may spawn a recorder.
    sessionCaptureCoordinator = SessionCaptureCoordinator(
      logsRepo = logsRepo,
      captureSessionFactory = { options, _ -> CaptureSession(emptyList(), options) },
    ),
  )

  private val answers = NotificationAlertAnswers.forSimulator(udid)

  private val companionUdid = "session-start-answers-companion"
  private val companionId = TrailblazeDeviceId(companionUdid, TrailblazeDevicePlatform.IOS)
  private val companionAnswers = NotificationAlertAnswers.forSimulator(companionUdid)

  private fun member(name: String, device: TrailblazeDeviceId) = BoundDeviceRosterMember(name, device, targetId = null)

  @Test
  fun `a new session forgets the answer an earlier session armed`() {
    deviceManager.getOrCreateSessionResolution(deviceId, sessionIdPrefix = "session")
    answers.arm("com.example.app", "Shop", "allow")
    deviceManager.endSessionForDevice(deviceId)

    deviceManager.getOrCreateSessionResolution(deviceId, sessionIdPrefix = "session")

    assertTrue(answers.isEmpty(), "the earlier session's Allow would answer this session's alert")
  }

  @Test
  fun `a call joining the running session keeps its answer`() {
    deviceManager.getOrCreateSessionResolution(deviceId, sessionIdPrefix = "session")
    answers.arm("com.example.app", "Shop", "allow")

    deviceManager.getOrCreateSessionResolution(deviceId, sessionIdPrefix = "tool")

    assertFalse(answers.isEmpty())
  }

  @Test
  fun `a simulator attached to a running cast session forgets its earlier answer`() {
    deviceManager.getOrCreateSessionResolution(deviceId, sessionIdPrefix = "session")
    companionAnswers.arm("com.example.app", "Shop", "allow")

    deviceManager.setDeviceRoster("mcp-1", listOf(member("seller", deviceId), member("buyer", companionId)))

    assertTrue(companionAnswers.isEmpty(), "the companion's old Allow would answer the cast session's alert")
  }

  @Test
  fun `a cast member running its own session keeps its answer when the cast opens a session`() {
    deviceManager.getOrCreateSessionResolution(companionId, sessionIdPrefix = "session")
    companionAnswers.arm("com.example.app", "Shop", "allow")
    deviceManager.getOrCreateSessionResolution(deviceId, sessionIdPrefix = "session")
    deviceManager.setDeviceRoster("mcp-1", listOf(member("seller", deviceId), member("buyer", companionId)))

    deviceManager.getOrCreateSessionResolution(deviceId, sessionIdPrefix = "session", forceNewSession = true)

    assertFalse(companionAnswers.isEmpty(), "the companion stayed on its own session, so its answer is still that session's")
  }
}
