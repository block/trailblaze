package xyz.block.trailblaze.host.devices

import maestro.DeviceInfo
import xyz.block.trailblaze.devices.TrailblazeAndroidDeviceCategory
import xyz.block.trailblaze.devices.TrailblazeDeviceClassifiersProvider
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.devices.TrailblazeIosDeviceCategory

class TrailblazeHostDeviceClassifier(
  private val trailblazeDriverType: TrailblazeDriverType,
  private val maestroDeviceInfoProvider: () -> DeviceInfo,
) : TrailblazeDeviceClassifiersProvider {
  override fun getDeviceClassifiers(): List<String> {
    // Heuristic thresholds:
    // - Phones in portrait: shortest side ~ 640–1242 px
    // - Tablets in portrait: shortest side ≥ ~1536 px
    val initialMaestroDeviceInfo = maestroDeviceInfoProvider()
    val minPx = minOf(initialMaestroDeviceInfo.widthPixels, initialMaestroDeviceInfo.heightPixels)
    val isTablet = minPx >= 1536

    return buildList {
      val platform = trailblazeDriverType.platform
      if (platform == TrailblazeDevicePlatform.IOS) {
        add(
          when (isTablet) {
            true -> TrailblazeIosDeviceCategory.IPAD
            false -> TrailblazeIosDeviceCategory.IPHONE
          }.classifier,
        )
      } else if (platform == TrailblazeDevicePlatform.ANDROID) {
        add(
          when (isTablet) {
            true -> TrailblazeAndroidDeviceCategory.TABLET
            false -> TrailblazeAndroidDeviceCategory.PHONE
          }.classifier,
        )
      }
    }
  }
}
