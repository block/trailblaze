package xyz.block.trailblaze.host.ios

import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.util.AndroidHostAdbUtils

object MobileDeviceUtils {
  fun getInstalledAppIds(trailblazeDeviceId: TrailblazeDeviceId): Set<String> {
    return when (trailblazeDeviceId.trailblazeDevicePlatform) {
      TrailblazeDevicePlatform.ANDROID -> AndroidHostAdbUtils.listInstalledPackages(
        deviceId = trailblazeDeviceId
      )

      TrailblazeDevicePlatform.IOS -> IosHostUtils.getInstalledAppIds(
        deviceId = trailblazeDeviceId.instanceId
      )

      TrailblazeDevicePlatform.WEB -> emptyList()
    }.toSet()
  }

  fun ensureAppsAreForceStopped(
    possibleAppIds: Set<String>,
    trailblazeDeviceId: TrailblazeDeviceId,
  ) {
    val installedAppIds = getInstalledAppIds(trailblazeDeviceId)
    when (trailblazeDeviceId.trailblazeDevicePlatform) {
      TrailblazeDevicePlatform.ANDROID -> {
        installedAppIds
          .filter { installedAppId -> possibleAppIds.any { installedAppId == it } }
          .forEach { appId ->
            AndroidHostAdbUtils.forceStopApp(
              deviceId = trailblazeDeviceId,
              appId = appId,
            )
          }
      }

      TrailblazeDevicePlatform.IOS -> {
        possibleAppIds.forEach { appId ->
          IosHostUtils.killAppOnSimulator(
            deviceId = trailblazeDeviceId.instanceId,
            appId = appId,
          )
        }
      }

      TrailblazeDevicePlatform.WEB -> {
        // Currently nothing to do here
      }
    }
  }
}
