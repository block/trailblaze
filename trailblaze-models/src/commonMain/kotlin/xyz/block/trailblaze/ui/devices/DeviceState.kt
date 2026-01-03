package xyz.block.trailblaze.ui.devices

import kotlinx.serialization.Serializable
import xyz.block.trailblaze.devices.TrailblazeConnectedDeviceSummary

/**
 * State for a single device.
 * Contains the device info (summary).
 * Session tracking is handled separately via DeviceManagerState.activeDeviceSessions.
 * Cancellation is handled forcefully by killing the driver and coroutine job.
 */
@Serializable
data class DeviceState(
  val device: TrailblazeConnectedDeviceSummary
)
