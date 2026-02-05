package xyz.block.trailblaze.host.ios

import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.model.AppVersionInfo
import xyz.block.trailblaze.util.AndroidHostAdbUtils

object MobileDeviceUtils {
  /**
   * Gets version information for an installed app on the specified device.
   *
   * @param trailblazeDeviceId The device to query
   * @param appId The app package name (Android) or bundle identifier (iOS)
   * @return AppVersionInfo with version details, or null if not installed or unsupported platform
   */
  fun getAppVersionInfo(trailblazeDeviceId: TrailblazeDeviceId, appId: String): AppVersionInfo? {
    return when (trailblazeDeviceId.trailblazeDevicePlatform) {
      TrailblazeDevicePlatform.ANDROID -> AndroidHostAdbUtils.getAppVersionInfo(
        deviceId = trailblazeDeviceId,
        packageName = appId,
      )

      TrailblazeDevicePlatform.IOS -> IosHostUtils.getAppVersionInfo(
        trailblazeDeviceId = trailblazeDeviceId,
        appId = appId,
      )

      TrailblazeDevicePlatform.WEB -> null
    }
  }

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
