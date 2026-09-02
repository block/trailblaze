package xyz.block.trailblaze.ui

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import xyz.block.trailblaze.devices.TrailblazeConnectedDeviceSummary
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.host.driver.DeviceListingVisibility
import xyz.block.trailblaze.host.driver.FakeHostDriverDescriptor
import xyz.block.trailblaze.host.driver.HostDriverDescriptorRegistry
import xyz.block.trailblaze.model.TrailblazeHostAppTarget
import xyz.block.trailblaze.report.utils.LogsRepo
import xyz.block.trailblaze.ui.composables.DefaultDeviceClassifierIconProvider
import xyz.block.trailblaze.ui.models.AppIconProvider
import xyz.block.trailblaze.ui.models.TrailblazeServerState.SavedTrailblazeAppConfig
import java.io.File
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins how [TrailblazeDeviceManager] consults plugged-in drivers, which is the part of the
 * descriptor conversion that changes observable behavior rather than just moving code.
 *
 * Discovery runs against the real host here — whatever adb/simctl find is irrelevant, because
 * every assertion is about the descriptor's own devices.
 */
class DeviceManagerDescriptorWiringTest {

  private val tempDir: File = File.createTempFile("trailblaze-descriptor-wiring-", "").also {
    it.delete()
    it.mkdirs()
  }

  @After
  fun tearDown() {
    tempDir.deleteRecursively()
  }

  private val cloudDevice = TrailblazeConnectedDeviceSummary(
    trailblazeDriverType = TrailblazeDriverType.REVYL_ANDROID,
    instanceId = "revyl-android-phone",
    description = "Revyl Android (Default)",
  )

  private val localDevice = TrailblazeConnectedDeviceSummary(
    trailblazeDriverType = TrailblazeDriverType.ANDROID_ONDEVICE_ACCESSIBILITY,
    instanceId = "descriptor-test-device",
    description = "Descriptor Test Device",
  )

  private fun deviceManager(registry: HostDriverDescriptorRegistry): TrailblazeDeviceManager {
    val settingsRepo = TrailblazeSettingsRepo(
      settingsFile = File(tempDir, "settings.json"),
      // No driver is enabled, so anything that survives the filter does so on its visibility.
      initialConfig = SavedTrailblazeAppConfig(selectedTrailblazeDriverTypes = emptyMap()),
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
      installedAppIdsProviderBlocking = { emptySet() },
      appVersionInfoProviderBlocking = { _, _ -> null },
      onDeviceInstrumentationArgsProvider = { emptyMap() },
      trailblazeAnalytics = TrailblazeAnalytics.NoOp,
      hostDriverDescriptors = registry,
    )
  }

  /**
   * A driver's devices reach the device list because its descriptor found them — this is what
   * lets a new driver be added without editing discovery.
   */
  @Test
  fun `a plugged-in driver contributes its devices to discovery`() {
    val manager = deviceManager(
      HostDriverDescriptorRegistry(
        setOf(
          FakeHostDriverDescriptor(
            TrailblazeDriverType.REVYL_ANDROID,
            listingVisibility = DeviceListingVisibility.ADDRESSABLE_NOT_LISTED,
            devices = listOf(cloudDevice),
          ),
        ),
      ),
    )

    val discovered = runBlocking { manager.loadDevicesSuspend(applyDriverFilter = false) }

    assertTrue(
      discovered.any { it.instanceId == cloudDevice.instanceId },
      "the descriptor's device should be discovered: $discovered",
    )
  }

  /** No descriptor, no devices — an app that doesn't plug a driver in doesn't get it. */
  @Test
  fun `a driver with no descriptor contributes nothing`() {
    val manager = deviceManager(HostDriverDescriptorRegistry.EMPTY)

    val discovered = runBlocking { manager.loadDevicesSuspend(applyDriverFilter = false) }

    assertFalse(
      discovered.any { it.instanceId == cloudDevice.instanceId },
      "nothing should have discovered a Revyl device: $discovered",
    )
  }

  /**
   * An addressable-but-unlisted device has to stay in device state even with its driver disabled,
   * because `--device <its id>` resolves against that state. The listings that shouldn't show it
   * strip it themselves.
   */
  @Test
  fun `an addressable-but-unlisted device survives the enabled-drivers filter`() {
    val manager = deviceManager(
      HostDriverDescriptorRegistry(
        setOf(
          FakeHostDriverDescriptor(
            TrailblazeDriverType.REVYL_ANDROID,
            listingVisibility = DeviceListingVisibility.ADDRESSABLE_NOT_LISTED,
            devices = listOf(cloudDevice),
          ),
        ),
      ),
    )

    val filtered = runBlocking { manager.loadDevicesSuspend(applyDriverFilter = true) }

    assertTrue(
      filtered.any { it.instanceId == cloudDevice.instanceId },
      "an addressable device must remain addressable while its driver is disabled: $filtered",
    )
  }

  /**
   * Capture contains a misbehaving plug-in the same way discovery does. This call serves the MCP
   * screen-state request, so a descriptor that throws must cost the caller one capture (null, the
   * same answer as "nothing to capture") rather than failing the request.
   */
  @Test
  fun `a descriptor that throws while capturing yields no screen state instead of failing the call`() {
    val manager = deviceManager(
      HostDriverDescriptorRegistry(
        setOf(
          // FakeHostDriverDescriptor.screenState throws — that IS the misbehaving plug-in here.
          FakeHostDriverDescriptor(
            TrailblazeDriverType.REVYL_ANDROID,
            listingVisibility = DeviceListingVisibility.ADDRESSABLE_NOT_LISTED,
            devices = listOf(cloudDevice),
          ),
        ),
      ),
    )

    val screenState = runBlocking {
      manager.loadDevicesSuspend(applyDriverFilter = true)
      manager.getCurrentScreenState(cloudDevice.trailblazeDeviceId)
    }

    assertNull(screenState)
  }

  /**
   * The counterpart: a normally-listed driver still obeys the user's enabled-drivers setting. If
   * this passed too, the visibility read would be granting every plugged-in driver a bypass.
   */
  @Test
  fun `a listed device still obeys the enabled-drivers filter`() {
    val manager = deviceManager(
      HostDriverDescriptorRegistry(
        setOf(
          FakeHostDriverDescriptor(
            TrailblazeDriverType.ANDROID_ONDEVICE_ACCESSIBILITY,
            listingVisibility = DeviceListingVisibility.LISTED,
            devices = listOf(localDevice),
          ),
        ),
      ),
    )

    val filtered = runBlocking { manager.loadDevicesSuspend(applyDriverFilter = true) }

    assertFalse(
      filtered.any { it.instanceId == localDevice.instanceId },
      "a listed driver that isn't enabled should be filtered out: $filtered",
    )
  }
}
