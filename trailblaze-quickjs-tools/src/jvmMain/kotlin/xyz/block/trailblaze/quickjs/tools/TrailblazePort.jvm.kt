package xyz.block.trailblaze.quickjs.tools

import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.devices.TrailblazeDevicePort.getTrailblazeOnDeviceSpecificPort

internal actual fun trailblazePortOrNull(trailblazeDeviceId: TrailblazeDeviceId): Int? =
  when (trailblazeDeviceId.trailblazeDevicePlatform) {
    TrailblazeDevicePlatform.ANDROID,
    TrailblazeDevicePlatform.IOS -> trailblazeDeviceId.getTrailblazeOnDeviceSpecificPort()
    else -> null
  }
