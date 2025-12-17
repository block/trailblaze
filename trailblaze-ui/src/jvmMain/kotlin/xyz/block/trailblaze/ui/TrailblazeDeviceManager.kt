package xyz.block.trailblaze.ui

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import maestro.device.Device
import maestro.device.DeviceService
import maestro.device.Platform
import xyz.block.trailblaze.devices.TrailblazeConnectedDeviceSummary
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.logs.client.TrailblazeJsonInstance
import xyz.block.trailblaze.mcp.android.ondevice.rpc.OnDeviceRpcClient
import xyz.block.trailblaze.mcp.android.ondevice.rpc.RpcResult
import xyz.block.trailblaze.mcp.android.ondevice.rpc.models.DeviceStatus.DeviceStatusRequest
import xyz.block.trailblaze.mcp.android.ondevice.rpc.models.DeviceStatus.DeviceStatusResponse
import xyz.block.trailblaze.model.TrailblazeHostAppTarget
import xyz.block.trailblaze.session.TrailblazeSessionManager
import xyz.block.trailblaze.ui.devices.DeviceSessionInfo
import xyz.block.trailblaze.ui.devices.DeviceState
import xyz.block.trailblaze.ui.models.AppIconProvider

/**
 * Manages device discovery, selection, and state across the application.
 * Can be shared across multiple Composables to maintain consistent device state.
 */
class TrailblazeDeviceManager(
  val settingsRepo: TrailblazeSettingsRepo,
  val defaultHostAppTarget: TrailblazeHostAppTarget,
  val availableAppTargets: Set<TrailblazeHostAppTarget>,
  val appIconProvider: AppIconProvider,
  val getInstalledAppIds: (TrailblazeDeviceId) -> Set<String>,
) {

  fun getAllSupportedDriverTypes() = settingsRepo.getAllSupportedDriverTypes()

  /**
   * Callback function that can be set by the host runner to actually cancel the running job.
   * This is set by TrailblazeHostYamlRunner when it starts.
   */
  var cancelSessionCallback: ((deviceInstanceId: TrailblazeDeviceId) -> Boolean)? = null

  fun getCurrentSelectedTargetApp(): TrailblazeHostAppTarget? = availableAppTargets
    .filter { it != defaultHostAppTarget }
    .firstOrNull { appTarget ->
      appTarget.name == settingsRepo.serverStateFlow.value.appConfig.selectedTargetAppName
    }

  private val targetDeviceFilter: (List<TrailblazeConnectedDeviceSummary>) -> List<TrailblazeConnectedDeviceSummary> =
    { connectedDeviceSummaries ->
      connectedDeviceSummaries.filter { connectedDeviceSummary ->
        settingsRepo.getEnabledDriverTypes().contains(connectedDeviceSummary.trailblazeDriverType)
      }
    }

  private val _deviceStateFlow = MutableStateFlow(DeviceState())
  val deviceStateFlow: StateFlow<DeviceState> = _deviceStateFlow.asStateFlow()

  private val scope = CoroutineScope(Dispatchers.IO)
  private var pollingJob: Job? = null

  /**
   * Load available devices from the system.
   * This will update the deviceStateFlow with the discovered devices.
   */
  fun loadDevices() {
    scope.launch {
      withContext(Dispatchers.Default) {
        updateDeviceState { currDeviceState ->
          currDeviceState.copy(isLoading = true, error = null)
        }
      }

      try {
        val devices: List<Device.Connected> = DeviceService.listConnectedDevices(includeWeb = false)

        val deviceInfos = buildList {
          devices.forEach { maestroConnectedDevice: Device.Connected ->

            val trailblazeDeviceId = TrailblazeDeviceId(
              instanceId = maestroConnectedDevice.instanceId,
              trailblazeDevicePlatform = when (maestroConnectedDevice.platform) {
                Platform.ANDROID -> TrailblazeDevicePlatform.ANDROID
                Platform.IOS -> TrailblazeDevicePlatform.IOS
                Platform.WEB -> TrailblazeDevicePlatform.WEB
              }
            )

            val installedAppIds = getInstalledAppIds(trailblazeDeviceId)

            when (maestroConnectedDevice.platform) {
              Platform.ANDROID -> {
                add(
                  TrailblazeConnectedDeviceSummary(
                    trailblazeDriverType = TrailblazeDriverType.ANDROID_ONDEVICE_INSTRUMENTATION,
                    instanceId = maestroConnectedDevice.instanceId,
                    description = maestroConnectedDevice.description,
                    installedAppIds = installedAppIds
                  )
                )
                add(
                  TrailblazeConnectedDeviceSummary(
                    trailblazeDriverType = TrailblazeDriverType.ANDROID_HOST,
                    instanceId = maestroConnectedDevice.instanceId,
                    description = maestroConnectedDevice.description,
                    installedAppIds = installedAppIds
                  )
                )
              }

              Platform.IOS -> {
                add(
                  TrailblazeConnectedDeviceSummary(
                    trailblazeDriverType = TrailblazeDriverType.IOS_HOST,
                    instanceId = maestroConnectedDevice.instanceId,
                    description = maestroConnectedDevice.description,
                    installedAppIds = installedAppIds
                  )
                )
              }

              Platform.WEB -> {
                add(
                  TrailblazeConnectedDeviceSummary(
                    trailblazeDriverType = TrailblazeDriverType.WEB_PLAYWRIGHT_HOST,
                    instanceId = maestroConnectedDevice.instanceId,
                    description = maestroConnectedDevice.description,
                    installedAppIds = installedAppIds
                  )
                )
              }
            }
          }
        }

        val filteredDevices = targetDeviceFilter(deviceInfos)

        withContext(Dispatchers.Default) {
          updateDeviceState { currState ->
            currState.copy(
              availableDevices = filteredDevices,
              selectedDevices = currState.selectedDevices.mapNotNull { selected ->
                // Try to maintain the same selected devices if they still exist
                filteredDevices.firstOrNull { it.instanceId == selected.instanceId && it.trailblazeDriverType == selected.trailblazeDriverType }
              }.toSet(),
              isLoading = false,
              error = null
            )
          }
        }
      } catch (e: Exception) {
        updateDeviceState { deviceState ->
          deviceState.copy(
            availableDevices = emptyList(),
            isLoading = false,
            error = e.message ?: "Unknown error loading devices"
          )
        }
      }
    }
  }

  /**
   * Select a specific device. If already selected, does nothing.
   */
  fun selectDevice(device: TrailblazeConnectedDeviceSummary) {
    updateDeviceState { deviceState ->
      val currentSelected = deviceState.selectedDevices
      deviceState.copy(
        selectedDevices = currentSelected + device
      )
    }
  }

  /**
   * Toggle device selection.
   */
  fun toggleDevice(device: TrailblazeConnectedDeviceSummary) {
    updateDeviceState { deviceState ->
      val currentSelected = deviceState.selectedDevices
      deviceState.copy(
        selectedDevices = if (device in currentSelected) {
          currentSelected - device
        } else {
          currentSelected + device
        }
      )
    }
  }

  /**
   * Set multiple selected devices at once.
   */
  fun setSelectedDevices(devices: Set<TrailblazeConnectedDeviceSummary>) {
    updateDeviceState { deviceState ->
      deviceState.copy(selectedDevices = devices)
    }
  }

  fun updateDeviceState(deviceStateUpdater: (DeviceState) -> DeviceState) {
    _deviceStateFlow.value = deviceStateUpdater(_deviceStateFlow.value)
  }

  /**
   * Select devices by instance IDs. Useful for restoring saved device selection.
   * @return set of devices that were found and selected
   */
  fun selectDevicesByInstanceIds(instanceIds: List<String>): Set<TrailblazeConnectedDeviceSummary> {
    val devices = _deviceStateFlow.value.availableDevices.filter { it.instanceId in instanceIds }.toSet()
    if (devices.isNotEmpty()) {
      setSelectedDevices(devices)
    }
    return devices
  }

  /**
   * Gets or creates a session manager for a specific device.
   * For host-based drivers, this returns the local session manager.
   * For on-device drivers, the session manager is a proxy that queries the device.
   */
  fun getOrCreateSessionManager(trailblazeDeviceId: TrailblazeDeviceId): TrailblazeSessionManager {
    val currentManagers = _deviceStateFlow.value.sessionManagersByDevice
    return currentManagers[trailblazeDeviceId] ?: run {
      val newManager = TrailblazeSessionManager()
      updateDeviceState { state ->
        state.copy(
          sessionManagersByDevice = state.sessionManagersByDevice + (trailblazeDeviceId to newManager)
        )
      }
      newManager
    }
  }

  /**
   * Gets the session manager for a device if it exists.
   */
  fun getSessionManager(trailblazeDeviceId: TrailblazeDeviceId): TrailblazeSessionManager? {
    return _deviceStateFlow.value.sessionManagersByDevice[trailblazeDeviceId]
  }

  /**
   * Starts a session on a device. This creates/updates the session manager and tracks the session info.
   */
  fun trackActiveSession(
    trailblazeDeviceId: TrailblazeDeviceId,
    sessionId: String
  ) {
    val sessionManager = getOrCreateSessionManager(trailblazeDeviceId)
    sessionManager.startSession(sessionId)

    updateDeviceState { state ->
      state.copy(
        activeSessionsByDevice = state.activeSessionsByDevice + (trailblazeDeviceId to DeviceSessionInfo(
          sessionId = sessionId,
          trailblazeDeviceId = trailblazeDeviceId,
          sessionManager = sessionManager
        ))
      )
    }
  }

  /**
   * Ends a session on a device. This updates the session manager and clears the active session info.
   */
  fun endSession(trailblazeDeviceId: TrailblazeDeviceId) {
    getSessionManager(trailblazeDeviceId)?.endSession()

    updateDeviceState { state ->
      state.copy(
        activeSessionsByDevice = state.activeSessionsByDevice - trailblazeDeviceId
      )
    }
  }

  /**
   * Cancels the current session on a device.
   */
  fun cancelSession(trailblazeDeviceId: TrailblazeDeviceId) {
    cancelSessionCallback?.invoke(trailblazeDeviceId)
    getSessionManager(trailblazeDeviceId)?.cancelCurrentSession()
  }

  /**
   * Checks if a device currently has an active session.
   */
  fun isDeviceRunningSession(trailblazeDeviceId: TrailblazeDeviceId): Boolean {
    return _deviceStateFlow.value.activeSessionsByDevice.containsKey(trailblazeDeviceId)
  }

  /**
   * Gets the active session info for a device, if any.
   */
  fun getActiveSessionForDevice(trailblazeDeviceId: TrailblazeDeviceId): DeviceSessionInfo? {
    return _deviceStateFlow.value.activeSessionsByDevice[trailblazeDeviceId]
  }

  /**
   * Clears all active sessions. Useful when reconnecting or resetting state.
   */
  fun clearActiveSessions() {
    updateDeviceState { deviceState ->
      deviceState.copy(activeSessionsByDevice = emptyMap())
    }
  }

  /**
   * Polls Android on-device instrumentation devices to check their status.
   * This continuously monitors device-specific ports to see if a test is running.
   */
  fun startPollingDeviceStatus() {
    pollingJob?.cancel()
    pollingJob = scope.launch {
      while (isActive) {
        delay(2000) // Poll every 2 seconds

        // Check all Android on-device instrumentation devices
        val androidDevices = _deviceStateFlow.value.availableDevices.filter {
          it.trailblazeDriverType == TrailblazeDriverType.ANDROID_ONDEVICE_INSTRUMENTATION
        }

        androidDevices.forEach { device: TrailblazeConnectedDeviceSummary ->
          try {
            // Query the on-device server status via adb port forwarding
            val trailblazeDeviceId = device.trailblazeDeviceId
            val onDeviceRpc = OnDeviceRpcClient(trailblazeDeviceId)

            onDeviceRpc.rpcCall(
              request = DeviceStatusRequest(
                trailblazeDeviceId = trailblazeDeviceId
              ),
              onSuccess = { deviceStatus ->
                if (deviceStatus is DeviceStatusResponse.HasSession && deviceStatus.isRunning) {
                  // Session is running on device - update session info
                  val currentSession: DeviceSessionInfo? = getActiveSessionForDevice(device.trailblazeDeviceId)
                  if (currentSession == null || currentSession.sessionId != deviceStatus.sessionId) {
                    // Use device manager's startSession to handle everything
                    trackActiveSession(device.trailblazeDeviceId, deviceStatus.sessionId)
                  }
                } else {
                  // No session running - clear if we had marked one
                  if (isDeviceRunningSession(device.trailblazeDeviceId)) {
                    endSession(device.trailblazeDeviceId)
                  }
                }
              },
              onFailure = { failureResult: RpcResult.Failure ->
                // Log the RPC error but don't throw - device might be unavailable temporarily
                println(
                  "Failed to get device status for ${device.instanceId}: ${
                    TrailblazeJsonInstance.encodeToString(failureResult)
                  }"
                )
              }
            )
          } catch (e: Exception) {
            // Can't connect to device - this is expected if no instrumentation is running
            // or if device is disconnected. Clear any session we had marked.
            if (isDeviceRunningSession(device.trailblazeDeviceId)) {
              endSession(device.trailblazeDeviceId)
            }
          }
        }
      }
    }
  }

  /**
   * Stops polling device status.
   */
  fun stopPollingDeviceStatus() {
    pollingJob?.cancel()
  }
}