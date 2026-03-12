package xyz.block.trailblaze.devices

enum class TrailblazeDeviceOrientation {
  PORTRAIT,
  LANDSCAPE,
  ;

  /**
   * Returns a [TrailblazeDeviceClassifier] for this orientation, or null for portrait
   * (portrait is the default and doesn't need a classifier).
   */
  fun asDeviceClassifierOrNull(): TrailblazeDeviceClassifier? = when (this) {
    LANDSCAPE -> TrailblazeDeviceClassifier("landscape")
    PORTRAIT -> null
  }
}
