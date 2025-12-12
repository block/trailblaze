package xyz.block.trailblaze.devices

import kotlinx.serialization.Serializable

@Serializable
data class TrailblazeDeviceInfo(
  val trailblazeDriverType: TrailblazeDriverType,
  val widthPixels: Int,
  val heightPixels: Int,
  val metadata: Map<String, String> = emptyMap(),
  val locale: String? = null,
  val classifiers: List<TrailblazeDeviceClassifier> = emptyList(),
  val orientation: TrailblazeDeviceOrientation? = null,
) {
  val platform = trailblazeDriverType.platform
}
