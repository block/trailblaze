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
 * Idempotent for the target it is already bound to: if the device is connected for the same target,
 * returns the existing dimensions without re-running the bootstrap sequence.
 *
 * Failure modes (returned as [RpcResult.Failure] → HTTP 5xx):
 *  - Device id not found in any source.
 *  - The named target app isn't registered in this daemon. Checked before the live connection is
 *    reused, so an unregistered target fails whether or not the device is already connected.
 *  - The device is connected for a DIFFERENT target. A connect installs and launches the bound
 *    app, so handing that connection back for another target would run one app while reporting
 *    the other; the caller has to release the device first, or stop the recording that owns it.
 *    Only for a device whose connect really binds the target - see `bindsTargetApp`.
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

    // Resolved here rather than inside the connect so the check runs on the reuse path too, and so
    // the session is recorded against a concrete target instead of the caller's request. The
    // resolution itself is then handed to the connect, not re-derived from an id: re-resolving
    // "nothing selected" would ask the daemon again and could bind an app selected in between.
    val bound = when (val resolved = connectionService.resolveBoundTarget(request.targetAppId)) {
      is DeviceConnectionService.BoundTarget.Unavailable -> return RpcResult.Failure(
        errorType = RpcResult.ErrorType.HTTP_ERROR,
        message = resolved.message,
      )
      is DeviceConnectionService.BoundTarget.Resolved -> resolved
    }
    // Only a connect that actually installs or launches the target can be bound to it; the Electron
    // and host-native iOS paths record nothing, so they'd otherwise refuse a second connect over a
    // difference that makes no difference to the stream they hand back. See `connectionBinding`.
    val binding = DeviceConnectionService.connectionBinding(
      platform = device.platform,
      driverType = device.trailblazeDriverType,
      target = bound.target,
    )

    var connectErrorMessage: String? = null
    val result = sessionManager.connectIfAbsent(deviceId, binding) {
      when (val state = connectionService.connectToDevice(device, bound)) {
        is ConnectionState.Connected -> state.connection.stream
        is ConnectionState.Error -> {
          connectErrorMessage = state.message
          null
        }
        else -> null
      }
    }

    return when (result) {
      is HostDeviceSessionManager.ConnectResult.Ready -> RpcResult.Success(
        ConnectToDeviceResponse(
          deviceWidth = result.stream.deviceWidth,
          deviceHeight = result.stream.deviceHeight,
        ),
      )
      is HostDeviceSessionManager.ConnectResult.BoundToOtherTarget -> RpcResult.Failure(
        errorType = RpcResult.ErrorType.HTTP_ERROR,
        // The whole message comes from the refusal, never assembled here: "disconnect it" only frees
        // a connection this registry owns, and a caller can be held by two owners at once, so both
        // the remedy and the number of holders are the registry's to state.
        message = result.explain(deviceId, binding, action = "connecting"),
      )
      HostDeviceSessionManager.ConnectResult.Unavailable -> RpcResult.Failure(
        errorType = RpcResult.ErrorType.HTTP_ERROR,
        message = connectErrorMessage ?: "Failed to connect to ${deviceId.toFullyQualifiedDeviceId()}",
      )
    }
  }
}
