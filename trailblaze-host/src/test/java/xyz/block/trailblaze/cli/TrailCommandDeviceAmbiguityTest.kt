package xyz.block.trailblaze.cli

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import xyz.block.trailblaze.devices.TrailDeviceSelection
import xyz.block.trailblaze.devices.TrailDeviceSelector

/**
 * Pins `trailblaze run`'s per-file device selection: target-aware narrowing + fail-loud ambiguity
 * (decision "A"), plus the opt-in fan-out (`--device a,b`, `--all-devices`).
 *
 * The core policy is the pure [TrailDeviceSelector.selectDevicesToRun] in `:trailblaze-models`
 * (pinned by `TrailDeviceSelectorTest` there, shared with the desktop app so the surfaces can't
 * drift); [TrailCommand.resolveDevicesForFile] layers on the `--driver`/YAML platform resolution
 * and the web/compose virtual-device defer. One consistent rule for BOTH the v1 and unified
 * formats.
 */
class TrailCommandDeviceAmbiguityTest {

  private val cmd = TrailCommand()

  private val android1 = "android/emulator-5554"
  private val android2 = "android/emulator-5556"
  private val ios1 = "ios/SIM-UUID"

  private fun trailFile(contents: String): File =
    File.createTempFile("trail-ambiguity-", ".trail.yaml").apply {
      deleteOnExit()
      writeText(contents)
    }

  @Test
  fun `an explicit device wins outright, no file read`() {
    assertEquals(
      TrailDeviceSelection.Resolved(listOf(ios1)),
      cmd.resolveDevicesForFile(trailFile("- tools:\n  - pressBack: {}"), listOf(ios1), allDevices = false, defaultDevice = null, driverType = null, connectedSpecs = listOf(android1, ios1)),
    )
  }

  @Test
  fun `a pinned default wins on a multi-device shell`() {
    assertEquals(
      TrailDeviceSelection.Resolved(listOf(android2)),
      cmd.resolveDevicesForFile(trailFile("- tools:\n  - pressBack: {}"), emptyList(), allDevices = false, defaultDevice = android2, driverType = null, connectedSpecs = listOf(android1, ios1)),
    )
  }

  @Test
  fun `no multi-device situation defers downstream`() {
    assertEquals(
      TrailDeviceSelection.Resolved(listOf<String?>(null)),
      cmd.resolveDevicesForFile(trailFile("- tools:\n  - pressBack: {}"), emptyList(), allDevices = false, defaultDevice = null, driverType = null, connectedSpecs = null),
    )
  }

  @Test
  fun `a driver flag forces the platform and disambiguates`() {
    assertEquals(
      TrailDeviceSelection.Resolved(listOf(ios1)),
      cmd.resolveDevicesForFile(trailFile("- config: {}\n- tools:\n  - pressBack: {}"), emptyList(), allDevices = false, defaultDevice = null, driverType = "IOS_HOST", connectedSpecs = listOf(android1, ios1)),
    )
  }

  @Test
  fun `a single-platform trail on a mixed shell requires an explicit device`() {
    // Strict rule: the bare default path does NOT narrow by the trail's declared platforms (a
    // trail can run as NL on any device), so 2+ connected devices with no --device/pin/--driver
    // is ambiguous — even for an android-only trail on an android+ios shell.
    val file = trailFile(
      """
      config:
        target: myapp
        devices:
          android: ANDROID_ONDEVICE_ACCESSIBILITY
      trail:
        - step: do something
      """.trimIndent(),
    )
    assertEquals(
      TrailDeviceSelection.Ambiguous(listOf(android1, ios1)),
      cmd.resolveDevicesForFile(file, emptyList(), allDevices = false, defaultDevice = null, driverType = null, connectedSpecs = listOf(android1, ios1)),
    )
  }

  @Test
  fun `a multi-platform trail with several matching devices is ambiguous`() {
    val file = trailFile(
      """
      config:
        target: myapp
        devices:
          android: ANDROID_ONDEVICE_ACCESSIBILITY
          ios: IOS_HOST
      trail:
        - step: do something
      """.trimIndent(),
    )
    assertEquals(
      TrailDeviceSelection.Ambiguous(listOf(android1, ios1)),
      cmd.resolveDevicesForFile(file, emptyList(), allDevices = false, defaultDevice = null, driverType = null, connectedSpecs = listOf(android1, ios1)),
      "opt into both with --device a,b or --all-devices",
    )
  }

  @Test
  fun `all-devices reads the trail to fan out across supported platforms`() {
    val file = trailFile(
      """
      config:
        target: myapp
        devices:
          android: ANDROID_ONDEVICE_ACCESSIBILITY
          ios: IOS_HOST
      trail:
        - step: do something
      """.trimIndent(),
    )
    assertEquals(
      TrailDeviceSelection.Resolved(listOf(android1, ios1)),
      cmd.resolveDevicesForFile(file, emptyList(), allDevices = true, defaultDevice = null, driverType = null, connectedSpecs = listOf(android1, ios1)),
    )
  }

  @Test
  fun `all-devices narrows to supported platforms even with a pinned default`() {
    // Regression guard: --all-devices must filter connected devices by the trail's platforms
    // regardless of a pinned default (the `needsPlatforms` gate must include allDevices, else it
    // skips the YAML parse and fans out to every connected device).
    val file = trailFile(
      """
      config:
        target: myapp
        devices:
          android: ANDROID_ONDEVICE_ACCESSIBILITY
      trail:
        - step: do something
      """.trimIndent(),
    )
    assertEquals(
      TrailDeviceSelection.Resolved(listOf(android1)),
      cmd.resolveDevicesForFile(file, emptyList(), allDevices = true, defaultDevice = android1, driverType = null, connectedSpecs = listOf(android1, ios1)),
    )
  }

  @Test
  fun `a web trail on a mixed mobile shell defers to downstream virtual-device routing`() {
    // A web/compose trail runs on a virtual device regardless of connected real devices, so a
    // shell with several mobile devices must NOT fail-loud on it — defer to downstream routing.
    val file = trailFile("- config:\n    platform: web\n- tools:\n  - pressBack: {}")
    assertEquals(
      TrailDeviceSelection.Resolved(listOf<String?>(null)),
      cmd.resolveDevicesForFile(file, emptyList(), allDevices = false, defaultDevice = null, driverType = null, connectedSpecs = listOf(android1, ios1)),
    )
  }

  @Test
  fun `a web trail with exactly one mobile device connected still defers to web`() {
    // The uniform-defer contract: web/compose routing must not depend on the mobile-device count.
    // With a single autodetected mobile device (defaultDevice would be that device), a web trail
    // must still defer to the virtual device rather than run on the mobile and error.
    val file = trailFile("- config:\n    platform: web\n- tools:\n  - pressBack: {}")
    assertEquals(
      TrailDeviceSelection.Resolved(listOf<String?>(null)),
      cmd.resolveDevicesForFile(file, emptyList(), allDevices = false, defaultDevice = android1, driverType = null, connectedSpecs = listOf(android1)),
    )
  }

  @Test
  fun `supportedPlatformsForTrail delegates with the full tool codec`() {
    // The heavy lifting is pinned in :trailblaze-models (TrailDeviceSelectorTest); this pins the
    // CLI wrapper wiring — a recorded-tool unified trail resolves through createTrailblazeYaml().
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
    assertEquals(
      setOf(xyz.block.trailblaze.devices.TrailblazeDevicePlatform.ANDROID),
      cmd.supportedPlatformsForTrail(yaml),
    )
  }
}
