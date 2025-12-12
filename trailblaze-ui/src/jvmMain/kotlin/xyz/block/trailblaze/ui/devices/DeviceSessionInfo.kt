package xyz.block.trailblaze.ui.devices

import xyz.block.trailblaze.session.TrailblazeSessionManager

/**
 * Information about an active session on a device
 */
data class DeviceSessionInfo(
  val sessionId: String,
  val deviceInstanceId: String,
  val startTimeMs: Long = System.currentTimeMillis(),
  val sessionManager: TrailblazeSessionManager
)