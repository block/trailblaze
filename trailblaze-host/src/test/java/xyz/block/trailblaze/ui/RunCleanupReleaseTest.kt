package xyz.block.trailblaze.ui

import kotlinx.coroutines.isActive
import kotlinx.coroutines.runBlocking
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.host.driver.HostDriverDescriptorRegistry
import xyz.block.trailblaze.model.TrailblazeHostAppTarget
import xyz.block.trailblaze.report.utils.LogsRepo
import xyz.block.trailblaze.ui.models.TrailblazeServerState.SavedTrailblazeAppConfig

/**
 * A cancelled run is reported over while its job still unwinds, so the next run can be on the
 * device by the time the cancelled one cleans up. That cleanup must leave the next run alone.
 */
class RunCleanupReleaseTest {

  private val tempDir: File = Files.createTempDirectory("run-cleanup").toFile()

  @AfterTest
  fun tearDown() {
    tempDir.deleteRecursively()
  }

  private val device = TrailblazeDeviceId("emulator-5554", TrailblazeDevicePlatform.ANDROID)

  private fun manager(): TrailblazeDeviceManager {
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
      runYamlLambda = { error("YAML runner not available in tests") },
      installedAppIdsProviderBlocking = { emptySet() },
      appVersionInfoProviderBlocking = { _, _ -> null },
      onDeviceInstrumentationArgsProvider = { emptyMap() },
      trailblazeAnalytics = TrailblazeAnalytics.NoOp,
      hostDriverDescriptors = HostDriverDescriptorRegistry.EMPTY,
    )
  }

  @Test
  fun `a run's cleanup cancels the device's session and scope when it is the only run there`() {
    val manager = manager()
    manager.beginRun(device)
    manager.getOrCreateSessionResolution(device, sessionIdPrefix = "yaml")
    val scope = manager.getOrCreateCoroutineScopeForDevice(device)

    assertTrue(runBlocking { manager.releaseDeviceAfterRun(device) })

    assertFalse(scope.isActive)
    assertNull(manager.getCurrentSessionIdForDevice(device))
  }

  @Test
  fun `a cancelled run's cleanup leaves the next run on the device alone`() {
    val manager = manager()
    val cancelledRun = manager.beginRun(device)
    manager.beginRun(device)
    val nextSession = manager.getOrCreateSessionResolution(device, forceNewSession = true, sessionIdPrefix = "yaml").sessionId
    val nextScope = manager.getOrCreateCoroutineScopeForDevice(device)

    assertFalse(runBlocking(cancelledRun) { manager.releaseDeviceAfterRun(device) })
    manager.endRun(cancelledRun)

    assertTrue(nextScope.isActive)
    assertEquals(nextSession, manager.getCurrentSessionIdForDevice(device))
  }

  @Test
  fun `a release both overlapping runs skipped happens when the device's last run ends`() {
    val manager = manager()
    val first = manager.beginRun(device)
    val second = manager.beginRun(device)
    val session = manager.getOrCreateSessionResolution(device, sessionIdPrefix = "yaml").sessionId
    first.sessionId = session
    second.sessionId = session
    val scope = manager.getOrCreateCoroutineScopeForDevice(device)

    assertFalse(runBlocking(first) { manager.releaseDeviceAfterRun(device) })
    assertFalse(runBlocking(second) { manager.releaseDeviceAfterRun(device) })
    manager.endRun(first)
    assertTrue(scope.isActive, "a run is still executing on the device")

    manager.endRun(second)
    assertFalse(scope.isActive)
    assertNull(manager.getCurrentSessionIdForDevice(device))
  }

  @Test
  fun `a skipped release leaves alone a session that kept the device meanwhile`() {
    val manager = manager()
    val cancelledRun = manager.beginRun(device)
    cancelledRun.sessionId = manager.getOrCreateSessionResolution(device, sessionIdPrefix = "yaml").sessionId
    val interactiveCall = manager.beginRun(device)
    val keptSession = manager.getOrCreateSessionResolution(device, forceNewSession = true, sessionIdPrefix = "mcp").sessionId
    val scope = manager.getOrCreateCoroutineScopeForDevice(device)

    assertFalse(runBlocking(cancelledRun) { manager.releaseDeviceAfterRun(device) })
    manager.endRun(cancelledRun)
    manager.endRun(interactiveCall)

    assertTrue(scope.isActive)
    assertEquals(keptSession, manager.getCurrentSessionIdForDevice(device))
  }

  @Test
  fun `a release on one device does not hold up a run starting on another`() {
    val manager = manager()
    val other = TrailblazeDeviceId("emulator-5556", TrailblazeDevicePlatform.ANDROID)
    val releasing = CountDownLatch(1)
    val finish = CountDownLatch(1)
    manager.beginRun(device)
    val release = thread {
      manager.ifDeviceStillOnSession(device, ownSession = null) {
        releasing.countDown()
        finish.await(30, TimeUnit.SECONDS)
      }
    }
    assertTrue(releasing.await(10, TimeUnit.SECONDS))

    val started = System.currentTimeMillis()
    manager.beginRun(other)

    assertTrue(System.currentTimeMillis() - started < 5_000, "another device's release held this run up")
    finish.countDown()
    release.join()
  }

  @Test
  fun `a run is refused rather than started while its device is still being released`() {
    val manager = manager().apply { deviceReleaseWaitMillis = 200 }
    val releasing = CountDownLatch(1)
    val finish = CountDownLatch(1)
    manager.beginRun(device)
    val release = thread {
      manager.ifDeviceStillOnSession(device, ownSession = null) {
        releasing.countDown()
        finish.await(30, TimeUnit.SECONDS)
      }
    }
    assertTrue(releasing.await(10, TimeUnit.SECONDS))

    val refused = assertFailsWith<IllegalStateException> { manager.beginRun(device) }

    assertTrue("still being released" in refused.message.orEmpty(), refused.message)
    finish.countDown()
    release.join()
  }

  @Test
  fun `a cancelled run's stop leaves a device another session took over`() {
    val manager = manager()
    val cancelledRun = manager.beginRun(device)
    val ownSession = manager.getOrCreateSessionResolution(device, sessionIdPrefix = "yaml").sessionId
    // An interactive call takes the device on a session of its own, then unregisters as calls do.
    manager.endRun(manager.beginRun(device).also {
      manager.getOrCreateSessionResolution(device, forceNewSession = true, sessionIdPrefix = "mcp")
    })
    var stopped = false

    assertFalse(manager.ifDeviceStillOnSession(device, ownSession) { stopped = true })
    assertFalse(stopped)
    manager.endRun(cancelledRun)
  }

  @Test
  fun `a cancelled run's stop goes ahead while the device is still on its session`() {
    val manager = manager()
    manager.beginRun(device)
    val ownSession = manager.getOrCreateSessionResolution(device, sessionIdPrefix = "yaml").sessionId
    var stopped = false

    assertTrue(manager.ifDeviceStillOnSession(device, ownSession) { stopped = true })
    assertTrue(stopped)
  }
}
