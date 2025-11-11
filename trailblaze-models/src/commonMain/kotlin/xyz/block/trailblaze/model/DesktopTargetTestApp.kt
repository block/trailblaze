package xyz.block.trailblaze.model

import xyz.block.trailblaze.devices.TrailblazeDevicePlatform

abstract class DesktopTargetTestApp(
  /** Display name for the app */
  val appName: String,
) {

  /** Maps platform to the id of the app on that platform */
  abstract fun getAppIdForPlatform(platform: TrailblazeDevicePlatform): String?
}
