package xyz.block.trailblaze.host

import maestro.device.Platform
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform

fun Platform.toTrailblazeDevicePlatform(): TrailblazeDevicePlatform = when (this) {
  Platform.ANDROID -> TrailblazeDevicePlatform.ANDROID
  Platform.IOS -> TrailblazeDevicePlatform.IOS
  Platform.WEB -> TrailblazeDevicePlatform.WEB
}

fun TrailblazeDevicePlatform.toMaestroDevicePlatform(): Platform = when (this) {
  TrailblazeDevicePlatform.ANDROID -> Platform.ANDROID
  TrailblazeDevicePlatform.IOS -> Platform.IOS
  TrailblazeDevicePlatform.WEB -> Platform.WEB
}

fun TrailblazeDevicePlatform.toMaestroPlatform(): maestro.Platform = when (this) {
  TrailblazeDevicePlatform.ANDROID -> maestro.Platform.ANDROID
  TrailblazeDevicePlatform.IOS -> maestro.Platform.IOS
  TrailblazeDevicePlatform.WEB -> maestro.Platform.WEB
}

fun maestro.Platform.toTrailblazeDevicePlatform(): TrailblazeDevicePlatform = when (this) {
  maestro.Platform.ANDROID -> TrailblazeDevicePlatform.ANDROID
  maestro.Platform.IOS -> TrailblazeDevicePlatform.IOS
  maestro.Platform.WEB -> TrailblazeDevicePlatform.WEB
}
