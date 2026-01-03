package xyz.block.trailblaze.devices

import kotlinx.serialization.Serializable
import xyz.block.trailblaze.model.TrailblazeHostAppTarget

@Serializable
data class TrailblazeConnectedDeviceSummary(
  val trailblazeDriverType: TrailblazeDriverType,
  val instanceId: String,
  val description: String,
) {
  val platform: TrailblazeDevicePlatform = trailblazeDriverType.platform

  val trailblazeDeviceId = TrailblazeDeviceId(
    instanceId = instanceId,
    trailblazeDevicePlatform = platform
  )
}
