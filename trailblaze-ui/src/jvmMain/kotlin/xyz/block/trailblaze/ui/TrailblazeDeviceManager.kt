package xyz.block.trailblaze.ui

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.isActive
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.Serializable
import maestro.device.Device
import maestro.device.DeviceService
import maestro.device.Platform
import xyz.block.trailblaze.devices.TrailblazeConnectedDeviceSummary
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.model.TrailblazeHostAppTarget
import xyz.block.trailblaze.ui.models.AppIconProvider
import xyz.block.trailblaze.session.TrailblazeSessionManager

// Data class for parsing the device status response
@Serializable
private data class DeviceStatusResponse(
  val sessionId: String?,
  val isRunning: Boolean
)

/**
 * Information about an active session on a device
 */
data class DeviceSessionInfo(
  val sessionId: String,
  val deviceInstanceId: String,
  val startTimeMs: Long = System.currentTimeMillis(),
  val sessionManager: TrailblazeSessionManager
)

/**
 * Manages device discovery, selection, and state across the application.
 * Can be shared across multiple Composables to maintain consistent device state.
 */
class TrailblazeDeviceManager(
  val settingsRepo: TrailblazeSettingsRepo,
  val appTargets: Set<TrailblazeHostAppTarget>,
  val supportedDrivers: Set<TrailblazeDriverType>,
  val appIconProvider: AppIconProvider,
  val getInstalledAppIds: (Device.Connected) -> Set<String>
) {

  /**
   * Callback function that can be set by the host runner to actually cancel the running job.
   * This is set by TrailblazeHostYamlRunner when it starts.
   */
  var cancelSessionCallback: ((deviceInstanceId: String) -> Boolean)? = null

  fun getCurrentSelectedTargetApp() = appTargets
    .filter { it != TrailblazeHostAppTarget.DefaultTrailblazeHostAppTarget }
    .firstOrNull { appTarget ->
      appTarget.name == settingsRepo.serverStateFlow.value.appConfig.selectedTargetAppName
    }

  private val targetDeviceFilter: (List<TrailblazeConnectedDeviceSummary>) -> List<TrailblazeConnectedDeviceSummary> = {
    it.filter { supportedDrivers.contains(it.trailblazeDriverType) }
  }

  data class DeviceState(
    val availableDevices: List<TrailblazeConnectedDeviceSummary> = emptyList(),
    val selectedDevices: Set<TrailblazeConnectedDeviceSummary> = emptySet(),
    val isLoading: Boolean = false,
    val error: String? = null,
    // Map of device instance ID to session manager
    val sessionManagersByDevice: Map<String, TrailblazeSessionManager> = emptyMap(),
    // Map of device instance ID to active session info
    val activeSessionsByDevice: Map<String, DeviceSessionInfo> = emptyMap(),
  )

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
            val installedAppIds = getInstalledAppIds(maestroConnectedDevice)

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
  fun getOrCreateSessionManager(deviceInstanceId: String): TrailblazeSessionManager {
    val currentManagers = _deviceStateFlow.value.sessionManagersByDevice
    return currentManagers[deviceInstanceId] ?: run {
      val newManager = TrailblazeSessionManager()
      updateDeviceState { state ->
        state.copy(
          sessionManagersByDevice = state.sessionManagersByDevice + (deviceInstanceId to newManager)
        )
      }
      newManager
    }
  }

  /**
   * Gets the session manager for a device if it exists.
   */
  fun getSessionManager(deviceInstanceId: String): TrailblazeSessionManager? {
    return _deviceStateFlow.value.sessionManagersByDevice[deviceInstanceId]
  }

  /**
   * Starts a session on a device. This creates/updates the session manager and tracks the session info.
   */
  fun startSession(
    deviceInstanceId: String,
    sessionId: String
  ) {
    val sessionManager = getOrCreateSessionManager(deviceInstanceId)
    sessionManager.startSession(sessionId)

    updateDeviceState { state ->
      state.copy(
        activeSessionsByDevice = state.activeSessionsByDevice + (deviceInstanceId to DeviceSessionInfo(
          sessionId = sessionId,
          deviceInstanceId = deviceInstanceId,
          sessionManager = sessionManager
        ))
      )
    }
  }

  /**
   * Ends a session on a device. This updates the session manager and clears the active session info.
   */
  fun endSession(deviceInstanceId: String) {
    getSessionManager(deviceInstanceId)?.endSession()

    updateDeviceState { state ->
      state.copy(
        activeSessionsByDevice = state.activeSessionsByDevice - deviceInstanceId
      )
    }
  }

  /**
   * Cancels the current session on a device.
   */
  fun cancelSession(deviceInstanceId: String) {
    cancelSessionCallback?.invoke(deviceInstanceId)
    getSessionManager(deviceInstanceId)?.cancelCurrentSession()
  }

  /**
   * Checks if a device currently has an active session.
   */
  fun isDeviceRunningSession(deviceInstanceId: String): Boolean {
    return _deviceStateFlow.value.activeSessionsByDevice.containsKey(deviceInstanceId)
  }

  /**
   * Gets the active session info for a device, if any.
   */
  fun getActiveSessionForDevice(deviceInstanceId: String): DeviceSessionInfo? {
    return _deviceStateFlow.value.activeSessionsByDevice[deviceInstanceId]
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
   * This continuously monitors localhost:52526/status to see if a test is running.
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

        androidDevices.forEach { device ->
          try {
            // Query the on-device server status via adb port forwarding
            val client =
              xyz.block.trailblaze.http.TrailblazeHttpClientFactory.createDefaultHttpClient(2L)
            val response = client.get("http://localhost:52526/status")
            val statusJson = response.bodyAsText()
            client.close()

            // Parse the JSON response using kotlinx.serialization.json.Json
            val deviceStatus = kotlinx.serialization.json.Json.decodeFromString(
              DeviceStatusResponse.serializer(),
              statusJson
            )

            if (deviceStatus.isRunning && !deviceStatus.sessionId.isNullOrEmpty()) {
              // Session is running on device - update session info
              val currentSession = getActiveSessionForDevice(device.instanceId)
              if (currentSession == null || currentSession.sessionId != deviceStatus.sessionId) {
                // Use device manager's startSession to handle everything
                startSession(device.instanceId, deviceStatus.sessionId)
              }
            } else {
              // No session running - clear if we had marked one
              if (isDeviceRunningSession(device.instanceId)) {
                endSession(device.instanceId)
              }
            }
          } catch (e: Exception) {
            // Can't connect to device - this is expected if no instrumentation is running
            // or if device is disconnected. Clear any session we had marked.
            if (isDeviceRunningSession(device.instanceId)) {
              endSession(device.instanceId)
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
