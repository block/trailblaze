package xyz.block.trailblaze.host.recording.rpc

import xyz.block.trailblaze.devices.TrailblazeConnectedDeviceSummary
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.devices.WebInstanceIds
import xyz.block.trailblaze.host.recording.DeviceConnectionService
import xyz.block.trailblaze.host.rpc.ConnectToDeviceRequest
import xyz.block.trailblaze.host.rpc.ConnectToDeviceResponse
import xyz.block.trailblaze.mcp.RpcHandler
import xyz.block.trailblaze.mcp.android.ondevice.rpc.RpcResult
import xyz.block.trailblaze.ui.TrailblazeDeviceManager
import xyz.block.trailblaze.ui.recording.ConnectionState

/**
 * Establishes a live device connection on behalf of the web recording UI and registers
 * the resulting [xyz.block.trailblaze.recording.DeviceScreenStream] in
 * [HostDeviceSessionManager] for subsequent screen-poll and interaction calls.
 *
 * Idempotent: if the device is already connected, returns the existing dimensions
 * without re-running the bootstrap sequence.
 *
 * Failure modes (returned as [RpcResult.Failure] → HTTP 5xx):
 *  - Device id not found in any source.
 *  - Underlying [DeviceConnectionService.connectToDevice] errored (e.g. instrumentation
 *    install failed, target app not selected).
 */
class ConnectToDeviceHandler(
  private val deviceManager: TrailblazeDeviceManager,
  private val connectionService: DeviceConnectionService,
  private val sessionManager: HostDeviceSessionManager,
) : RpcHandler<ConnectToDeviceRequest, ConnectToDeviceResponse> {

  override suspend fun handle(
    request: ConnectToDeviceRequest,
  ): RpcResult<ConnectToDeviceResponse> {
    val deviceId: TrailblazeDeviceId = request.trailblazeDeviceId

    val device = deviceManager.deviceStateFlow.value.devices[deviceId]?.device
      ?: deviceManager.webBrowserManager.getAllRunningBrowserSummaries()
        .firstOrNull { it.trailblazeDeviceId == deviceId }
      // GetConnectedDevicesHandler synthesizes a PLAYWRIGHT_NATIVE placeholder that exists
      // in neither deviceStateFlow nor the running-browser list — reconstruct it directly.
      ?: if (deviceId.instanceId == WebInstanceIds.PLAYWRIGHT_NATIVE) {
        TrailblazeConnectedDeviceSummary(
          trailblazeDriverType = TrailblazeDriverType.PLAYWRIGHT_NATIVE,
          instanceId = WebInstanceIds.PLAYWRIGHT_NATIVE,
          description = "Playwright Browser (Native)",
        )
      } else {
        null
      }
      ?: return RpcResult.Failure(
        errorType = RpcResult.ErrorType.HTTP_ERROR,
        message = "Device not found: ${deviceId.toFullyQualifiedDeviceId()}",
      )

    var connectErrorMessage: String? = null
    val stream = sessionManager.connectIfAbsent(deviceId) {
      when (val state = connectionService.connectToDevice(device)) {
        is ConnectionState.Connected -> state.connection.stream
        is ConnectionState.Error -> {
          connectErrorMessage = state.message
          null
        }
        else -> null
      }
    } ?: return RpcResult.Failure(
      errorType = RpcResult.ErrorType.HTTP_ERROR,
      message = connectErrorMessage ?: "Failed to connect to ${deviceId.toFullyQualifiedDeviceId()}",
    )

    return RpcResult.Success(
      ConnectToDeviceResponse(
        deviceWidth = stream.deviceWidth,
        deviceHeight = stream.deviceHeight,
      ),
    )
  }
}
