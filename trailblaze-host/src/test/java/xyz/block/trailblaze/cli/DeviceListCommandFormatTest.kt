package xyz.block.trailblaze.cli

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import xyz.block.trailblaze.devices.TrailblazeDeviceClassifier
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform

/**
 * Pins the user-facing device-type label format printed by `trailblaze device list`. The
 * label appears in parentheses next to each device line (e.g. `(ios-iphone)`,
 * `(android-phone)`, or a distribution-specific multi-segment label) and matches the
 * recording filename convention — `<label>.trail.yaml` — so a reader of `device list`
 * can predict which recording each device will resolve to without running anything.
 *
 * Tests stub `HostClassifierOverride` so the join logic runs against a known classifier
 * list without depending on a real device probe (xcrun simctl / adb).
 */
class DeviceListCommandFormatTest {

  @BeforeTest
  fun resetResolverState() {
    DeviceClassifierResolver.resetCacheForTesting()
    DeviceClassifierResolver.installOverride(null)
  }

  @AfterTest
  fun clearOverride() {
    DeviceClassifierResolver.installOverride(null)
  }

  @Test
  fun `formatDeviceType joins multi-segment classifiers with dash separator`() {
    DeviceClassifierResolver.installOverride { _, _ ->
      listOf(TrailblazeDeviceClassifier("android"), TrailblazeDeviceClassifier("phone"))
    }
    val label = DeviceListCommand.formatDeviceType(TrailblazeDevicePlatform.ANDROID, "any-id")
    assertEquals("android-phone", label)
  }

  @Test
  fun `formatDeviceType matches the recording filename convention for known device types`() {
    // Pins that the label produced here matches the `<platform>-<category>.trail.yaml`
    // filename the save path writes. If a future refactor swaps the separator (`-` → `/`
    // or `_`) the labels would silently diverge from disk and the recording-pick would
    // miss — exactly the bug this PR fixes for the underlying classifier list.
    val expectations = mapOf(
      "ios-iphone" to listOf(TrailblazeDeviceClassifier("ios"), TrailblazeDeviceClassifier("iphone")),
      "ios-ipad" to listOf(TrailblazeDeviceClassifier("ios"), TrailblazeDeviceClassifier("ipad")),
      "android-tablet" to listOf(TrailblazeDeviceClassifier("android"), TrailblazeDeviceClassifier("tablet")),
      // Generic distribution-override example — proves the join handles non-OSS-canonical
      // multi-segment classifier lists too. Distributions plug in their own naming.
      "acme-widget" to listOf(TrailblazeDeviceClassifier("acme"), TrailblazeDeviceClassifier("widget")),
    )
    for ((expectedLabel, classifiers) in expectations) {
      DeviceClassifierResolver.resetCacheForTesting()
      DeviceClassifierResolver.installOverride { _, _ -> classifiers }
      val label = DeviceListCommand.formatDeviceType(TrailblazeDevicePlatform.ANDROID, "any-id")
      assertEquals(expectedLabel, label)
    }
  }
}
