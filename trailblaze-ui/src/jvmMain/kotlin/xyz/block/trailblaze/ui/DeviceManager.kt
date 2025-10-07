package xyz.block.trailblaze.ui

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import maestro.device.Device
import maestro.device.DeviceService
import maestro.device.Platform
import xyz.block.trailblaze.devices.TrailblazeConnectedDeviceSummary
import xyz.block.trailblaze.devices.TrailblazeDriverType

/**
 * Manages device discovery, selection, and state across the application.
 * Can be shared across multiple composables to maintain consistent device state.
 */
class DeviceManager(
  private val targetDeviceFilter: (List<TrailblazeConnectedDeviceSummary>) -> List<TrailblazeConnectedDeviceSummary> = { it },
) {
  data class DeviceState(
    val availableDevices: List<TrailblazeConnectedDeviceSummary> = emptyList(),
    val selectedDevices: Set<TrailblazeConnectedDeviceSummary> = emptySet(),
    val isLoading: Boolean = false,
    val error: String? = null,
  )

  private val _deviceStateFlow = MutableStateFlow(DeviceState())
  val deviceStateFlow: StateFlow<DeviceState> = _deviceStateFlow.asStateFlow()

  private val scope = CoroutineScope(Dispatchers.IO)

  /**
   * Load available devices from the system.
   * This will update the deviceStateFlow with the discovered devices.
   */
  fun loadDevices() {
    scope.launch {
      withContext(Dispatchers.Default) {
        _deviceStateFlow.value = _deviceStateFlow.value.copy(isLoading = true, error = null)
      }

      try {
        val devices: List<Device.Connected> = DeviceService.listConnectedDevices(includeWeb = false)

        val deviceInfos = buildList {
          devices.forEach { maestroConnectedDevice: Device.Connected ->
            when (maestroConnectedDevice.platform) {
              Platform.ANDROID -> {
                add(
                  TrailblazeConnectedDeviceSummary(
                    trailblazeDriverType = TrailblazeDriverType.ANDROID_ONDEVICE_INSTRUMENTATION,
                    instanceId = maestroConnectedDevice.instanceId,
                    description = maestroConnectedDevice.description
                  )
                )
                add(
                  TrailblazeConnectedDeviceSummary(
                    trailblazeDriverType = TrailblazeDriverType.ANDROID_HOST,
                    instanceId = maestroConnectedDevice.instanceId,
                    description = maestroConnectedDevice.description
                  )
                )
              }

              Platform.IOS -> {
                add(
                  TrailblazeConnectedDeviceSummary(
                    trailblazeDriverType = TrailblazeDriverType.IOS_HOST,
                    instanceId = maestroConnectedDevice.instanceId,
                    description = maestroConnectedDevice.description
                  )
                )
              }

              Platform.WEB -> {
                add(
                  TrailblazeConnectedDeviceSummary(
                    trailblazeDriverType = TrailblazeDriverType.WEB_PLAYWRIGHT_HOST,
                    instanceId = maestroConnectedDevice.instanceId,
                    description = maestroConnectedDevice.description
                  )
                )
              }
            }
          }
        }

        val filteredDevices = targetDeviceFilter(deviceInfos)

        withContext(Dispatchers.Default) {
          val currentState = _deviceStateFlow.value
          _deviceStateFlow.value = currentState.copy(
            availableDevices = filteredDevices,
            selectedDevices = currentState.selectedDevices.mapNotNull { selected ->
              // Try to maintain the same selected devices if they still exist
              filteredDevices.firstOrNull { it.instanceId == selected.instanceId && it.trailblazeDriverType == selected.trailblazeDriverType }
            }.toSet(),
            isLoading = false,
            error = null
          )
        }
      } catch (e: Exception) {
        withContext(Dispatchers.Default) {
          _deviceStateFlow.value = _deviceStateFlow.value.copy(
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
    _deviceStateFlow.value = _deviceStateFlow.value.copy(
      selectedDevices = _deviceStateFlow.value.selectedDevices + device
    )
  }

  /**
   * Deselect a specific device.
   */
  fun deselectDevice(device: TrailblazeConnectedDeviceSummary) {
    _deviceStateFlow.value = _deviceStateFlow.value.copy(
      selectedDevices = _deviceStateFlow.value.selectedDevices - device
    )
  }

  /**
   * Toggle device selection.
   */
  fun toggleDevice(device: TrailblazeConnectedDeviceSummary) {
    val currentSelected = _deviceStateFlow.value.selectedDevices
    _deviceStateFlow.value = _deviceStateFlow.value.copy(
      selectedDevices = if (device in currentSelected) {
        currentSelected - device
      } else {
        currentSelected + device
      }
    )
  }

  /**
   * Set multiple selected devices at once.
   */
  fun setSelectedDevices(devices: Set<TrailblazeConnectedDeviceSummary>) {
    _deviceStateFlow.value = _deviceStateFlow.value.copy(selectedDevices = devices)
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
   * Clear all selected devices.
   */
  fun clearSelection() {
    _deviceStateFlow.value = _deviceStateFlow.value.copy(selectedDevices = emptySet())
  }
}
