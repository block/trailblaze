package xyz.block.trailblaze.devices

enum class TrailblazeDriverType(val platform: TrailblazeDevicePlatform) {
  ANDROID_ONDEVICE_ACCESSIBILITY(TrailblazeDevicePlatform.ANDROID),
  ANDROID_ONDEVICE_INSTRUMENTATION(TrailblazeDevicePlatform.ANDROID),
  ANDROID_HOST(TrailblazeDevicePlatform.ANDROID),
  IOS_HOST(TrailblazeDevicePlatform.IOS),
  WEB_PLAYWRIGHT_HOST(TrailblazeDevicePlatform.WEB),
}
