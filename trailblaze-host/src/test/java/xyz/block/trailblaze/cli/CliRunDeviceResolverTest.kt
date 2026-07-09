package xyz.block.trailblaze.cli

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import xyz.block.trailblaze.devices.TrailblazeConnectedDeviceSummary
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.devices.TrailblazeDriverType

/**
 * Pins the daemon `/cli/run` device resolution to the CLI's selection semantics: an explicit
 * device or driver is honored, a web/compose trail routes to the browser regardless of connected
 * mobile devices, exactly one real device runs, and 2+ real devices with nothing chosen fails
 * loud — never a silent first-pick.
 */
class CliRunDeviceResolverTest {

  private val androidA = TrailblazeConnectedDeviceSummary(
    trailblazeDriverType = TrailblazeDriverType.ANDROID_ONDEVICE_ACCESSIBILITY,
    instanceId = "emulator-5554",
    description = "Pixel emulator A",
  )
  private val androidAInstrumentation = androidA.copy(
    trailblazeDriverType = TrailblazeDriverType.ANDROID_ONDEVICE_INSTRUMENTATION,
  )
  private val androidB = TrailblazeConnectedDeviceSummary(
    trailblazeDriverType = TrailblazeDriverType.ANDROID_ONDEVICE_ACCESSIBILITY,
    instanceId = "emulator-5556",
    description = "Pixel emulator B",
  )
  private val ios = TrailblazeConnectedDeviceSummary(
    trailblazeDriverType = TrailblazeDriverType.IOS_HOST,
    instanceId = "SIM-UUID",
    description = "iPhone simulator",
  )
  private val web = TrailblazeConnectedDeviceSummary(
    trailblazeDriverType = TrailblazeDriverType.PLAYWRIGHT_NATIVE,
    instanceId = "playwright-native",
    description = "Playwright Browser (Native)",
  )
  private val webElectron = TrailblazeConnectedDeviceSummary(
    trailblazeDriverType = TrailblazeDriverType.PLAYWRIGHT_ELECTRON,
    instanceId = "playwright-electron",
    description = "Playwright Electron (CDP)",
  )

  private val WEB_ONLY = setOf(TrailblazeDevicePlatform.WEB)

  private fun resolve(
    devices: List<TrailblazeConnectedDeviceSummary>,
    deviceId: String? = null,
    driverType: TrailblazeDriverType? = null,
    trailPlatforms: Set<TrailblazeDevicePlatform> = emptySet(),
  ) = CliRunDeviceResolver.resolve(devices, deviceId, driverType, trailPlatforms)

  // ---- fail-loud default (the CLI-alignment fix) ----

  @Test
  fun `two real devices and nothing chosen fails loud, never first-pick`() {
    val resolution = resolve(listOf(androidA, androidB, web))
    assertEquals(
      CliRunDeviceResolution.MultipleDevices(listOf(androidA, androidB)),
      resolution,
      "the ever-present web device must not count toward, or appear in, the ambiguity",
    )
  }

  @Test
  fun `mixed-platform shell with nothing chosen fails loud`() {
    assertIs<CliRunDeviceResolution.MultipleDevices>(resolve(listOf(androidA, ios, web)))
  }

  @Test
  fun `a single real device runs on it`() {
    assertEquals(CliRunDeviceResolution.Selected(androidA), resolve(listOf(androidA, web)))
  }

  @Test
  fun `driver variants of one physical device are one device, not an ambiguity`() {
    // loadDevicesSuspend(applyDriverFilter = false) lists the same emulator once per driver
    // variant; counting variants would fail-loud a healthy single-device workstation.
    val resolution = resolve(listOf(androidA, androidAInstrumentation, web))
    assertIs<CliRunDeviceResolution.Selected>(resolution)
    assertEquals(androidA.trailblazeDeviceId, resolution.device.trailblazeDeviceId)
  }

  // ---- web/compose defer ----

  @Test
  fun `a web trail routes to the browser even with mobile devices connected`() {
    assertEquals(
      CliRunDeviceResolution.Selected(web),
      resolve(listOf(androidA, androidB, web), trailPlatforms = WEB_ONLY),
    )
  }

  @Test
  fun `a web trail prefers the native Playwright browser over other web drivers`() {
    assertEquals(
      CliRunDeviceResolution.Selected(web),
      resolve(listOf(webElectron, web), trailPlatforms = WEB_ONLY),
    )
  }

  @Test
  fun `a web trail with no web device connected is an error`() {
    assertIs<CliRunDeviceResolution.NoMatch>(
      resolve(listOf(androidA), trailPlatforms = WEB_ONLY),
    )
  }

  // ---- zero real devices (deferred-run concretization) ----

  @Test
  fun `no real devices and no declared platforms lands on the web catch-all`() {
    assertEquals(CliRunDeviceResolution.Selected(web), resolve(listOf(web)))
  }

  @Test
  fun `no real devices for a mobile-declared trail is an error, not a web run`() {
    // Regression guard for the unified-format drift: an android-declared unified trail with no
    // android connected must error rather than silently running on the browser.
    assertIs<CliRunDeviceResolution.NoMatch>(
      resolve(listOf(web), trailPlatforms = setOf(TrailblazeDevicePlatform.ANDROID)),
    )
  }

  @Test
  fun `no devices at all is an error`() {
    assertIs<CliRunDeviceResolution.NoMatch>(resolve(emptyList()))
  }

  // ---- explicit signals ----

  @Test
  fun `an explicit fully-qualified device id is honored`() {
    assertEquals(
      CliRunDeviceResolution.Selected(androidB),
      resolve(listOf(androidA, androidB, web), deviceId = "android/emulator-5556"),
    )
  }

  @Test
  fun `an explicit raw instance id matches across platforms`() {
    assertEquals(
      CliRunDeviceResolution.Selected(ios),
      resolve(listOf(androidA, ios), deviceId = "SIM-UUID"),
    )
  }

  @Test
  fun `an explicit partial instance id matches by substring`() {
    assertEquals(
      CliRunDeviceResolution.Selected(androidB),
      resolve(listOf(androidA, androidB), deviceId = "5556"),
    )
  }

  @Test
  fun `an explicit platform-only id auto-selects within the platform`() {
    assertEquals(
      CliRunDeviceResolution.Selected(web),
      resolve(listOf(androidA, web), deviceId = "web"),
    )
  }

  @Test
  fun `a platform id with a trailing slash behaves like the bare platform`() {
    // "web/" must not let contains("") match the first listed candidate — it auto-selects within
    // the platform with the same native-browser preference as bare "web".
    assertEquals(
      CliRunDeviceResolution.Selected(web),
      resolve(listOf(androidA, webElectron, web), deviceId = "web/"),
    )
  }

  @Test
  fun `an explicit platform-only id with no such device connected is an error`() {
    assertIs<CliRunDeviceResolution.NoMatch>(
      resolve(listOf(androidA), deviceId = "ios"),
    )
  }

  @Test
  fun `an explicit device id that matches nothing is an error`() {
    assertIs<CliRunDeviceResolution.NoMatch>(
      resolve(listOf(androidA), deviceId = "android/emulator-9999"),
    )
  }

  @Test
  fun `an explicit device id with a requested driver picks that driver variant`() {
    // One emulator, listed once per driver variant: the instance id names the physical device,
    // the requested driver (a trail's `devices:` pin / --driver) picks which variant — never
    // whichever variant happens to be listed first. Mirrors the CLI in-process path's behavior
    // before it delegated here.
    assertEquals(
      CliRunDeviceResolution.Selected(androidAInstrumentation),
      resolve(
        listOf(androidA, androidAInstrumentation),
        deviceId = "emulator-5554",
        driverType = TrailblazeDriverType.ANDROID_ONDEVICE_INSTRUMENTATION,
      ),
    )
  }

  @Test
  fun `a partial instance id with a requested driver picks that driver variant`() {
    // The tiebreak applies to the substring fallback too, not just exact-id matches.
    assertEquals(
      CliRunDeviceResolution.Selected(androidAInstrumentation),
      resolve(
        listOf(androidA, androidAInstrumentation),
        deviceId = "5554",
        driverType = TrailblazeDriverType.ANDROID_ONDEVICE_INSTRUMENTATION,
      ),
    )
  }

  @Test
  fun `a platform-prefixed device id with a requested driver picks that driver variant`() {
    // And to platform-scoped explicit ids (`android/emulator-5554`).
    assertEquals(
      CliRunDeviceResolution.Selected(androidAInstrumentation),
      resolve(
        listOf(androidA, androidAInstrumentation),
        deviceId = "android/emulator-5554",
        driverType = TrailblazeDriverType.ANDROID_ONDEVICE_INSTRUMENTATION,
      ),
    )
  }

  @Test
  fun `an explicit device id with an unlisted driver still matches the device`() {
    // The driver is a variant *preference*, not a filter: a pinned driver the device list
    // doesn't expose falls back to the first instance match (the run request still carries the
    // driver separately).
    assertEquals(
      CliRunDeviceResolution.Selected(androidA),
      resolve(
        listOf(androidA),
        deviceId = "emulator-5554",
        driverType = TrailblazeDriverType.ANDROID_ONDEVICE_INSTRUMENTATION,
      ),
    )
  }

  @Test
  fun `an explicit driver narrows to its device`() {
    assertEquals(
      CliRunDeviceResolution.Selected(ios),
      resolve(listOf(androidA, ios, web), driverType = TrailblazeDriverType.IOS_HOST),
    )
  }

  @Test
  fun `a requested driver with no matching device is an error`() {
    assertIs<CliRunDeviceResolution.NoMatch>(
      resolve(listOf(androidA, web), driverType = TrailblazeDriverType.IOS_HOST),
    )
  }

  @Test
  fun `a requested driver matching several devices fails loud, never first-pick`() {
    // Mirrors the CLI's --driver semantics: the driver narrows the candidate set, and two
    // candidates still require an explicit device choice.
    assertEquals(
      CliRunDeviceResolution.MultipleDevices(listOf(androidA, androidB)),
      resolve(
        listOf(androidA, androidB, web),
        driverType = TrailblazeDriverType.ANDROID_ONDEVICE_ACCESSIBILITY,
      ),
    )
  }
}
