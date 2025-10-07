package xyz.block.trailblaze.devices

import kotlinx.serialization.Serializable

@Serializable
data class TrailblazeConnectedDeviceSummary(
  val trailblazeDriverType: TrailblazeDriverType,
  val instanceId: String,
  val description: String,
) {
  val platform: TrailblazeDevicePlatform = trailblazeDriverType.platform
}
