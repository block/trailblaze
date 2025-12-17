package xyz.block.trailblaze.devices

import kotlinx.serialization.Serializable

/**
 * Typesafe wrapper for an ID
 *
 * For Android it's the device name from "adb devices"
 * For iOS it's the simulator id
 */
@Serializable
data class TrailblazeDeviceId(
  val instanceId: String,
  val trailblazeDevicePlatform: TrailblazeDevicePlatform
)

