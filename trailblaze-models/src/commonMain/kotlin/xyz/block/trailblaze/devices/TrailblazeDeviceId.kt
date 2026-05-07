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
  /** The value from `adb devices` or the simulator id */
  val instanceId: String,
  val trailblazeDevicePlatform: TrailblazeDevicePlatform,
) {
  /**
   * Canonical fully-qualified id for this device — `"<platform>/<instanceId>"` with a
   * lowercase platform, e.g. `"android/emulator-5554"`. Round-trips via
   * [TrailblazeDevicePlatform.fromString]. The matching format `--device` accepts on
   * the CLI.
   */
  fun toFullyQualifiedDeviceId(): String =
    trailblazeDevicePlatform.toFullyQualifiedDeviceId(instanceId)
}

