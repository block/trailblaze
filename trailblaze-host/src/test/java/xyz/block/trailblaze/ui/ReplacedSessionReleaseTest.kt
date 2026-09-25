package xyz.block.trailblaze.ui

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import xyz.block.trailblaze.capture.CaptureOptions
import xyz.block.trailblaze.capture.CaptureSession
import xyz.block.trailblaze.capture.CaptureStream
import xyz.block.trailblaze.capture.model.CaptureArtifact
import xyz.block.trailblaze.capture.model.CaptureType
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.host.capture.SessionCaptureCoordinator
import xyz.block.trailblaze.host.driver.HostDriverDescriptorRegistry
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.model.TrailblazeHostAppTarget
import xyz.block.trailblaze.report.utils.LogsRepo
import xyz.block.trailblaze.ui.composables.DefaultDeviceClassifierIconProvider
import xyz.block.trailblaze.ui.models.AppIconProvider
import xyz.block.trailblaze.ui.models.TrailblazeServerState.SavedTrailblazeAppConfig

/**
 * A session a new one takes a device over from is never ended, so unless it is released its
 * capture keeps sampling the device for the life of the daemon.
 */
class ReplacedSessionReleaseTest {

  private val tempDir: File = Files.createTempDirectory("replaced-session").toFile()

  @AfterTest
  fun tearDown() {
    tempDir.deleteRecursively()
  }

  private class RecordingStream : CaptureStream {
    override val type = CaptureType.MEMORY
    var started = false
    var stopped = false

    override fun start(sessionDir: File, deviceId: String, appId: String?) {
      started = true
    }

    override fun stop(options: CaptureOptions): CaptureArtifact? {
      stopped = true
      return null
    }
  }

  private val device = TrailblazeDeviceId("emulator-5554", TrailblazeDevicePlatform.ANDROID)

  private fun manager(stream: RecordingStream): TrailblazeDeviceManager {
    val logsRepo = LogsRepo(logsDir = File(tempDir, "logs").also { it.mkdirs() }, watchFileSystem = false)
    return TrailblazeDeviceManager(
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
      appIconProvider = AppIconProvider.DefaultAppIconProvider,
      deviceClassifierIconProvider = DefaultDeviceClassifierIconProvider,
      runYamlLambda = { error("YAML runner not available in tests") },
      installedAppIdsProviderBlocking = { emptySet() },
      appVersionInfoProviderBlocking = { _, _ -> null },
      onDeviceInstrumentationArgsProvider = { emptyMap() },
      trailblazeAnalytics = TrailblazeAnalytics.NoOp,
      hostDriverDescriptors = HostDriverDescriptorRegistry.EMPTY,
      sessionCaptureCoordinator = SessionCaptureCoordinator(
        logsRepo = logsRepo,
        captureSessionFactory = { options, _ -> CaptureSession(listOf(stream), options) },
      ),
    )
  }

  /** An interactive session on [device], with its capture running. */
  private fun interactiveSession(manager: TrailblazeDeviceManager, stream: RecordingStream): SessionId {
    val sessionId = manager.getOrCreateSessionResolution(device, sessionIdPrefix = "tool").sessionId
    assertTrue(stream.started, "precondition: the interactive session's capture is running")
    return sessionId
  }

  @Test
  fun `a session replaced on its device has its capture stopped`() {
    val stream = RecordingStream()
    val manager = manager(stream)
    val interactive = interactiveSession(manager, stream)

    manager.releaseReplacedSession(device, interactive, SessionId("run-session"))

    assertTrue(stream.stopped)
  }

  @Test
  fun `a run that continues the device's session leaves its capture running`() {
    val stream = RecordingStream()
    val manager = manager(stream)
    val interactive = interactiveSession(manager, stream)

    manager.releaseReplacedSession(device, interactive, interactive)

    assertFalse(stream.stopped)
  }

  @Test
  fun `a replaced session still active on another device keeps its capture`() {
    val stream = RecordingStream()
    val manager = manager(stream)
    val interactive = interactiveSession(manager, stream)
    val otherDevice = TrailblazeDeviceId("emulator-5556", TrailblazeDevicePlatform.ANDROID)
    manager.trackActiveSession(otherDevice, interactive)

    manager.releaseReplacedSession(device, interactive, SessionId("run-session"))

    assertFalse(stream.stopped)
  }

  /** Runs can share a device; the session the pointer named may be a sibling run still executing. */
  @Test
  fun `a replaced session is kept while another run is executing on the device`() {
    val stream = RecordingStream()
    val manager = manager(stream)
    val sibling = interactiveSession(manager, stream)
    val siblingRun = manager.beginRun(device)
    val thisRun = manager.beginRun(device)

    manager.releaseReplacedSession(device, sibling, SessionId("run-session"), thisRun)
    assertFalse(stream.stopped, "a sibling run is still executing on the device")

    manager.endRun(siblingRun)
    manager.releaseReplacedSession(device, sibling, SessionId("run-session"), thisRun)
    assertTrue(stream.stopped, "the caller's own run must not count as a sibling")
  }

  /** MCP and TrailRunner runs reserve their session up front, before the runner could look. */
  @Test
  fun `forcing a new session releases the session it replaced`() {
    val stream = RecordingStream()
    val manager = manager(stream)
    interactiveSession(manager, stream)

    manager.getOrCreateSessionResolution(device, forceNewSession = true, sessionIdPrefix = "yaml")

    assertTrue(stream.stopped)
  }

  @Test
  fun `forcing a new session while a run executes on the device keeps the replaced session`() {
    val stream = RecordingStream()
    val manager = manager(stream)
    interactiveSession(manager, stream)
    manager.beginRun(device)

    manager.getOrCreateSessionResolution(device, forceNewSession = true, sessionIdPrefix = "yaml")

    assertFalse(stream.stopped)
  }
}
