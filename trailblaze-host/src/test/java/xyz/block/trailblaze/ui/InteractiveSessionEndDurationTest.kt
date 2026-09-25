package xyz.block.trailblaze.ui

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.microseconds
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import xyz.block.trailblaze.capture.CaptureSession
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDeviceInfo
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.host.capture.HostSessionFinalizerRegistry
import xyz.block.trailblaze.host.capture.SessionCaptureCoordinator
import xyz.block.trailblaze.host.driver.HostDriverDescriptorRegistry
import xyz.block.trailblaze.logs.client.TrailblazeLog
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.logs.model.SessionStatus
import xyz.block.trailblaze.model.TrailblazeHostAppTarget
import xyz.block.trailblaze.report.utils.LogsRepo
import xyz.block.trailblaze.ui.composables.DefaultDeviceClassifierIconProvider
import xyz.block.trailblaze.ui.models.AppIconProvider
import xyz.block.trailblaze.ui.models.TrailblazeServerState.SavedTrailblazeAppConfig

/**
 * The duration an interactive session is ended with. Such a session is ended by releasing its
 * device (`session stop`, another command claiming the device), commands after it was minted, and
 * that end used to carry a duration of 0 — which every surface then either printed as "0.0s" or
 * replaced with start-to-release, idle time included.
 */
class InteractiveSessionEndDurationTest {

  private val tempDir: File = Files.createTempDirectory("interactive-session-duration").toFile()

  @AfterTest
  fun tearDown() {
    tempDir.deleteRecursively()
  }

  private val deviceId = TrailblazeDeviceId("emulator-5554", TrailblazeDevicePlatform.ANDROID)

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
    appIconProvider = AppIconProvider.DefaultAppIconProvider,
    deviceClassifierIconProvider = DefaultDeviceClassifierIconProvider,
    runYamlLambda = { error("YAML runner not available in tests") },
    installedAppIdsProviderBlocking = { emptySet() },
    appVersionInfoProviderBlocking = { _, _ -> null },
    onDeviceInstrumentationArgsProvider = { emptyMap() },
    trailblazeAnalytics = TrailblazeAnalytics.NoOp,
    hostDriverDescriptors = HostDriverDescriptorRegistry.EMPTY,
    // No capture streams: nothing here may spawn screenrecord or adb.
    sessionCaptureCoordinator = SessionCaptureCoordinator(
      logsRepo = logsRepo,
      captureSessionFactory = { options, _ -> CaptureSession(emptyList(), options) },
    ),
  )

  /** Writes [log] and back-dates it to [writtenAt], as if the command that produced it ran then. */
  private fun writeLogAt(log: TrailblazeLog, writtenAt: Instant) {
    logsRepo.saveLogToDisk(log).setLastModified(writtenAt.toEpochMilliseconds())
  }

  private fun startedLog(sessionId: SessionId, at: Instant) = TrailblazeLog.TrailblazeSessionStatusChangeLog(
    sessionStatus = SessionStatus.Started(
      trailConfig = null,
      trailFilePath = null,
      hasRecordedSteps = false,
      testMethodName = "Tap the login button",
      testClassName = "MCP",
      trailblazeDeviceInfo = TrailblazeDeviceInfo(
        trailblazeDeviceId = deviceId,
        trailblazeDriverType = TrailblazeDriverType.ANDROID_ONDEVICE_INSTRUMENTATION,
        widthPixels = 1080,
        heightPixels = 2400,
        classifiers = emptyList(),
      ),
      trailblazeDeviceId = deviceId,
    ),
    session = sessionId,
    timestamp = at,
  )

  @Test
  fun `a released interactive session lasts from its first log to its last, not until the release`() {
    val sessionId = deviceManager.getOrCreateSessionResolution(deviceId, sessionIdPrefix = "yaml").sessionId
    // Ten minutes ago the session started; its last command finished 42 seconds later, and the
    // device then sat idle until the release below.
    val startedAt = Instant.fromEpochMilliseconds(Clock.System.now().toEpochMilliseconds() - 10 * 60_000L)
    val lastCommandAt = Instant.fromEpochMilliseconds(startedAt.toEpochMilliseconds() + 42_000L)
    writeLogAt(startedLog(sessionId, startedAt), startedAt)
    writeLogAt(
      TrailblazeLog.TrailblazeProgressLog(
        eventType = "Test",
        description = "the session's last command",
        session = sessionId,
        timestamp = lastCommandAt,
      ),
      lastCommandAt,
    )

    deviceManager.endSessionForDevice(deviceId)

    val info = logsRepo.getSessionInfoDirect(sessionId)!!
    assertIs<SessionStatus.Ended.Succeeded>(info.latestStatus)
    assertEquals(42_000L, info.durationMs)
    // Readers that derive the start as end minus duration (the analytics window) must land on the
    // session's first log, so the end is the last command, not the release.
    val endedAt = assertNotNull(info.endTimestamp)
    assertTrue(endedAt in lastCommandAt..lastCommandAt + 1.milliseconds, "ended at $endedAt")
  }

  @Test
  fun `a session released right after it started reads as ended, not still in progress`() {
    val sessionId = deviceManager.getOrCreateSessionResolution(deviceId, sessionIdPrefix = "yaml").sessionId
    // A log's timestamp is finer than its file's write time, which is truncated to the millisecond:
    // stamped 0.9ms into a millisecond, the file reads as written at its start.
    val writtenAt = Instant.fromEpochMilliseconds(Clock.System.now().toEpochMilliseconds())
    writeLogAt(startedLog(sessionId, writtenAt + 900.microseconds), writtenAt)

    deviceManager.endSessionForDevice(deviceId)

    assertIs<SessionStatus.Ended.Succeeded>(logsRepo.getSessionInfoDirect(sessionId)!!.latestStatus)
  }

  @Test
  fun `a log still queued when the device is released counts toward the session`() {
    val sessionId = deviceManager.getOrCreateSessionResolution(deviceId, sessionIdPrefix = "yaml").sessionId
    val startedAt = Instant.fromEpochMilliseconds(Clock.System.now().toEpochMilliseconds() - 10 * 60_000L)
    val queuedLogAt = Instant.fromEpochMilliseconds(startedAt.toEpochMilliseconds() + 50_000L)
    writeLogAt(startedLog(sessionId, startedAt), startedAt)
    // The iOS runner writes its action logs on a background scope and flushes them from a session
    // finalizer, so its last log reaches disk only during the release.
    val flush = HostSessionFinalizerRegistry.register { finalizing ->
      if (finalizing == sessionId) {
        writeLogAt(
          TrailblazeLog.TrailblazeProgressLog(
            eventType = "Test",
            description = "a log the runner had queued",
            session = sessionId,
            timestamp = queuedLogAt,
          ),
          queuedLogAt,
        )
      }
    }
    try {
      deviceManager.endSessionForDevice(deviceId)
    } finally {
      flush.close()
    }

    assertEquals(50_000L, logsRepo.getSessionInfoDirect(sessionId)!!.durationMs)
  }

  @Test
  fun `a session released before it wrote anything ends with no duration rather than failing`() {
    val sessionId = deviceManager.getOrCreateSessionResolution(deviceId, sessionIdPrefix = "yaml").sessionId

    assertEquals(sessionId, deviceManager.endSessionForDevice(deviceId))

    val info = logsRepo.getSessionInfoDirect(sessionId)!!
    assertIs<SessionStatus.Ended.Succeeded>(info.latestStatus)
    assertEquals(0L, info.durationMs)
  }
}
