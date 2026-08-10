package xyz.block.trailblaze.host.yaml

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import xyz.block.trailblaze.devices.TrailblazeConnectedDeviceSummary
import org.junit.Test
import xyz.block.trailblaze.cli.CliRunDriverResolution
import xyz.block.trailblaze.devices.TrailblazeDeviceClassifier
import xyz.block.trailblaze.devices.TrailblazeDriverType

/**
 * Guards the runner-side read of a trail's driver pin
 * ([DesktopYamlRunner.trailPinnedDriverResolution]).
 *
 * The daemon's `/cli/run` handler and the desktop Run path extract trail config without a device,
 * so a unified trail's per-classifier `devices:` pin arrives at the runner as
 * `RunYamlRequest.driverType = null`. The runner must resolve the pin itself against the connected
 * device's classifiers — when this regressed, the android-instrumentation CLI smoke trails
 * (pinning `ANDROID_ONDEVICE_INSTRUMENTATION`) silently ran on the accessibility driver.
 *
 * A pin naming an unknown driver must resolve to [CliRunDriverResolution.Unrecognized] — never
 * null — so callers fail loud instead of silently falling back to the default driver.
 */
class DesktopYamlRunnerDriverPinTest {

  private val androidPhone = listOf(
    TrailblazeDeviceClassifier("android"),
    TrailblazeDeviceClassifier("phone"),
  )

  private fun resolvedDriverType(yaml: String): TrailblazeDriverType? {
    val resolution = DesktopYamlRunner.trailPinnedDriverResolution(yaml, androidPhone)
    assertTrue(
      "expected Resolved but was $resolution",
      resolution is CliRunDriverResolution.Resolved,
    )
    return (resolution as CliRunDriverResolution.Resolved).driverType
  }

  @Test
  fun `unified devices pin resolves for a matching device`() {
    val yaml = """
      config:
        devices:
          android: ANDROID_ONDEVICE_INSTRUMENTATION
      trail:
        - step: "Open the Lists tab"
    """.trimIndent()

    assertEquals(
      TrailblazeDriverType.ANDROID_ONDEVICE_INSTRUMENTATION,
      resolvedDriverType(yaml),
    )
  }

  @Test
  fun `unified devices pin is closest-wins per classifier`() {
    val yaml = """
      config:
        devices:
          android: ANDROID_ONDEVICE_ACCESSIBILITY
          android-phone: ANDROID_ONDEVICE_INSTRUMENTATION
      trail:
        - step: "Open the Lists tab"
    """.trimIndent()

    assertEquals(
      TrailblazeDriverType.ANDROID_ONDEVICE_INSTRUMENTATION,
      resolvedDriverType(yaml),
    )
  }

  @Test
  fun `unified pin for another platform resolves to null`() {
    val yaml = """
      config:
        devices:
          ios: IOS_HOST
      trail:
        - step: "Open the Lists tab"
    """.trimIndent()

    assertNull(resolvedDriverType(yaml))
  }

  @Test
  fun `trail with no pin resolves to null`() {
    val yaml = """
      trail:
        - step: "Open the Lists tab"
    """.trimIndent()

    assertNull(resolvedDriverType(yaml))
  }

  @Test
  fun `unparseable yaml resolves to null instead of throwing`() {
    assertNull(resolvedDriverType("config: [not, a, trail"))
  }

  @Test
  fun `unrecognized unified pin is rejected loud, naming the bad value and the valid drivers`() {
    val yaml = """
      config:
        devices:
          android: ANDROID_TYPO_DRIVER
      trail:
        - step: "Open the Lists tab"
    """.trimIndent()

    val resolution = DesktopYamlRunner.trailPinnedDriverResolution(yaml, androidPhone)
    assertTrue(
      "expected Unrecognized but was $resolution",
      resolution is CliRunDriverResolution.Unrecognized,
    )
    val message = (resolution as CliRunDriverResolution.Unrecognized).message
    assertTrue(
      "message should name the bad value: $message",
      message.contains("'ANDROID_TYPO_DRIVER'"),
    )
    for (driver in TrailblazeDriverType.entries) {
      assertTrue(
        "message should list valid driver ${driver.name}: $message",
        message.contains(driver.name),
      )
    }
  }

  @Test
  fun `unrecognized pin for another platform resolves to null for this device`() {
    // The pin is bad, but it is not reachable from this device's classifier chain — the trail
    // never runs on this driver decision, so it must not fail this device's run.
    val yaml = """
      config:
        devices:
          ios: IOS_TYPO_DRIVER
      trail:
        - step: "Open the Lists tab"
    """.trimIndent()

    assertNull(resolvedDriverType(yaml))
  }

  /**
   * The host runner picks its driver from the device summary's `trailblazeDriverType`, so the
   * runner hands the host branches a device tagged with the resolved pin. That swap is only safe
   * because a pin resolves to a driver on the SAME platform — this guards that invariant (a pin
   * of iOS Axe over the simulator's default IOS_HOST keeps the device's identity intact).
   */
  @Test
  fun `retagging a device with a same-platform pin preserves its identity`() {
    val simulator = TrailblazeConnectedDeviceSummary(
      trailblazeDriverType = TrailblazeDriverType.IOS_HOST,
      instanceId = "SIM-UDID-1234",
      description = "iPhone 15 simulator",
    )

    val retagged = simulator.copy(trailblazeDriverType = TrailblazeDriverType.IOS_AXE)

    assertEquals(TrailblazeDriverType.IOS_AXE, retagged.trailblazeDriverType)
    assertEquals(simulator.platform, retagged.platform)
    assertEquals(simulator.trailblazeDeviceId, retagged.trailblazeDeviceId)
  }
}
