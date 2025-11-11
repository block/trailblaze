package xyz.block.trailblaze.devices

enum class TrailblazeDriverType(
  val platform: TrailblazeDevicePlatform,
  val isHost: Boolean,
) {
  ANDROID_ONDEVICE_ACCESSIBILITY(
    platform = TrailblazeDevicePlatform.ANDROID,
    isHost = false,
  ),
  ANDROID_ONDEVICE_INSTRUMENTATION(
    platform = TrailblazeDevicePlatform.ANDROID,
    isHost = false,
  ),
  ANDROID_HOST(
    platform = TrailblazeDevicePlatform.ANDROID,
    isHost = true,
  ),
  IOS_HOST(
    platform = TrailblazeDevicePlatform.IOS,
    isHost = true,
  ),
  WEB_PLAYWRIGHT_HOST(
    platform = TrailblazeDevicePlatform.WEB,
    isHost = true,
  ),
  ;

  companion object {
    val ANDROID_ON_DEVICE_DRIVER_TYPES = setOf(
      ANDROID_ONDEVICE_INSTRUMENTATION,
      ANDROID_ONDEVICE_ACCESSIBILITY,
    )
  }
}
