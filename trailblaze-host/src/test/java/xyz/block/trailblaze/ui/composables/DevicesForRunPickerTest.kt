package xyz.block.trailblaze.ui.composables

import kotlin.test.Test
import kotlin.test.assertEquals
import xyz.block.trailblaze.devices.TrailblazeConnectedDeviceSummary
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.devices.TrailblazeDriverType

/**
 * Pins which devices the Run Configuration dialog lists: the device manager's filtered list,
 * plus — for a virtual-only trail — the trail's virtual devices from the unfiltered discovery.
 * Without the union, a web/compose trail can't be run from the picker in a mobile testing
 * environment at all (no browser row to check, pre-checked or otherwise).
 */
class DevicesForRunPickerTest {

  private val android = TrailblazeConnectedDeviceSummary(
    trailblazeDriverType = TrailblazeDriverType.ANDROID_ONDEVICE_ACCESSIBILITY,
    instanceId = "emulator-5554",
    description = "Pixel emulator",
  )
  private val webNative = TrailblazeConnectedDeviceSummary(
    trailblazeDriverType = TrailblazeDriverType.PLAYWRIGHT_NATIVE,
    instanceId = "playwright-native",
    description = "Playwright Browser (Native)",
  )
  private val webElectron = TrailblazeConnectedDeviceSummary(
    trailblazeDriverType = TrailblazeDriverType.PLAYWRIGHT_ELECTRON,
    instanceId = "playwright-electron",
    description = "Playwright Electron (CDP)",
  )
  private val composeDesktop = TrailblazeConnectedDeviceSummary(
    trailblazeDriverType = TrailblazeDriverType.COMPOSE,
    instanceId = "self",
    description = "Compose Desktop (RPC)",
  )

  private val allDiscovered = listOf(android, webNative, webElectron, composeDesktop)
  private val WEB_ONLY = setOf(TrailblazeDevicePlatform.WEB)

  @Test
  fun `a web trail in a mobile environment gains the hidden browser rows`() {
    assertEquals(
      listOf(android, webNative, webElectron),
      devicesForRunPicker(
        filteredDevices = listOf(android),
        allDiscoveredDevices = allDiscovered,
        trailPlatforms = WEB_ONLY,
      ),
    )
  }

  @Test
  fun `only the trail's declared virtual platforms are added — a web trail gains no compose row`() {
    val result = devicesForRunPicker(
      filteredDevices = listOf(android),
      allDiscoveredDevices = allDiscovered,
      trailPlatforms = WEB_ONLY,
    )
    assertEquals(false, composeDesktop in result)
  }

  @Test
  fun `a compose trail gains the compose row`() {
    assertEquals(
      listOf(android, composeDesktop),
      devicesForRunPicker(
        filteredDevices = listOf(android),
        allDiscoveredDevices = allDiscovered,
        trailPlatforms = setOf(TrailblazeDevicePlatform.DESKTOP),
      ),
    )
  }

  @Test
  fun `no duplicates when the environment already lists the virtual device`() {
    // testingEnvironment=WEB: the filtered list already contains the browsers.
    assertEquals(
      listOf(webNative, webElectron),
      devicesForRunPicker(
        filteredDevices = listOf(webNative, webElectron),
        allDiscoveredDevices = allDiscovered,
        trailPlatforms = WEB_ONLY,
      ),
    )
  }

  @Test
  fun `a mobile trail keeps the filtered list untouched`() {
    assertEquals(
      listOf(android),
      devicesForRunPicker(
        filteredDevices = listOf(android),
        allDiscoveredDevices = allDiscovered,
        trailPlatforms = setOf(TrailblazeDevicePlatform.ANDROID),
      ),
    )
  }

  @Test
  fun `a mixed mobile-and-web trail keeps the filtered list untouched`() {
    // Matches the CLI + pre-check semantics: only a virtual-ONLY trail is special-cased.
    assertEquals(
      listOf(android),
      devicesForRunPicker(
        filteredDevices = listOf(android),
        allDiscoveredDevices = allDiscovered,
        trailPlatforms = setOf(TrailblazeDevicePlatform.ANDROID, TrailblazeDevicePlatform.WEB),
      ),
    )
  }

  @Test
  fun `non-trail callers and platform-less trails keep the filtered list untouched`() {
    assertEquals(
      listOf(android),
      devicesForRunPicker(listOf(android), allDiscovered, trailPlatforms = null),
    )
    assertEquals(
      listOf(android),
      devicesForRunPicker(listOf(android), allDiscovered, trailPlatforms = emptySet()),
    )
  }
}
