package xyz.block.trailblaze.ui.composables

import kotlin.test.Test
import kotlin.test.assertEquals
import xyz.block.trailblaze.devices.TrailblazeConnectedDeviceSummary
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.devices.TrailblazeDriverType

/**
 * Pins what the Run Configuration dialog pre-checks when it opens: last-used devices by default,
 * but a web/compose trail pre-checks its virtual device — so one-click "Run Tests" on a web trail
 * can't land on the last-used mobile device (the desktop counterpart of the CLI's web defer).
 */
class InitialRunDeviceSelectionTest {

  private val android = TrailblazeConnectedDeviceSummary(
    trailblazeDriverType = TrailblazeDriverType.ANDROID_ONDEVICE_ACCESSIBILITY,
    instanceId = "emulator-5554",
    description = "Pixel emulator",
  )
  private val ios = TrailblazeConnectedDeviceSummary(
    trailblazeDriverType = TrailblazeDriverType.IOS_HOST,
    instanceId = "SIM-UUID",
    description = "iPhone simulator",
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

  private val all = listOf(android, ios, webNative, webElectron)
  private val WEB_ONLY = setOf(TrailblazeDevicePlatform.WEB)

  private fun select(
    lastSelected: List<String>,
    trailPlatforms: Set<TrailblazeDevicePlatform>?,
    devices: List<TrailblazeConnectedDeviceSummary> = all,
    isEligible: (TrailblazeConnectedDeviceSummary) -> Boolean = { true },
  ) = initialRunDeviceSelection(devices, lastSelected, trailPlatforms, isEligible)

  @Test
  fun `defaults to the last-used devices that are still connected`() {
    assertEquals(
      setOf(android),
      select(lastSelected = listOf("emulator-5554", "gone-device"), trailPlatforms = null),
    )
  }

  @Test
  fun `nothing pre-checked when nothing was used before — user must pick`() {
    // The fail-loud step: an empty pre-check keeps "Run Tests" disabled on a multi-device
    // workstation until the user chooses.
    assertEquals(emptySet(), select(lastSelected = emptyList(), trailPlatforms = null))
  }

  @Test
  fun `a web trail pre-checks the browser, not the last-used mobile device`() {
    assertEquals(
      setOf(webNative),
      select(lastSelected = listOf("emulator-5554"), trailPlatforms = WEB_ONLY),
    )
  }

  @Test
  fun `a web trail prefers the native Playwright browser over other web drivers`() {
    assertEquals(
      setOf(webNative),
      select(lastSelected = emptyList(), trailPlatforms = WEB_ONLY, devices = listOf(webElectron, webNative)),
    )
  }

  @Test
  fun `a web trail with no eligible browser pre-checks nothing`() {
    // Nothing sensible to pre-check → empty set, which keeps "Run Tests" disabled until the
    // user explicitly picks a device (the fail-loud default, never a wrong-platform guess).
    assertEquals(
      emptySet(),
      select(lastSelected = listOf("emulator-5554"), trailPlatforms = WEB_ONLY, devices = listOf(android)),
    )
  }

  @Test
  fun `a mixed mobile-and-web trail keeps the last-used behavior`() {
    // Only a virtual-ONLY trail routes to the browser; a trail that also declares a mobile
    // platform is not web-deferred (matching the CLI's usesVirtualDevice all-platforms check).
    assertEquals(
      setOf(android),
      select(
        lastSelected = listOf("emulator-5554"),
        trailPlatforms = setOf(TrailblazeDevicePlatform.ANDROID, TrailblazeDevicePlatform.WEB),
      ),
    )
  }

  @Test
  fun `a mobile trail keeps the last-used behavior — declared platforms are not a hard target`() {
    // Mirrors the CLI: the default path never narrows by declared platforms (a trail runs as
    // natural language anywhere), so an android-declared trail still restores the last pick.
    assertEquals(
      setOf(ios),
      select(lastSelected = listOf("SIM-UUID"), trailPlatforms = setOf(TrailblazeDevicePlatform.ANDROID)),
    )
  }

  @Test
  fun `a trail with no declared platforms keeps the last-used behavior`() {
    assertEquals(
      setOf(android),
      select(lastSelected = listOf("emulator-5554"), trailPlatforms = emptySet()),
    )
  }

  @Test
  fun `ineligible devices are never pre-checked`() {
    assertEquals(
      emptySet(),
      select(
        lastSelected = listOf("emulator-5554"),
        trailPlatforms = null,
        isEligible = { it != android },
      ),
    )
  }
}
