package xyz.block.trailblaze.devices

/**
 * Represents a supported Platform
 */
enum class TrailblazeDevicePlatform(val displayName: String) {
  ANDROID("Android"),
  IOS("iOS"),
  WEB("Web Browser"),
  ;

  fun asTrailblazeDeviceClassifier(): TrailblazeDeviceClassifier = TrailblazeDeviceClassifier(this.name.lowercase())
}
