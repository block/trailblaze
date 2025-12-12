package xyz.block.trailblaze.devices

interface TrailblazeDeviceClassifiersProvider {
  fun getDeviceClassifiers(): List<TrailblazeDeviceClassifier>
}
