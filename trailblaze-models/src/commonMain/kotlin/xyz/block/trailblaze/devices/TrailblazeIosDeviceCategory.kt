package xyz.block.trailblaze.devices

enum class TrailblazeIosDeviceCategory(val classifier: String) {
  IPHONE("iphone"),
  IPAD("ipad"),
  ;

  fun asTrailblazeDeviceClassifier(): TrailblazeDeviceClassifier = TrailblazeDeviceClassifier(classifier)
}
