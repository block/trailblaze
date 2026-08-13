package xyz.block.trailblaze.ui

import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.model.TrailblazeHostAppTarget
import xyz.block.trailblaze.report.utils.LogsRepo
import xyz.block.trailblaze.ui.composables.DefaultDeviceClassifierIconProvider
import xyz.block.trailblaze.ui.models.AppIconProvider
import xyz.block.trailblaze.ui.models.TrailblazeServerState
import xyz.block.trailblaze.ui.models.TrailblazeServerState.SavedTrailblazeAppConfig

/**
 * Locks the lazy-inventory contract on [TrailblazeDeviceManager] (OSS issue block/trailblaze#216): device discovery
 * must never invoke the per-device installed-apps probe — that eager probe (`simctl listapps`
 * per booted iOS simulator) is what made every un-pinned CLI command pay seconds whenever a
 * simulator was booted. Inventory is pulled only via [TrailblazeDeviceManager.refreshAppInventory].
 *
 * Discovery here runs against the real host (adb/simctl may or may not find devices — both
 * fine): the assertion is about which code paths call the injected probe, not about what is
 * connected. To keep that assertion meaningful on a host with nothing attached, the fixture
 * sets `testingEnvironment = WEB`, which keeps the always-present Playwright virtual device in
 * the filtered device set — the set the old eager probe iterated. Verified against the
 * regression it names: re-injecting the removed eager-probe loop into discovery makes the
 * discovery test fail with a non-zero probe count.
 */
class DeviceManagerLazyInventoryTest {

  private val tempDir: File = File.createTempFile("trailblaze-inventory-test-", "").also {
    it.delete()
    it.mkdirs()
  }

  @After
  fun tearDown() {
    tempDir.deleteRecursively()
  }

  private fun deviceManager(
    probeCount: AtomicInteger,
    installedAppIds: () -> Set<String> = { setOf("com.example.app") },
  ): TrailblazeDeviceManager {
    val settingsRepo = TrailblazeSettingsRepo(
      settingsFile = File(tempDir, "settings.json"),
      initialConfig = SavedTrailblazeAppConfig(
        selectedTrailblazeDriverTypes = emptyMap(),
        // WEB keeps the Playwright virtual device (always present in discovery, no hardware
        // needed) in the filtered set the old eager probe iterated — without it, that set is
        // empty on a device-less host and the discovery assertion below can't fail.
        testingEnvironment = TrailblazeServerState.TestingEnvironment.WEB,
      ),
      defaultHostAppTarget = TrailblazeHostAppTarget.DefaultTrailblazeHostAppTarget,
      allTargetApps = { emptySet() },
      supportedDriverTypes = emptySet(),
    )
    return TrailblazeDeviceManager(
      logsRepo = LogsRepo(logsDir = File(tempDir, "logs").also { it.mkdirs() }, watchFileSystem = false),
      settingsRepo = settingsRepo,
      defaultHostAppTarget = TrailblazeHostAppTarget.DefaultTrailblazeHostAppTarget,
      currentTrailblazeLlmModelProvider = { error("LLM not available in tests") },
      initialAppTargets = emptySet(),
      appIconProvider = AppIconProvider.DefaultAppIconProvider,
      deviceClassifierIconProvider = DefaultDeviceClassifierIconProvider,
      runYamlLambda = { error("YAML runner not available in tests") },
      installedAppIdsProviderBlocking = {
        probeCount.incrementAndGet()
        installedAppIds()
      },
      appVersionInfoProviderBlocking = { _, _ -> null },
      onDeviceInstrumentationArgsProvider = { emptyMap() },
      trailblazeAnalytics = TrailblazeAnalytics.NoOp,
    )
  }

  @Test
  fun `device discovery never invokes the app inventory probe`() = runBlocking {
    val probeCount = AtomicInteger(0)
    val manager = deviceManager(probeCount)

    manager.loadDevicesSuspend()

    assertEquals(0, probeCount.get())
  }

  @Test
  fun `refreshAppInventory probes the device and publishes the flow`() = runBlocking {
    val probeCount = AtomicInteger(0)
    val manager = deviceManager(probeCount)
    val deviceId = TrailblazeDeviceId("emulator-5554", TrailblazeDevicePlatform.ANDROID)

    val installed = manager.refreshAppInventory(deviceId)

    assertEquals(1, probeCount.get())
    assertEquals(setOf("com.example.app"), installed)
    assertEquals(setOf("com.example.app"), manager.installedAppIdsByDeviceFlow.value[deviceId])
  }

  @Test
  fun `a failed device probe surfaces as null, not as an empty app set`() = runBlocking {
    val probeCount = AtomicInteger(0)
    val manager = deviceManager(probeCount, installedAppIds = { error("adb shell pm list failed") })
    val deviceId = TrailblazeDeviceId("emulator-5554", TrailblazeDevicePlatform.ANDROID)

    // The manager wraps the probe in runWithTimeout, whose null must reach callers as null:
    // coercing it to an empty set would tell `launchApp` validation the app isn't installed.
    assertNull(manager.refreshAppInventory(deviceId))
    assertEquals(1, probeCount.get())
    assertNull(manager.installedAppIdsByDeviceFlow.value[deviceId])
  }
}
