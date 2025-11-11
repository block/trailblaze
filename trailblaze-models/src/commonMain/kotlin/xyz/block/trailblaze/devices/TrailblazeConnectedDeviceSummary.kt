package xyz.block.trailblaze.devices

import kotlinx.serialization.Serializable
import xyz.block.trailblaze.model.TrailblazeHostAppTarget

@Serializable
data class TrailblazeConnectedDeviceSummary(
  val trailblazeDriverType: TrailblazeDriverType,
  val instanceId: String,
  val description: String,
  val installedAppIds: Set<String>,
) {
  val platform: TrailblazeDevicePlatform = trailblazeDriverType.platform

  fun getAppIdIfInstalled(hostAppTarget: TrailblazeHostAppTarget?): String? {
    val installedAppId = hostAppTarget?.getPossibleAppIdsForPlatform(platform)?.let { expectedAppIds ->
      expectedAppIds.firstOrNull { expectedAppId ->
        installedAppIds.contains(expectedAppId)
      }
    }
    return installedAppId
  }
}
