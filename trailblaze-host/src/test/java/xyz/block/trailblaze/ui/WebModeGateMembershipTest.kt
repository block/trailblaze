package xyz.block.trailblaze.ui

import java.io.File
import org.junit.After
import org.junit.Test
import xyz.block.trailblaze.devices.TrailblazeConnectedDeviceSummary
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.host.minimalDeviceManager
import xyz.block.trailblaze.ui.models.TrailblazeServerState
import xyz.block.trailblaze.ui.models.TrailblazeServerState.SavedTrailblazeAppConfig
import kotlin.test.assertEquals

/**
 * [TrailblazeDeviceManager.targetDeviceFilter] gates virtual devices (Playwright browsers, the
 * Compose desktop window) behind the web-mode setting by the platform fact `usesVirtualDevice`,
 * where it used to name the three drivers. These tests run real device summaries through the
 * filter, so deleting or re-keying that gate arm fails here instead of surfacing as a device
 * silently appearing in (or vanishing from) the device list.
 */
class WebModeGateMembershipTest {

  private val tempDir: File = File.createTempFile("trailblaze-web-mode-gate-", "").also {
    it.delete()
    it.mkdirs()
  }

  @After
  fun tearDown() {
    tempDir.deleteRecursively()
  }

  private fun device(driverType: TrailblazeDriverType) = TrailblazeConnectedDeviceSummary(
    trailblazeDriverType = driverType,
    instanceId = "gate-${driverType.name.lowercase()}",
    description = driverType.name,
  )

  private val virtualDevices = listOf(
    device(TrailblazeDriverType.PLAYWRIGHT_NATIVE),
    device(TrailblazeDriverType.PLAYWRIGHT_ELECTRON),
    device(TrailblazeDriverType.COMPOSE),
  )

  private val androidDevice = device(TrailblazeDriverType.ANDROID_ONDEVICE_INSTRUMENTATION)

  /**
   * The gate must win over driver enablement: outside web mode a virtual device stays hidden even
   * when its driver is the platform's selected one. Deleting the `usesVirtualDevice -> isWebMode`
   * arm would drop these into the enabled-drivers check and leak the enabled ones into the list.
   */
  @Test
  fun `outside web mode the filter hides virtual devices even when their drivers are enabled`() {
    val manager = minimalDeviceManager(
      tempDir = tempDir,
      initialConfig = SavedTrailblazeAppConfig(
        selectedTrailblazeDriverTypes = mapOf(
          TrailblazeDevicePlatform.WEB to TrailblazeDriverType.PLAYWRIGHT_NATIVE,
          TrailblazeDevicePlatform.DESKTOP to TrailblazeDriverType.COMPOSE,
          TrailblazeDevicePlatform.ANDROID to TrailblazeDriverType.ANDROID_ONDEVICE_INSTRUMENTATION,
        ),
        testingEnvironment = TrailblazeServerState.TestingEnvironment.MOBILE,
      ),
    )

    val filtered = manager.targetDeviceFilter(virtualDevices + androidDevice)

    assertEquals(listOf(androidDevice), filtered)
  }

  /**
   * The same override in the other direction: in web mode every virtual device is listed even
   * with nothing in the enabled-drivers setting, while non-virtual devices still follow it.
   */
  @Test
  fun `in web mode the filter lists virtual devices their enablement setting doesn't name`() {
    val manager = minimalDeviceManager(
      tempDir = tempDir,
      initialConfig = SavedTrailblazeAppConfig(
        selectedTrailblazeDriverTypes = emptyMap(),
        testingEnvironment = TrailblazeServerState.TestingEnvironment.WEB,
      ),
    )

    val filtered = manager.targetDeviceFilter(virtualDevices + androidDevice)

    assertEquals(virtualDevices, filtered)
  }
}
