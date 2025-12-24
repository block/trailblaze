package xyz.block.trailblaze.mcp

import kotlinx.coroutines.flow.MutableStateFlow
import xyz.block.trailblaze.devices.TrailblazeConnectedDeviceSummary
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.model.TrailblazeHostAppTarget
import xyz.block.trailblaze.ui.TrailblazeDeviceManager

class TrailblazeMcpBridgeImpl(
  private val trailblazeDeviceManager: TrailblazeDeviceManager,
) : TrailblazeMcpBridge {

  data class DeviceBridgeState(
    val selectedDeviceId: TrailblazeDeviceId? = null,
    val availableDeviceCache: Map<TrailblazeDeviceId, TrailblazeConnectedDeviceSummary> = emptyMap(),
  )

  private fun updateDeviceBridgeState(updater: (DeviceBridgeState) -> DeviceBridgeState) {
    deviceBridgeState.value = updater(deviceBridgeState.value)
  }

  private val deviceBridgeState = MutableStateFlow(
    DeviceBridgeState()
  )

  override fun getAvailableAppTargets(): Set<TrailblazeHostAppTarget> {
    return trailblazeDeviceManager.availableAppTargets
  }

  override suspend fun selectDevice(trailblazeDeviceId: TrailblazeDeviceId): TrailblazeConnectedDeviceSummary {
    val selectedDeviceId = assertDeviceIsSelected(trailblazeDeviceId)
    return deviceBridgeState.value.availableDeviceCache[selectedDeviceId]!!
  }

  override suspend fun runYaml(yaml: String) {
    val trailblazeDeviceId: TrailblazeDeviceId = assertDeviceIsSelected()
    trailblazeDeviceManager.runYaml(
      yamlToRun = yaml,
      trailblazeDeviceId = trailblazeDeviceId,
      forceStopTargetApp = false
    )
  }

  override suspend fun getAvailableDevices(): Set<TrailblazeConnectedDeviceSummary> {
    return trailblazeDeviceManager.loadDevicesSuspend().toSet()
      .also { availableDevices ->
        updateDeviceBridgeState { currentState ->
          currentState.copy(
            availableDeviceCache = availableDevices.associateBy { availableDevice -> availableDevice.trailblazeDeviceId }
          )
        }
      }
  }

  override suspend fun getInstalledAppIds(): Set<String> {
    val trailblazeDeviceId = assertDeviceIsSelected()
    return trailblazeDeviceManager.getInstalledAppIds(trailblazeDeviceId)
  }

  private suspend fun assertDeviceIsSelected(requestedTrailblazeDeviceId: TrailblazeDeviceId? = null): TrailblazeDeviceId {
    // If a specific device is requested, validate and select it
    if (requestedTrailblazeDeviceId != null) {
      // Check cache first, then refresh if not found
      val isInCache = deviceBridgeState.value.availableDeviceCache.containsKey(requestedTrailblazeDeviceId)
      if (!isInCache) {
        val availableDevices = getAvailableDevices()
        val isAvailable = availableDevices.any { it.trailblazeDeviceId == requestedTrailblazeDeviceId }
        if (!isAvailable) {
          error("Device $requestedTrailblazeDeviceId is not available.")
        }
      }
      // Device is available, select it
      updateDeviceBridgeState { it.copy(selectedDeviceId = requestedTrailblazeDeviceId) }
      return requestedTrailblazeDeviceId
    }

    // No specific device requested - use currently selected if available
    val currentlySelectedDeviceId = deviceBridgeState.value.selectedDeviceId
    if (currentlySelectedDeviceId != null) {
      return currentlySelectedDeviceId
    }

    // No device selected - pick the first available one
    val firstDevice = getAvailableDevices().firstOrNull()
      ?: error("No devices are connected, please connect a device to continue.")

    updateDeviceBridgeState { it.copy(selectedDeviceId = firstDevice.trailblazeDeviceId) }
    return firstDevice.trailblazeDeviceId
  }
}
