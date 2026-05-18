package xyz.block.trailblaze.host.recording.rpc

import xyz.block.trailblaze.devices.TrailblazeConnectedDeviceSummary
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.devices.WebInstanceIds
import xyz.block.trailblaze.host.rpc.GetConnectedDevicesRequest
import xyz.block.trailblaze.host.rpc.GetConnectedDevicesResponse
import xyz.block.trailblaze.mcp.RpcHandler
import xyz.block.trailblaze.mcp.android.ondevice.rpc.RpcResult
import xyz.block.trailblaze.ui.TrailblazeDeviceManager

/**
 * Returns the list of devices currently visible to the daemon — same filter rules as the
 * desktop recording tab dropdown and `trailblaze device list`.
 *
 * Filter: drop Revyl cloud devices (require revyl CLI) and hidden platforms (Compose
 * desktop self-driver). Always synthesizes a Playwright-native placeholder entry when no
 * running browser slot already matches that instance ID.
 *
 * Calls [TrailblazeDeviceManager.loadDevicesSuspend] before reading the state flow so
 * headless daemon callers (the web viewer, MCP, CLI) see a freshly-discovered device list
 * — without this, only the desktop UI's [LaunchedEffect] populates the flow and headless
 * surfaces would see an empty list until the UI happens to refresh.
 */
class GetConnectedDevicesHandler(
  private val deviceManager: TrailblazeDeviceManager,
) : RpcHandler<GetConnectedDevicesRequest, GetConnectedDevicesResponse> {

  override suspend fun handle(
    request: GetConnectedDevicesRequest,
  ): RpcResult<GetConnectedDevicesResponse> {
    deviceManager.loadDevicesSuspend()
    val deviceState = deviceManager.deviceStateFlow.value
    val filtered = deviceState.devices.values
      .map { it.device }
      .filter {
        it.trailblazeDriverType != TrailblazeDriverType.REVYL_ANDROID &&
          it.trailblazeDriverType != TrailblazeDriverType.REVYL_IOS &&
          !it.platform.hidden
      }

    val seen = filtered.map { it.instanceId to it.platform }.toMutableSet()
    val withRunningBrowsers = filtered + deviceManager.webBrowserManager.getAllRunningBrowserSummaries()
      .filter { (it.instanceId to it.platform) !in seen }
      .also { added -> added.forEach { seen += it.instanceId to it.platform } }

    val devices: List<TrailblazeConnectedDeviceSummary> =
      if (withRunningBrowsers.none { it.instanceId == WebInstanceIds.PLAYWRIGHT_NATIVE }) {
        withRunningBrowsers + TrailblazeConnectedDeviceSummary(
          trailblazeDriverType = TrailblazeDriverType.PLAYWRIGHT_NATIVE,
          instanceId = WebInstanceIds.PLAYWRIGHT_NATIVE,
          description = "Playwright Browser (Native)",
        )
      } else {
        withRunningBrowsers
      }

    return RpcResult.Success(GetConnectedDevicesResponse(devices = devices))
  }
}
