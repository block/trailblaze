package xyz.block.trailblaze.host.devices

import maestro.DeviceInfo
import xyz.block.trailblaze.devices.TrailblazeAndroidDeviceCategory
import xyz.block.trailblaze.devices.TrailblazeDeviceClassifier
import xyz.block.trailblaze.devices.TrailblazeDeviceClassifiersProvider
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.devices.TrailblazeIosDeviceCategory

class TrailblazeHostDeviceClassifier(
  private val trailblazeDriverType: TrailblazeDriverType,
  private val maestroDeviceInfoProvider: () -> DeviceInfo,
) : TrailblazeDeviceClassifiersProvider {

  private val trailblazeDeviceClassifiers: List<TrailblazeDeviceClassifier> by lazy {
    // Heuristic thresholds:
    // - Phones in portrait: shortest side ~ 640–1242 px
    // - Tablets in portrait: shortest side ≥ ~1536 px
    val initialMaestroDeviceInfo = maestroDeviceInfoProvider()
    val minPx = minOf(initialMaestroDeviceInfo.widthPixels, initialMaestroDeviceInfo.heightPixels)
    val isTablet = minPx >= 1536

    buildList {
      val platform = trailblazeDriverType.platform
      add(platform.asTrailblazeDeviceClassifier())
      when (platform) {
        TrailblazeDevicePlatform.IOS -> {
          add(
            when (isTablet) {
              true -> TrailblazeIosDeviceCategory.IPAD.asTrailblazeDeviceClassifier()
              false -> TrailblazeIosDeviceCategory.IPHONE.asTrailblazeDeviceClassifier()
            },
          )
        }

        TrailblazeDevicePlatform.ANDROID -> {
          add(
            when (isTablet) {
              true -> TrailblazeAndroidDeviceCategory.TABLET.asTrailblazeDeviceClassifier()
              false -> TrailblazeAndroidDeviceCategory.PHONE.asTrailblazeDeviceClassifier()
            },
          )
        }

        else -> {
          // Other platforms not supported at this point
        }
      }
    }
  }

  override fun getDeviceClassifiers(): List<TrailblazeDeviceClassifier> = trailblazeDeviceClassifiers
}
