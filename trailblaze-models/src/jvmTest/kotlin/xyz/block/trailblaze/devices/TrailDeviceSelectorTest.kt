package xyz.block.trailblaze.devices

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import xyz.block.trailblaze.yaml.TrailblazeYaml

/**
 * Pins the shared device-selection policy ([TrailDeviceSelector]) that the CLI `run` command, the
 * daemon's `/cli/run` handler, and the desktop app's Run action all delegate to: explicit device
 * list > `--all-devices` fan-out > pinned default > strict fail-loud on a bare multi-device shell
 * (never a silent first-pick). Pure — plain strings, no daemon, no device.
 *
 * The CLI-specific layering on top (`--driver` resolution, web/compose defer, file reads) is
 * pinned by `TrailCommandDeviceAmbiguityTest` in `:trailblaze-host`.
 */
class TrailDeviceSelectorTest {

  private val android1 = "android/emulator-5554"
  private val android2 = "android/emulator-5556"
  private val ios1 = "ios/SIM-UUID"

  private val ANDROID = setOf(TrailblazeDevicePlatform.ANDROID)
  private val ANDROID_IOS = setOf(TrailblazeDevicePlatform.ANDROID, TrailblazeDevicePlatform.IOS)

  // ---- selectDevicesToRun: precedence + narrowing ----

  @Test
  fun `a single explicit device runs once`() {
    assertEquals(
      TrailDeviceSelection.Resolved(listOf(android1)),
      TrailDeviceSelector.selectDevicesToRun(listOf(android1), allDevices = false, defaultDevice = null, connectedSpecs = null, supportedPlatforms = emptySet()),
    )
  }

  @Test
  fun `several explicit devices fan out, in order`() {
    assertEquals(
      TrailDeviceSelection.Resolved(listOf(android1, ios1)),
      TrailDeviceSelector.selectDevicesToRun(listOf(android1, ios1), allDevices = false, defaultDevice = null, connectedSpecs = listOf(ios1, android1), supportedPlatforms = ANDROID),
      "explicit --device list wins over everything and preserves order",
    )
  }

  @Test
  fun `all-devices fans out across every supported connected device`() {
    assertEquals(
      TrailDeviceSelection.Resolved(listOf(android1, ios1)),
      TrailDeviceSelector.selectDevicesToRun(emptyList(), allDevices = true, defaultDevice = null, connectedSpecs = listOf(android1, ios1), supportedPlatforms = ANDROID_IOS),
    )
  }

  @Test
  fun `all-devices narrows to the platforms the trail supports`() {
    assertEquals(
      TrailDeviceSelection.Resolved(listOf(android1)),
      TrailDeviceSelector.selectDevicesToRun(emptyList(), allDevices = true, defaultDevice = null, connectedSpecs = listOf(android1, ios1), supportedPlatforms = ANDROID),
      "an android-only trail under --all-devices runs only on the android device(s)",
    )
  }

  @Test
  fun `all-devices with no supported device connected is ambiguous`() {
    assertEquals(
      TrailDeviceSelection.Ambiguous(listOf(ios1)),
      TrailDeviceSelector.selectDevicesToRun(emptyList(), allDevices = true, defaultDevice = null, connectedSpecs = listOf(ios1), supportedPlatforms = ANDROID),
    )
  }

  @Test
  fun `all-devices with zero connected devices is NoDevices, not ambiguous`() {
    // Distinct from "several, pick one" — the caller renders "no devices connected", not "pick []".
    assertEquals(
      TrailDeviceSelection.NoDevices,
      TrailDeviceSelector.selectDevicesToRun(emptyList(), allDevices = true, defaultDevice = null, connectedSpecs = emptyList(), supportedPlatforms = ANDROID),
    )
    assertEquals(
      TrailDeviceSelection.NoDevices,
      TrailDeviceSelector.selectDevicesToRun(emptyList(), allDevices = true, defaultDevice = null, connectedSpecs = null, supportedPlatforms = ANDROID),
    )
  }

  @Test
  fun `a pinned default device runs once`() {
    assertEquals(
      TrailDeviceSelection.Resolved(listOf(android1)),
      TrailDeviceSelector.selectDevicesToRun(emptyList(), allDevices = false, defaultDevice = android1, connectedSpecs = null, supportedPlatforms = emptySet()),
    )
  }

  @Test
  fun `no default and no multi-device list defers downstream`() {
    assertEquals(
      TrailDeviceSelection.Resolved(listOf<String?>(null)),
      TrailDeviceSelector.selectDevicesToRun(emptyList(), allDevices = false, defaultDevice = null, connectedSpecs = null, supportedPlatforms = emptySet()),
    )
  }

  @Test
  fun `a single connected device runs even with an empty supported set`() {
    // Bare default path (empty supported = "any"): one connected device → run on it.
    assertEquals(
      TrailDeviceSelection.Resolved(listOf(android1)),
      TrailDeviceSelector.selectDevicesToRun(emptyList(), allDevices = false, defaultDevice = null, connectedSpecs = listOf(android1), supportedPlatforms = emptySet()),
    )
  }

  @Test
  fun `a non-empty supported set narrows a mixed shell to the one match`() {
    // Reached via an EXPLICIT signal only — a `--driver` platform (the caller passes the driver's
    // platform here). The bare default path passes an empty set, so this narrowing never applies
    // to a plain multi-device run.
    assertEquals(
      TrailDeviceSelection.Resolved(listOf(android1)),
      TrailDeviceSelector.selectDevicesToRun(emptyList(), allDevices = false, defaultDevice = null, connectedSpecs = listOf(android1, ios1), supportedPlatforms = ANDROID),
    )
  }

  @Test
  fun `multi-device shell with several supported devices is ambiguous`() {
    assertEquals(
      TrailDeviceSelection.Ambiguous(listOf(android1, android2)),
      TrailDeviceSelector.selectDevicesToRun(emptyList(), allDevices = false, defaultDevice = null, connectedSpecs = listOf(android1, android2), supportedPlatforms = ANDROID),
      "two androids for an android trail → user must pick (v1 and unified alike)",
    )
  }

  @Test
  fun `multi-device shell with a trail that declares no platforms is ambiguous`() {
    assertEquals(
      TrailDeviceSelection.Ambiguous(listOf(android1, ios1)),
      TrailDeviceSelector.selectDevicesToRun(emptyList(), allDevices = false, defaultDevice = null, connectedSpecs = listOf(android1, ios1), supportedPlatforms = emptySet()),
    )
  }

  @Test
  fun `all-devices on a trail that declares no platforms runs on every connected device`() {
    // Pins the "empty supported set = any" contract for --all-devices so it can't silently flip
    // between "run everywhere" and "fail-loud".
    assertEquals(
      TrailDeviceSelection.Resolved(listOf(android1, ios1)),
      TrailDeviceSelector.selectDevicesToRun(emptyList(), allDevices = true, defaultDevice = null, connectedSpecs = listOf(android1, ios1), supportedPlatforms = emptySet()),
    )
  }

  // ---- supportedPlatformsForTrail (version-aware) ----

  private fun platformsFor(yaml: String): Set<TrailblazeDevicePlatform> =
    TrailDeviceSelector.supportedPlatformsForTrail(TrailblazeYaml.Default, yaml)

  @Test
  fun `v1 platform hint yields its platform`() {
    val yaml = "- config:\n    platform: android\n- tools:\n  - pressBack: {}"
    assertEquals(ANDROID, platformsFor(yaml))
  }

  @Test
  fun `v1 driver yields its platform`() {
    val yaml = "- config:\n    driver: IOS_HOST\n- tools:\n  - pressBack: {}"
    assertEquals(setOf(TrailblazeDevicePlatform.IOS), platformsFor(yaml))
  }

  @Test
  fun `unified devices map yields every declared platform`() {
    val yaml = """
      config:
        target: myapp
        devices:
          android: ANDROID_ONDEVICE_ACCESSIBILITY
          ios: IOS_HOST
      trail:
        - step: do something
    """.trimIndent()
    assertEquals(ANDROID_IOS, platformsFor(yaml))
  }

  @Test
  fun `unified recording classifiers contribute their platform prefix`() {
    val yaml = """
      config:
        target: myapp
      trail:
        - step: Open
          recording:
            android-tablet:
              - tapOnPoint:
                  x: 1
                  y: 1
    """.trimIndent()
    assertEquals(ANDROID, platformsFor(yaml), "an `android-tablet` key contributes ANDROID")
  }

  @Test
  fun `a trail that declares no platforms yields the empty set`() {
    val yaml = "config:\n  target: myapp\ntrail:\n  - step: do something in natural language"
    assertEquals(emptySet(), platformsFor(yaml))
  }

  @Test
  fun `unparseable yaml yields the empty set`() {
    assertEquals(emptySet(), platformsFor("this: is: not: a: trail"))
  }

  // ---- usesVirtualDevice ----

  @Test
  fun `web and compose desktop are virtual, mobile platforms are real`() {
    assertTrue(TrailblazeDevicePlatform.WEB.usesVirtualDevice)
    assertTrue(TrailblazeDevicePlatform.DESKTOP.usesVirtualDevice)
    assertFalse(TrailblazeDevicePlatform.ANDROID.usesVirtualDevice)
    assertFalse(TrailblazeDevicePlatform.IOS.usesVirtualDevice)
  }
}
