package xyz.block.trailblaze.ui.devices

import xyz.block.trailblaze.devices.TrailblazeConnectedDeviceSummary
import xyz.block.trailblaze.session.TrailblazeSessionManager

data class DeviceState(
  val availableDevices: List<TrailblazeConnectedDeviceSummary> = emptyList(),
  val selectedDevices: Set<TrailblazeConnectedDeviceSummary> = emptySet(),
  val isLoading: Boolean = false,
  val error: String? = null,
    // Map of device instance ID to session manager
  val sessionManagersByDevice: Map<String, TrailblazeSessionManager> = emptyMap(),
    // Map of device instance ID to active session info
  val activeSessionsByDevice: Map<String, DeviceSessionInfo> = emptyMap(),
  )