package xyz.block.trailblaze.trailrunner

import io.ktor.http.HttpStatusCode
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.llm.TrailblazeLlmModel
import xyz.block.trailblaze.llm.TrailblazeLlmProvider
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.model.DesktopAppRunYamlParams
import xyz.block.trailblaze.model.TrailExecutionResult
import xyz.block.trailblaze.model.TrailblazeHostAppTarget
import xyz.block.trailblaze.report.utils.LogsRepo
import xyz.block.trailblaze.ui.TrailblazeAnalytics
import xyz.block.trailblaze.ui.TrailblazeDeviceManager
import xyz.block.trailblaze.ui.TrailblazeSettingsRepo
import xyz.block.trailblaze.ui.composables.DefaultDeviceClassifierIconProvider
import xyz.block.trailblaze.ui.models.AppIconProvider
import xyz.block.trailblaze.ui.models.TrailblazeServerState.SavedTrailblazeAppConfig

/**
 * A run dispatch registers the device as busy before the run starts, so the busy-device gate can
 * reject a click stampede. These lock the other half of that contract (CPLAT-1623): a dispatch that
 * never becomes a run must hand the device back, or the device stays unusable until the daemon
 * restarts - which is what the desktop "Run recording" button did, since a recording file can carry
 * config (`skip:`, a driver pin) that short-circuits the run before it ever opens a session.
 *
 * The run itself is injected, so nothing here needs a device: the runner lambda stands in for the
 * dispatch outcome (throws / completes without a session / stays in flight).
 */
class RunDispatchDeviceReleaseTest {

  private val tempDir: File = File.createTempFile("trailblaze-run-release-", "").also {
    it.delete()
    it.mkdirs()
  }

  @After
  fun tearDown() {
    tempDir.deleteRecursively()
  }

  private val deviceId = TrailblazeDeviceId("emulator-5554", TrailblazeDevicePlatform.ANDROID)

  private val recordingYaml = """
    config:
      devices:
        android-phone: ANDROID_ONDEVICE_ACCESSIBILITY
      title: TC-1 recording

    trail:
      - step: Sign in
        recording:
          android-phone:
            - tapOnPoint:
                x: 10
                y: 20
  """.trimIndent()

  private class Harness(val deps: TrailRunnerDeps, val deviceManager: TrailblazeDeviceManager)

  private fun harness(runYaml: (DesktopAppRunYamlParams) -> Unit): Harness {
    val logsRepo = LogsRepo(logsDir = File(tempDir, "logs").also { it.mkdirs() }, watchFileSystem = false)
    val settingsRepo = TrailblazeSettingsRepo(
      settingsFile = File(tempDir, "settings-${System.nanoTime()}.json"),
      initialConfig = SavedTrailblazeAppConfig(selectedTrailblazeDriverTypes = emptyMap()),
      defaultHostAppTarget = TrailblazeHostAppTarget.DefaultTrailblazeHostAppTarget,
      allTargetApps = { emptySet() },
      supportedDriverTypes = emptySet(),
    )
    val deviceManager = TrailblazeDeviceManager(
      logsRepo = logsRepo,
      settingsRepo = settingsRepo,
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
      initialAppTargets = emptySet(),
      appIconProvider = AppIconProvider.DefaultAppIconProvider,
      deviceClassifierIconProvider = DefaultDeviceClassifierIconProvider,
      runYamlLambda = runYaml,
      installedAppIdsProviderBlocking = { emptySet() },
      appVersionInfoProviderBlocking = { _, _ -> null },
      onDeviceInstrumentationArgsProvider = { emptyMap() },
      trailblazeAnalytics = TrailblazeAnalytics.NoOp,
    )
    val deps = TrailRunnerDeps(
      trailsRootProvider = { File(tempDir, "trails").also { it.mkdirs() } },
      logsRepo = logsRepo,
      settingsRepo = settingsRepo,
      deviceManager = deviceManager,
      integrationsProvider = null,
      integrationActionHandler = null,
      analyticsProvider = null,
      analyticsCaptureStarter = null,
      eventCaptureController = null,
    )
    return Harness(deps, deviceManager)
  }

  // Capture is switched off explicitly: resolving a session starts capture for it, and letting the
  // defaults through would have these tests reaching for screenrecord/logcat on a device that
  // doesn't exist. The reservation is what's under test, not the streams.
  private fun runRecording(harness: Harness): RunDispatchResult = runBlocking {
    buildRunDispatchResult(
      harness.deps,
      RunRequest(
        trailblazeDeviceId = deviceId,
        yaml = recordingYaml,
        useRecordedSteps = true,
        captureVideo = false,
        captureLogcat = false,
        captureIosLogs = false,
      ),
    )
  }

  @Test
  fun `a dispatch that throws leaves the device free for the next run`() {
    val harness = harness { error("the on-device server never came up") }

    val first = runRecording(harness)
    assertTrue(first is RunDispatchResult.Ok, "a dispatch failure is reported in-band: $first")
    assertEquals(false, first.response.success)
    assertNull(harness.deviceManager.getCurrentSessionIdForDevice(deviceId), "the device should not still be held")

    val second = runRecording(harness)
    assertTrue(
      second is RunDispatchResult.Ok,
      "the next run must reach dispatch, not be rejected as busy: $second",
    )
  }

  @Test
  fun `a run that finishes without ever opening a session frees the device`() {
    // Mirrors a recording whose `config.skip` (or rejected driver pin) short-circuits the runner:
    // it reports a finished run, but no session was ever created and no log was ever written.
    var finish: ((TrailExecutionResult) -> Unit)? = null
    val harness = harness { params -> finish = params.onComplete }

    val first = runRecording(harness)
    assertTrue(first is RunDispatchResult.Ok && first.response.success, "dispatch should be accepted: $first")

    finish!!(TrailExecutionResult.Failed("Test execution did not produce a session id"))

    assertNull(harness.deviceManager.getCurrentSessionIdForDevice(deviceId), "the device should not still be held")
    val second = runRecording(harness)
    assertTrue(
      second is RunDispatchResult.Ok,
      "the next run must reach dispatch, not be rejected as busy: $second",
    )
  }

  @Test
  fun `releasing a stale reservation leaves the run that took the device alone`() {
    // A late callback from a dispatch that already lost the device must be a no-op. This is why the
    // release keys on its own session id and compares under the manager's lock: the forceful
    // `cancelSessionForDevice` path drops whatever mapping it finds, so reaching for that instead
    // would let an older run's callback hand back a newer run's device.
    val harness = harness { }
    assertTrue(runRecording(harness) is RunDispatchResult.Ok)
    val holder = harness.deviceManager.getCurrentSessionIdForDevice(deviceId)!!

    val released = harness.deviceManager.releaseUnstartedSession(deviceId, SessionId("recording_already_gone"))

    assertEquals(false, released, "a release keyed on another session must report that it did nothing")
    assertEquals(holder, harness.deviceManager.getCurrentSessionIdForDevice(deviceId), "the holder must keep the device")
  }

  @Test
  fun `a run still in flight keeps a second dispatch off the same device`() {
    // The release must not weaken the anti-stampede gate: until the in-flight run reports back,
    // the device stays claimed even though it has written no log yet.
    val harness = harness { }

    assertTrue(runRecording(harness) is RunDispatchResult.Ok)

    val second = runRecording(harness)
    assertTrue(second is RunDispatchResult.Invalid, "a second click must be rejected: $second")
    assertEquals(HttpStatusCode.Conflict, second.status)
  }
}
