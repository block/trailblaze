package xyz.block.trailblaze.host.devices

import maestro.DeviceInfo
import maestro.device.Platform
import xyz.block.trailblaze.devices.TrailblazeDeviceClassifier
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.devices.TrailblazeDriverType
import org.junit.Test
import kotlin.test.assertEquals

/**
 * Covers the synthetic [DeviceInfo] [BaseHostTrailblazeTest.trailblazeDeviceClassifiers][
 * xyz.block.trailblaze.host.rules.BaseHostTrailblazeTest] builds for a non-Maestro-backed
 * connected device (e.g. [xyz.block.trailblaze.host.devices.AxeConnectedDevice] on the IOS_AXE
 * driver) — a raw width/height with no live Maestro driver to ask. [TrailblazeHostDeviceClassifier]
 * itself is the testable seam here: it takes a plain `() -> DeviceInfo` provider, so this test
 * exercises the exact classification BaseHostTrailblazeTest's fallback would produce, without
 * needing a real device or simulator.
 */
class TrailblazeHostDeviceClassifierAxeFallbackTest {

  private fun classifiersForPointBounds(width: Int, height: Int): List<TrailblazeDeviceClassifier> =
    TrailblazeHostDeviceClassifier(
      trailblazeDriverType = TrailblazeDriverType.IOS_AXE,
      maestroDeviceInfoProvider = {
        DeviceInfo(
          platform = Platform.IOS,
          widthPixels = width,
          heightPixels = height,
          widthGrid = width,
          heightGrid = height,
        )
      },
      iosDimensionsInPoints = true,
    ).getDeviceClassifiers()

  @Test
  fun `a 393x852-point iPhone-shaped DeviceInfo classifies as ios, iphone`() {
    assertEquals(
      listOf(
        TrailblazeDevicePlatform.IOS.asTrailblazeDeviceClassifier(),
        TrailblazeDeviceClassifier("iphone"),
      ),
      classifiersForPointBounds(width = 393, height = 852),
    )
  }

  @Test
  fun `a 1024x1366-point iPad-shaped DeviceInfo classifies as ios, ipad`() {
    // 1024pt is below the 1536px pixel threshold — without the points flag this would
    // misclassify as iphone and decode classifier-specific recordings for the wrong form factor.
    assertEquals(
      listOf(
        TrailblazeDevicePlatform.IOS.asTrailblazeDeviceClassifier(),
        TrailblazeDeviceClassifier("ipad"),
      ),
      classifiersForPointBounds(width = 1024, height = 1366),
    )
  }
}
