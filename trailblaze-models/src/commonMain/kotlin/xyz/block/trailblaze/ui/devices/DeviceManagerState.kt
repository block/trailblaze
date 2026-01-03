package xyz.block.trailblaze.ui.devices

import xyz.block.trailblaze.devices.TrailblazeDeviceId

/**
 * State managed by TrailblazeDeviceManager.
 * Contains state for all devices and manager-level status.
 * Note: Active session tracking (deviceId -> sessionId) is handled separately
 * via TrailblazeDeviceManager.activeDeviceSessionsFlow.
 */
data class DeviceManagerState(
  val devices: Map<TrailblazeDeviceId, DeviceState> = emptyMap(),
  val isLoading: Boolean = false,
  val error: String? = null
)
