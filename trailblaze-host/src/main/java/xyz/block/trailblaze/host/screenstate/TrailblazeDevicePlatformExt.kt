package xyz.block.trailblaze.host.screenstate

import xyz.block.trailblaze.devices.TrailblazeDevicePlatform

fun maestro.device.Platform.toTrailblazeDevicePlatform(): TrailblazeDevicePlatform = when (this) {
  maestro.device.Platform.ANDROID -> TrailblazeDevicePlatform.ANDROID
  maestro.device.Platform.IOS -> TrailblazeDevicePlatform.IOS
  maestro.device.Platform.WEB -> TrailblazeDevicePlatform.WEB
}

fun maestro.Platform.toTrailblazeDevicePlatform(): TrailblazeDevicePlatform = when (this) {
  maestro.Platform.ANDROID -> TrailblazeDevicePlatform.ANDROID
  maestro.Platform.IOS -> TrailblazeDevicePlatform.IOS
  maestro.Platform.WEB -> TrailblazeDevicePlatform.WEB
}
