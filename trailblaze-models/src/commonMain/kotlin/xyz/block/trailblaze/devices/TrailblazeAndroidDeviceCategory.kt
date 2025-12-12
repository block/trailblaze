package xyz.block.trailblaze.devices

enum class TrailblazeAndroidDeviceCategory(val classifier: String) {
  PHONE("phone"),
  TABLET("tablet"),
  ;

  fun asTrailblazeDeviceClassifier(): TrailblazeDeviceClassifier = TrailblazeDeviceClassifier(classifier)
}
