package xyz.block.trailblaze.util

@Deprecated("Prefer [xyz.block.trailblaze.devices.TrailblazeConnectedDeviceSummary] going forward")
data class AdbDevice(
  val id: String,
  val name: String,
)
