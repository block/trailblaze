package xyz.block.trailblaze.ui.devices

import xyz.block.trailblaze.devices.TrailblazeConnectedDeviceSummary
import xyz.block.trailblaze.logs.model.SessionId

/**
 * State for a single device.
 * Contains the device info and current session ID (if running).
 * Cancellation is handled forcefully by killing the driver and coroutine job.
 */
data class DeviceState(
  val device: TrailblazeConnectedDeviceSummary,
  val currentSessionId: SessionId? = null
)
