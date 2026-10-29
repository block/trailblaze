package xyz.block.trailblaze.host.recording.rpc

import xyz.block.trailblaze.devices.TrailblazeConnectedDeviceSummary
import xyz.block.trailblaze.host.driver.BrowsableDeviceListing
import xyz.block.trailblaze.host.rpc.GetConnectedDevicesRequest
import xyz.block.trailblaze.host.rpc.GetConnectedDevicesResponse
import xyz.block.trailblaze.mcp.RpcHandler
import xyz.block.trailblaze.mcp.android.ondevice.rpc.RpcResult
import xyz.block.trailblaze.ui.TrailblazeDeviceManager

/**
 * Returns the list of devices currently visible to the daemon — same filter rules as the
 * desktop recording tab dropdown and `trailblaze device list`.
 *
 * Filter rules live in [BrowsableDeviceListing], shared with the other two listing surfaces.
 *
 * Calls [TrailblazeDeviceManager.loadDevicesSuspend] before reading the state flow so
 * headless daemon callers (the web viewer, MCP, CLI) see a freshly-discovered device list
 * — without this, nothing else populates the flow and they would see an empty list.
 */
class GetConnectedDevicesHandler(
  private val deviceManager: TrailblazeDeviceManager,
) : RpcHandler<GetConnectedDevicesRequest, GetConnectedDevicesResponse> {

  override suspend fun handle(
    request: GetConnectedDevicesRequest,
  ): RpcResult<GetConnectedDevicesResponse> {
    deviceManager.loadDevicesSuspend()
    val deviceState = deviceManager.deviceStateFlow.value
    val devices: List<TrailblazeConnectedDeviceSummary> = BrowsableDeviceListing.filter(
      devices = deviceState.devices.values.map { it.device },
      descriptors = deviceManager.hostDriverDescriptors,
      runningWebBrowsers = deviceManager.webBrowserManager.getAllRunningBrowserSummaries(),
    )

    return RpcResult.Success(GetConnectedDevicesResponse(devices = devices))
  }
}
