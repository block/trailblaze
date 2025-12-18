package xyz.block.trailblaze.ui

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import maestro.Driver
import maestro.device.Device
import maestro.device.DeviceService
import maestro.device.Platform
import xyz.block.trailblaze.devices.TrailblazeConnectedDeviceSummary
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.model.TrailblazeHostAppTarget
import xyz.block.trailblaze.ui.devices.DeviceManagerState
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

  private val targetDeviceFilter: (List<TrailblazeConnectedDeviceSummary>) -> List<TrailblazeConnectedDeviceSummary> =
    { connectedDeviceSummaries ->
      connectedDeviceSummaries.filter { connectedDeviceSummary ->
        settingsRepo.getEnabledDriverTypes().contains(connectedDeviceSummary.trailblazeDriverType)
      }
    }

  private val _deviceStateFlow = MutableStateFlow(DeviceManagerState())
  val deviceStateFlow: StateFlow<DeviceManagerState> = _deviceStateFlow.asStateFlow()

  private val loadDevicesScope = CoroutineScope(Dispatchers.IO)

  /**
   * Load available devices from the system.
   * This will update the deviceStateFlow with the discovered devices.
   */
  fun loadDevices() {
    loadDevicesScope.launch {
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
            // Create DeviceState for each device, preserving existing session state
            val newDeviceStates = filteredDevices.associate { device ->
              val deviceId = device.trailblazeDeviceId

              // Preserve existing session state if device already exists
              val existingSessionId = currState.devices[deviceId]?.currentSessionId

              deviceId to DeviceState(
                device = device,
                currentSessionId = existingSessionId
              )
            }

            currState.copy(
              devices = newDeviceStates,
              isLoading = false,
              error = null
            )
          }
        }
      } catch (e: Exception) {
        updateDeviceState { deviceState ->
          deviceState.copy(
            devices = emptyMap(),
            isLoading = false,
            error = e.message ?: "Unknown error loading devices"
          )
        }
      }
    }
  }

  fun updateDeviceState(deviceStateUpdater: (DeviceManagerState) -> DeviceManagerState) {
    _deviceStateFlow.value = deviceStateUpdater(_deviceStateFlow.value)
  }

  /**
   * Tracks a session on a device. Updates the DeviceState to Running.
   */
  fun trackActiveHostSession(
    trailblazeDeviceId: TrailblazeDeviceId,
    sessionId: SessionId
  ) {
    updateDeviceState { state ->
      val deviceState = state.devices[trailblazeDeviceId]
      if (deviceState != null) {
        val updatedDeviceState = deviceState.copy(
          currentSessionId = sessionId
        )
        state.copy(
          devices = state.devices + (trailblazeDeviceId to updatedDeviceState)
        )
      } else {
        state // Device not found, no change
      }
    }
  }

  fun getAllSupportedDriverTypes() = settingsRepo.getAllSupportedDriverTypes()

  fun getCurrentSelectedTargetApp(): TrailblazeHostAppTarget? = availableAppTargets
    .filter { it != defaultHostAppTarget }
    .firstOrNull { appTarget ->
      appTarget.name == settingsRepo.serverStateFlow.value.appConfig.selectedTargetAppName
    }

  // Store running test instances per device - allows forceful driver shutdown
  private val maestroDriverByDeviceMap: MutableMap<TrailblazeDeviceId, Driver> = mutableMapOf()

  // Store running coroutine jobs per device - allows cancellation of test execution
  private val coroutineScopeByDevice: MutableMap<TrailblazeDeviceId, CoroutineScope> = mutableMapOf()

  /**
   * Cancels the current session on a device.
   * Uses forceful cancellation - closes the driver and kills the coroutine job.
   * 
   * FORCEFULLY KILLS the running test on a specific device.
   * This is aggressive - it shuts down the driver (killing child processes like XCUITest),
   * then cancels the coroutine job. No more "cooperative" cancellation.
   * The job cleanup (finally block) will handle removing it from the map.
   */
  fun cancelSessionForDevice(trailblazeDeviceId: TrailblazeDeviceId) {
    println("FORCEFULLY CANCELLING test on device: ${trailblazeDeviceId.instanceId}")

    closeAndRemoveMaestroDriverForDevice(trailblazeDeviceId)

    // Step 2: Cancel the coroutine job (stop any remaining work)
    cancelAndRemoveCoroutineScopeForDeviceIfActive(trailblazeDeviceId)

    updateDeviceState { deviceManagerState: DeviceManagerState ->
      deviceManagerState.devices[trailblazeDeviceId]?.let { stateForDevice: DeviceState ->
        deviceManagerState.copy(
          devices = deviceManagerState.devices + (trailblazeDeviceId to stateForDevice.copy(currentSessionId = null))
        )
      } ?: deviceManagerState // Device not found, no change
    }
  }

  private fun closeAndRemoveMaestroDriverForDevice(trailblazeDeviceId: TrailblazeDeviceId) {
    // Step 1: Get the running test and KILL its driver (kills child processes)
    maestroDriverByDeviceMap[trailblazeDeviceId]?.let { maestroDriver ->
      try {
        println("Forcefully closing driver for device: ${trailblazeDeviceId.instanceId}")
        // This closes the underlying driver and kills child processes (XCUITest, adb, etc.)
        maestroDriver.close()
        println("Driver closed successfully for device: ${trailblazeDeviceId.instanceId}")
      } catch (e: Exception) {
        println("Error closing driver (continuing anyway): ${e.message}")
        // Continue with coroutine cancellation even if driver close fails
      } finally {
        maestroDriverByDeviceMap.remove(trailblazeDeviceId)
      }
    } ?: println("No Maestro Driver found for device: ${trailblazeDeviceId.instanceId}")
  }

  private fun cancelAndRemoveCoroutineScopeForDeviceIfActive(trailblazeDeviceId: TrailblazeDeviceId) {
    coroutineScopeByDevice[trailblazeDeviceId]?.let { coroutineScopeForDevice ->
      println("Cancelling coroutine job for device: ${trailblazeDeviceId.instanceId}")
      println("  Scope isActive BEFORE cancel: ${coroutineScopeForDevice.isActive}")
      try {
        if (coroutineScopeForDevice.isActive) {
          coroutineScopeForDevice.cancel(CancellationException("Session cancelled by user - driver forcefully closed"))

          // Verify cancellation propagated by checking status over time
          println("  Scope isActive AFTER cancel (immediate): ${coroutineScopeForDevice.isActive}")

          // Monitor cancellation propagation
          repeat(5) { attempt ->
            Thread.sleep(100)
            val stillActive = coroutineScopeForDevice.isActive
            println("  Scope isActive check #${attempt + 1} (after ${(attempt + 1) * 100}ms): $stillActive")
            if (!stillActive) {
              println("  ✓ Scope successfully cancelled and inactive")
              return@repeat
            }
          }

          // If still active after 500ms, warn
          if (coroutineScopeForDevice.isActive) {
            println("  ⚠️ WARNING: Scope still active after 500ms - cancellation may not have propagated!")
          }
        } else {
          println("  Scope was already inactive, nothing to cancel")
        }
      } finally {
        coroutineScopeByDevice.remove(trailblazeDeviceId)
        println("  Scope removed from map for device: ${trailblazeDeviceId.instanceId}")
      }
    }
  }

  fun getDeviceState(trailblazeDeviceId: TrailblazeDeviceId): DeviceState? {
    return deviceStateFlow.value.devices[trailblazeDeviceId]
  }

  fun createNewCoroutineScopeForDevice(trailblazeDeviceId: TrailblazeDeviceId): CoroutineScope {
    cancelAndRemoveCoroutineScopeForDeviceIfActive(trailblazeDeviceId)
    return CoroutineScope(Dispatchers.IO).also {
      coroutineScopeByDevice[trailblazeDeviceId] = it
    }
  }

  fun setActiveDriverForDevice(trailblazeDeviceId: TrailblazeDeviceId, maestroDriver: Driver) {
    maestroDriverByDeviceMap[trailblazeDeviceId] = maestroDriver
  }
}
