package xyz.block.trailblaze.mcp

import xyz.block.trailblaze.api.ScreenState
import xyz.block.trailblaze.devices.TrailblazeConnectedDeviceSummary
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.model.TrailblazeHostAppTarget
import xyz.block.trailblaze.toolcalls.TrailblazeTool

/**
 * Bridges functions between the Trailblaze Device Manager and the MCP Server
 */
interface TrailblazeMcpBridge {
  suspend fun selectDevice(trailblazeDeviceId: TrailblazeDeviceId): TrailblazeConnectedDeviceSummary
  suspend fun getAvailableDevices(): Set<TrailblazeConnectedDeviceSummary>
  suspend fun getInstalledAppIds(): Set<String>
  fun getAvailableAppTargets(): Set<TrailblazeHostAppTarget>
  suspend fun runYaml(yaml: String, startNewSession: Boolean)

  /**
   * Allows us to see the "connected device" from the viewpoint of the MCP server.
   */
  fun getCurrentlySelectedDeviceId(): TrailblazeDeviceId?

  suspend fun getCurrentScreenState(): ScreenState?

  /**
   * Executes a TrailblazeTool directly on the connected device.
   * This enables MCP clients to act as the agent, calling low-level device control tools.
   *
   * @param tool The TrailblazeTool to execute (e.g., TapOnPointTrailblazeTool, SwipeTrailblazeTool)
   * @return Result string describing the execution outcome
   */
  suspend fun executeTrailblazeTool(tool: TrailblazeTool): String

  /**
   * Ends the current session on the selected device.
   * Clears the session state and writes a session end log.
   *
   * @return true if a session was ended, false if no session was active
   */
  suspend fun endSession(): Boolean
}
