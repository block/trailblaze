package xyz.block.trailblaze.ui

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.datetime.Clock
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
 * What an interactive session reads as when a capture step fails while its device is released.
 * The failure says the session's captured evidence may be incomplete, not that the work done in the
 * session failed, so the session keeps its outcome and carries the failure as a warning.
 */
class SessionReleaseCaptureFailureTest {

  private val tempDir: File = Files.createTempDirectory("session-release-capture-failure").toFile()

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

  private fun startInteractiveSession(): SessionId {
    val sessionId = deviceManager.getOrCreateSessionResolution(deviceId, sessionIdPrefix = "yaml").sessionId
    logsRepo.saveLogToDisk(
      TrailblazeLog.TrailblazeSessionStatusChangeLog(
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
        timestamp = Clock.System.now(),
      ),
    )
    return sessionId
  }

  @Test
  fun `a capture that fails at release leaves the session succeeded, with the failure as its warning`() {
    val sessionId = startInteractiveSession()
    val failingCapture = HostSessionFinalizerRegistry.register { finalizing ->
      if (finalizing == sessionId) {
        error("Network capture for 'com.example' ended without evidence")
      }
    }

    // Still raised to the caller, which reports it on the stop.
    try {
      assertFailsWith<IllegalStateException> { deviceManager.endSessionForDevice(deviceId) }
    } finally {
      failingCapture.close()
    }

    val status = assertIs<SessionStatus.Ended.Succeeded>(logsRepo.getSessionInfoDirect(sessionId)!!.latestStatus)
    // The capture that actually failed, not just the barrier's count of failures.
    assertContains(assertNotNull(status.captureWarning), "ended without evidence")
  }

  @Test
  fun `a clean release carries no warning`() {
    val sessionId = startInteractiveSession()

    assertEquals(sessionId, deviceManager.endSessionForDevice(deviceId))

    val status = assertIs<SessionStatus.Ended.Succeeded>(logsRepo.getSessionInfoDirect(sessionId)!!.latestStatus)
    assertNull(status.captureWarning)
  }
}
