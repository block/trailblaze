package xyz.block.trailblaze.ui.devices

import kotlinx.serialization.Serializable

// Data class for parsing the device status response
@Serializable
data class DeviceStatusResponse(
  val sessionId: String?,
  val isRunning: Boolean
)

