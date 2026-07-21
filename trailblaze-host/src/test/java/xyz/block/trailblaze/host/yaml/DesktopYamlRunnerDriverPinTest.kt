package xyz.block.trailblaze.host.yaml

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import xyz.block.trailblaze.devices.TrailblazeConnectedDeviceSummary
import org.junit.Test
import xyz.block.trailblaze.devices.TrailblazeDeviceClassifier
import xyz.block.trailblaze.devices.TrailblazeDriverType

/**
 * Guards the runner-side read of a trail's driver pin ([DesktopYamlRunner.trailPinnedDriverType]).
 *
 * The daemon's `/cli/run` handler and the desktop Run path extract trail config without a device,
 * so a unified trail's per-classifier `devices:` pin arrives at the runner as
 * `RunYamlRequest.driverType = null`. The runner must resolve the pin itself against the connected
 * device's classifiers — when this regressed, the android-instrumentation CLI smoke trails
 * (pinning `ANDROID_ONDEVICE_INSTRUMENTATION`) silently ran on the accessibility driver.
 */
class DesktopYamlRunnerDriverPinTest {

  private val androidPhone = listOf(
    TrailblazeDeviceClassifier("android"),
    TrailblazeDeviceClassifier("phone"),
  )

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
      DesktopYamlRunner.trailPinnedDriverType(yaml, androidPhone),
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
      DesktopYamlRunner.trailPinnedDriverType(yaml, androidPhone),
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

    assertNull(DesktopYamlRunner.trailPinnedDriverType(yaml, androidPhone))
  }

  @Test
  fun `v1 driver scalar resolves regardless of classifiers`() {
    val yaml = """
      - config:
          driver: ANDROID_ONDEVICE_INSTRUMENTATION
      - prompts:
          - step: Open the Lists tab
    """.trimIndent()

    assertEquals(
      TrailblazeDriverType.ANDROID_ONDEVICE_INSTRUMENTATION,
      DesktopYamlRunner.trailPinnedDriverType(yaml, androidPhone),
    )
    assertEquals(
      TrailblazeDriverType.ANDROID_ONDEVICE_INSTRUMENTATION,
      DesktopYamlRunner.trailPinnedDriverType(yaml, emptyList()),
    )
  }

  @Test
  fun `trail with no pin resolves to null`() {
    val yaml = """
      trail:
        - step: "Open the Lists tab"
    """.trimIndent()

    assertNull(DesktopYamlRunner.trailPinnedDriverType(yaml, androidPhone))
  }

  @Test
  fun `unparseable yaml resolves to null instead of throwing`() {
    assertNull(DesktopYamlRunner.trailPinnedDriverType("config: [not, a, trail", androidPhone))
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
