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
  // The Compose desktop driver doesn't go through Maestro; reaching this code path with
  // DESKTOP means a caller is mis-routing a Compose-driven device through the Maestro
  // dispatch. Fail loudly so the routing bug surfaces at the boundary instead of as
  // mysterious "no Maestro driver for this device" later.
  TrailblazeDevicePlatform.DESKTOP ->
    error("Cannot map TrailblazeDevicePlatform.DESKTOP to a Maestro platform — Compose desktop driver bypasses Maestro.")
}

fun TrailblazeDevicePlatform.toMaestroPlatform(): maestro.Platform = when (this) {
  TrailblazeDevicePlatform.ANDROID -> maestro.Platform.ANDROID
  TrailblazeDevicePlatform.IOS -> maestro.Platform.IOS
  TrailblazeDevicePlatform.WEB -> maestro.Platform.WEB
  TrailblazeDevicePlatform.DESKTOP ->
    error("Cannot map TrailblazeDevicePlatform.DESKTOP to a Maestro platform — Compose desktop driver bypasses Maestro.")
}

fun maestro.Platform.toTrailblazeDevicePlatform(): TrailblazeDevicePlatform = when (this) {
  maestro.Platform.ANDROID -> TrailblazeDevicePlatform.ANDROID
  maestro.Platform.IOS -> TrailblazeDevicePlatform.IOS
  maestro.Platform.WEB -> TrailblazeDevicePlatform.WEB
}
