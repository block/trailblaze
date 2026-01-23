package xyz.block.trailblaze.mcp

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import xyz.block.trailblaze.api.ScreenState
import xyz.block.trailblaze.devices.TrailblazeConnectedDeviceSummary
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.model.TrailblazeHostAppTarget
import xyz.block.trailblaze.llm.TrailblazeReferrer
import xyz.block.trailblaze.toolcalls.TrailblazeTool
import xyz.block.trailblaze.ui.TrailblazeDeviceManager
import xyz.block.trailblaze.yaml.TrailblazeYaml
import xyz.block.trailblaze.yaml.models.TrailblazeYamlBuilder

class TrailblazeMcpBridgeImpl(
  private val trailblazeDeviceManager: TrailblazeDeviceManager,
  /**
   * Optional custom executor for TrailblazeTools.
   * If not provided, tools will be converted to YAML and executed via runYaml().
   */
  private val trailblazeToolExecutor: (suspend (TrailblazeTool, TrailblazeDeviceId) -> String)? = null,
) : TrailblazeMcpBridge {

  private val _selectedDeviceIdFlow = MutableStateFlow<TrailblazeDeviceId?>(null)

  /**
   * Flow for reactively observing the selected device ID.
   */
  val selectedDeviceIdFlow: StateFlow<TrailblazeDeviceId?> = _selectedDeviceIdFlow.asStateFlow()

  /**
   * The currently selected device ID for MCP operations.
   */
  var selectedDeviceId: TrailblazeDeviceId?
    get() = _selectedDeviceIdFlow.value
    set(value) {
      _selectedDeviceIdFlow.value = value
    }

  override fun getAvailableAppTargets(): Set<TrailblazeHostAppTarget> {
    return trailblazeDeviceManager.availableAppTargets
  }

  override suspend fun selectDevice(trailblazeDeviceId: TrailblazeDeviceId): TrailblazeConnectedDeviceSummary {
    assertDeviceIsSelected(trailblazeDeviceId)
    return trailblazeDeviceManager.getDeviceState(trailblazeDeviceId)?.device
      ?: error("Device $trailblazeDeviceId is not available.")
  }

  override suspend fun runYaml(yaml: String, startNewSession: Boolean) {
    val deviceId = assertDeviceIsSelected()

    val sessionResolution = trailblazeDeviceManager.getOrCreateSessionResolution(
      trailblazeDeviceId = deviceId,
      forceNewSession = startNewSession,
      sessionIdPrefix = "yaml"
    )

    trailblazeDeviceManager.runYaml(
      yamlToRun = yaml,
      trailblazeDeviceId = deviceId,
      forceStopTargetApp = false,
      sendSessionStartLog = sessionResolution.isNewSession,
      sendSessionEndLog = false,
      existingSessionId = sessionResolution.sessionId,
      referrer = TrailblazeReferrer.MCP,
    )
  }

  override fun getCurrentlySelectedDeviceId(): TrailblazeDeviceId? {
    return selectedDeviceId
  }

  override suspend fun getCurrentScreenState(): ScreenState? {
    val trailblazeDeviceId = assertDeviceIsSelected()
    return trailblazeDeviceManager.getCurrentScreenState(trailblazeDeviceId)
  }

  override suspend fun getAvailableDevices(): Set<TrailblazeConnectedDeviceSummary> {
    val availableDevices = trailblazeDeviceManager.loadDevicesSuspend().toSet()
    return availableDevices
  }

  override suspend fun getInstalledAppIds(): Set<String> {
    val trailblazeDeviceId = assertDeviceIsSelected()
    return trailblazeDeviceManager.getInstalledAppIdsFlow(trailblazeDeviceId).value
  }

  override suspend fun executeTrailblazeTool(tool: TrailblazeTool): String {
    val trailblazeDeviceId = assertDeviceIsSelected()

    // Use custom executor if provided, otherwise convert to YAML and run
    if (trailblazeToolExecutor != null) {
      return trailblazeToolExecutor.invoke(tool, trailblazeDeviceId)
    }

    // Default implementation: convert tool to YAML and run via runYaml()
    println("Executing TrailblazeTool via YAML conversion: ${tool::class.simpleName}")

    val yaml = TrailblazeYaml.Default.encodeToString(
      TrailblazeYamlBuilder()
        .tools(listOf(tool))
        .build()
    )

    println("Generated YAML:\n$yaml")

    // Reuse the existing session if available, otherwise runYaml will create one
    runYaml(yaml, startNewSession = false)

    return "Executed ${tool::class.simpleName} on device ${trailblazeDeviceId.instanceId}"
  }

  override suspend fun endSession(): Boolean {
    val deviceId = assertDeviceIsSelected(null)
    val endedSessionId = trailblazeDeviceManager.endSessionForDevice(deviceId)
    return endedSessionId != null
  }

  private suspend fun assertDeviceIsSelected(requestedDeviceId: TrailblazeDeviceId? = null): TrailblazeDeviceId {
    // If a specific device is requested, validate and select it
    if (requestedDeviceId != null) {
      // Check if already selected
      if (selectedDeviceId == requestedDeviceId) {
        return requestedDeviceId
      }

      // Verify the device is available
      val isAvailable = trailblazeDeviceManager.getDeviceState(requestedDeviceId) != null
          || getAvailableDevices().any { it.trailblazeDeviceId == requestedDeviceId }

      if (!isAvailable) {
        error("Device $requestedDeviceId is not available.")
      }

      selectedDeviceId = requestedDeviceId
      return requestedDeviceId
    }

    // No specific device requested - use currently selected if available
    if (selectedDeviceId != null) {
      return selectedDeviceId!!
    }

    // No device selected - pick the first available one
    val firstDevice = getAvailableDevices().firstOrNull()
      ?: error("No devices are connected, please connect a device to continue.")

    return firstDevice.trailblazeDeviceId.also {
      selectedDeviceId = it
    }
  }
}
