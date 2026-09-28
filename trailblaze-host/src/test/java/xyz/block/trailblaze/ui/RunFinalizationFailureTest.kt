package xyz.block.trailblaze.ui

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.datetime.Clock
import xyz.block.trailblaze.capture.CaptureSession
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDeviceInfo
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.devices.TrailblazeDriverType
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
 * What a run reads as when finalizing its session fails after the trail passed. The run writes its
 * end before it finalizes, so the failure has to replace that end for the report and the daemon's
 * CLI verdict to see it — while an error from reconnecting after a passed run still must not.
 */
class RunFinalizationFailureTest {

  private val tempDir: File = Files.createTempDirectory("run-finalization-failure").toFile()

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
    sessionCaptureCoordinator = SessionCaptureCoordinator(
      logsRepo = logsRepo,
      captureSessionFactory = { options, _ -> CaptureSession(emptyList(), options) },
    ),
  )

  /** The shape the finalization barrier throws: a count of failures, with the real one as cause. */
  private val noEvidence = IllegalStateException(
    "1 host session finalizer(s) failed",
    IllegalStateException("Network capture for 'com.example' ended without evidence"),
  )

  private val sessionId = SessionId("2026_09_25_12_00_00_yaml_1")

  private fun writeStatus(status: SessionStatus) {
    logsRepo.saveLogToDisk(
      TrailblazeLog.TrailblazeSessionStatusChangeLog(
        sessionStatus = status,
        session = sessionId,
        timestamp = Clock.System.now(),
      ),
    )
  }

  private fun runThatEnded(end: SessionStatus.Ended) {
    writeStatus(
      SessionStatus.Started(
        trailConfig = null,
        trailFilePath = null,
        hasRecordedSteps = true,
        testMethodName = "login",
        testClassName = "trails",
        trailblazeDeviceInfo = TrailblazeDeviceInfo(
          trailblazeDeviceId = deviceId,
          trailblazeDriverType = TrailblazeDriverType.ANDROID_ONDEVICE_ACCESSIBILITY,
          widthPixels = 1080,
          heightPixels = 2400,
          classifiers = emptyList(),
        ),
        trailblazeDeviceId = deviceId,
      ),
    )
    writeStatus(end)
  }

  private fun diskStatus() = logsRepo.getSessionInfoDirect(sessionId)?.latestStatus

  private fun daemonVerdict(latchSuccess: Boolean, latchError: String?, finalizationError: String? = null) =
    reconcileRunOutcome(
      latchSuccess = latchSuccess,
      latchError = latchError,
      diskStatus = diskStatus(),
      sessionDescription = sessionId.value,
      finalizationError = finalizationError,
    )

  @Test
  fun `a passed run whose finalization fails ends failed, and the daemon fails it`() {
    runThatEnded(SessionStatus.Ended.Succeeded(durationMs = 4_200))

    val message = deviceManager.failSucceededSessionOnFinalization(sessionId, noEvidence)

    val status = assertIs<SessionStatus.Ended.Failed>(diskStatus())
    // The capture that actually failed, not just the barrier's count of failures.
    assertContains(message, "ended without evidence")
    assertEquals(message, status.exceptionMessage)
    assertEquals(4_200, status.durationMs)
    // Read by the daemon as a plain failed result, the failed disk status alone fails the run.
    val verdict = daemonVerdict(latchSuccess = false, latchError = message)
    assertFalse(verdict.success)
    assertEquals(message, verdict.error)
  }

  @Test
  fun `a finalization failure before the end lands fails that end, and the run`() {
    // A device's end can still be on its way when finalization fails.
    val message = deviceManager.failSucceededSessionOnFinalization(sessionId, noEvidence)
    runThatEnded(SessionStatus.Ended.Succeeded(durationMs = 4_200))

    assertEquals(message, assertIs<SessionStatus.Ended.Failed>(diskStatus()).exceptionMessage)
    val verdict = daemonVerdict(latchSuccess = false, latchError = message, finalizationError = message)
    assertFalse(verdict.success)
    assertEquals(message, verdict.error)
  }

  @Test
  fun `a connection error after a passed run still passes`() {
    runThatEnded(SessionStatus.Ended.Succeeded(durationMs = 4_200))

    val verdict = daemonVerdict(latchSuccess = false, latchError = "Connection refused: instrumentation server exited")

    assertTrue(verdict.success)
    assertNull(verdict.error)
  }

  @Test
  fun `a self-healed pass keeps its self-heal when finalization fails it`() {
    runThatEnded(SessionStatus.Ended.SucceededWithSelfHeal(durationMs = 4_200))

    deviceManager.failSucceededSessionOnFinalization(sessionId, noEvidence)

    val status = assertIs<SessionStatus.Ended.FailedWithSelfHeal>(diskStatus())
    assertContains(status.exceptionMessage.orEmpty(), "ended without evidence")
  }

  @Test
  fun `a run that already failed keeps its own failure`() {
    runThatEnded(SessionStatus.Ended.Failed(durationMs = 4_200, exceptionMessage = "Login button not found"))

    deviceManager.failSucceededSessionOnFinalization(sessionId, noEvidence)

    assertEquals("Login button not found", assertIs<SessionStatus.Ended.Failed>(diskStatus()).exceptionMessage)
  }
}
