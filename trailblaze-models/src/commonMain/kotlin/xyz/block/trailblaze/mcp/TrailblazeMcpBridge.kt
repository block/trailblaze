package xyz.block.trailblaze.mcp

import xyz.block.trailblaze.devices.TrailblazeConnectedDeviceSummary
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.model.TrailblazeHostAppTarget

/**
 * Bridges functions between the Trailblaze Device Manager and the MCP Server
 */
interface TrailblazeMcpBridge {
  suspend fun selectDevice(trailblazeDeviceId: TrailblazeDeviceId): TrailblazeConnectedDeviceSummary
  suspend fun getAvailableDevices(): Set<TrailblazeConnectedDeviceSummary>
  suspend fun getInstalledAppIds(): Set<String>
  fun getAvailableAppTargets(): Set<TrailblazeHostAppTarget>
  suspend fun runYaml(yaml: String)
}
