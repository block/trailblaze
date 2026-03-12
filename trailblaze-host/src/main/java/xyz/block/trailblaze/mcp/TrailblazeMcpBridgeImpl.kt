package xyz.block.trailblaze.mcp

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.datetime.Clock
import xyz.block.trailblaze.api.ScreenState
import xyz.block.trailblaze.api.ScreenshotScalingConfig
import xyz.block.trailblaze.mcp.AgentImplementation
import xyz.block.trailblaze.devices.TrailblazeConnectedDeviceSummary
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDeviceInfo
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.host.devices.TrailblazeConnectedDevice
import xyz.block.trailblaze.host.devices.TrailblazeDeviceService
import xyz.block.trailblaze.host.devices.TrailblazeHostDeviceClassifier
import xyz.block.trailblaze.host.screenstate.HostMaestroDriverScreenState
import xyz.block.trailblaze.model.TrailblazeHostAppTarget
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.logs.model.SessionStatus
import xyz.block.trailblaze.llm.TrailblazeReferrer
import xyz.block.trailblaze.mcp.android.ondevice.rpc.GetScreenStateRequest
import xyz.block.trailblaze.mcp.android.ondevice.rpc.GetScreenStateResponse
import xyz.block.trailblaze.mcp.android.ondevice.rpc.OnDeviceRpcClient
import xyz.block.trailblaze.mcp.android.ondevice.rpc.RpcResult
import xyz.block.trailblaze.logs.client.TrailblazeLog
import xyz.block.trailblaze.report.utils.LogsRepo
import xyz.block.trailblaze.toolcalls.TrailblazeTool
import xyz.block.trailblaze.ui.TrailblazeDeviceManager
import xyz.block.trailblaze.util.Console
import xyz.block.trailblaze.yaml.createTrailblazeYaml
import xyz.block.trailblaze.yaml.models.TrailblazeYamlBuilder
import kotlin.time.Duration

private const val DEFAULT_DEVICE_WIDTH = 1080
private const val DEFAULT_DEVICE_HEIGHT = 2400

class TrailblazeMcpBridgeImpl(
  private val trailblazeDeviceManager: TrailblazeDeviceManager,
  /**
   * Optional custom executor for TrailblazeTools.
   * If not provided, tools will be converted to YAML and executed via runYaml().
   */
  private val trailblazeToolExecutor: (suspend (TrailblazeTool, TrailblazeDeviceId) -> String)? = null,
  /**
   * Optional LogsRepo for MCP-specific blocking execution.
   * Required for runYamlBlocking() to wait for completion.
   */
  private val logsRepo: LogsRepo? = null,
) : TrailblazeMcpBridge {

  private val _selectedDeviceIdFlow = MutableStateFlow<TrailblazeDeviceId?>(null)

  /**
   * Cached screen state from the last tool execution.
   * This allows subsequent viewHierarchy/screenshot calls to use cached data
   * without needing to create a new session.
   */
  private var _lastScreenState: ScreenState? = null

  /**
   * Persistent device connection for direct screen state capture.
   * Created when a device is selected and kept alive until endSession or device change.
   * This enables fast, reliable screen state queries without session overhead.
   */
  private var _persistentDevice: TrailblazeConnectedDevice? = null

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
    // Clear cached state and persistent device when switching devices
    if (selectedDeviceId != trailblazeDeviceId) {
      _lastScreenState = null
      closePersistentDevice()
    }
    assertDeviceIsSelected(trailblazeDeviceId)

    // Create persistent device connection for direct screen capture
    if (_persistentDevice == null) {
      createPersistentDevice(trailblazeDeviceId)
    }

    return trailblazeDeviceManager.getDeviceState(trailblazeDeviceId)?.device
      ?: error("Device $trailblazeDeviceId is not available.")
  }

  /**
   * Creates a persistent Maestro driver connection for the specified device.
   * This enables direct, fast screen state capture without session overhead.
   */
  private fun createPersistentDevice(trailblazeDeviceId: TrailblazeDeviceId) {
    try {
      val connectedDevice = TrailblazeDeviceService.getConnectedDevice(
        trailblazeDeviceId = trailblazeDeviceId,
        appTarget = trailblazeDeviceManager.getCurrentSelectedTargetApp()
      )
      if (connectedDevice != null) {
        _persistentDevice = connectedDevice
      }
    } catch (e: Exception) {
      // Don't fail device selection if persistent connection fails - fallback will be used
      Console.log("[MCP Bridge] Persistent device connection failed (fallback will be used): ${e.message}")
    }
  }

  /**
   * Closes the persistent device connection and releases resources.
   */
  private fun closePersistentDevice() {
    _persistentDevice?.let { device ->
      try {
        device.getMaestroDriver().close()
      } catch (e: Exception) {
        Console.log("[MCP Bridge] Exception closing persistent device (already closed?): ${e.message}")
      }
      _persistentDevice = null
    }
  }

  override suspend fun runYaml(
    yaml: String,
    startNewSession: Boolean,
    agentImplementation: AgentImplementation,
  ): String {
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
      agentImplementation = agentImplementation,
    )

    // Return the session ID used for this run so callers can monitor progress
    return sessionResolution.sessionId.value
  }

  override fun getCurrentlySelectedDeviceId(): TrailblazeDeviceId? {
    return selectedDeviceId
  }

  override suspend fun getCurrentScreenState(): ScreenState? {
    // Return cached state if available (from last tool execution)
    _lastScreenState?.let { return it }

    // Otherwise capture fresh state (creates a new session)
    val trailblazeDeviceId = assertDeviceIsSelected()
    return trailblazeDeviceManager.getCurrentScreenState(trailblazeDeviceId).also {
      _lastScreenState = it
    }
  }

  /**
   * Returns the cached screen state without capturing a new one.
   * Returns null if no cached state is available.
   */
  fun getCachedScreenState(): ScreenState? = _lastScreenState

  /**
   * Clears the cached screen state.
   * Call this when you want to force a fresh capture on next request.
   */
  fun clearCachedScreenState() {
    _lastScreenState = null
  }

  override fun getDirectScreenStateProvider(): ((ScreenshotScalingConfig) -> ScreenState)? {
    // Use persistent device connection if available (preferred - always ready)
    _persistentDevice?.let { device ->
      val driver = device.getMaestroDriver()
      return { scalingConfig ->
        HostMaestroDriverScreenState(
          maestroDriver = driver,
          setOfMarkEnabled = false,
          screenshotScalingConfig = scalingConfig,
        )
      }
    }

    // Fallback: use active driver from device manager (only available during YAML execution)
    val deviceId = selectedDeviceId ?: return null
    val driver = trailblazeDeviceManager.getActiveDriverForDevice(deviceId) ?: return null

    return { scalingConfig ->
      HostMaestroDriverScreenState(
        maestroDriver = driver,
        setOfMarkEnabled = false,
        screenshotScalingConfig = scalingConfig,
      )
    }
  }

  /**
   * Checks if the currently selected device is using on-device instrumentation.
   */
  override fun isOnDeviceInstrumentation(): Boolean {
    val deviceId = selectedDeviceId ?: return false
    val deviceState = trailblazeDeviceManager.getDeviceState(deviceId)
    return deviceState?.device?.trailblazeDriverType == TrailblazeDriverType.ANDROID_ONDEVICE_INSTRUMENTATION
  }

  /**
   * Gets the driver type for the currently selected device.
   */
  override fun getDriverType(): TrailblazeDriverType? {
    val deviceId = selectedDeviceId ?: return null
    val deviceState = trailblazeDeviceManager.getDeviceState(deviceId)
    return deviceState?.device?.trailblazeDriverType
  }

  /**
   * Gets screen state via RPC for on-device instrumentation mode.
   * This calls the GetScreenStateRequest endpoint on the on-device agent.
   * 
   * @param includeScreenshot Whether to include screenshot bytes
   * @param filterViewHierarchy Whether to filter to interactable elements
   * @param screenshotScalingConfig Configuration for scaling/compressing screenshots on-device
   * @return GetScreenStateResponse on success, null on failure
   */
  override suspend fun getScreenStateViaRpc(
    includeScreenshot: Boolean,
    filterViewHierarchy: Boolean,
    screenshotScalingConfig: ScreenshotScalingConfig,
  ): GetScreenStateResponse? {
    val deviceId = selectedDeviceId ?: return null
    
    if (!isOnDeviceInstrumentation()) {
      return null
    }
    
    val rpcClient = OnDeviceRpcClient(
      trailblazeDeviceId = deviceId,
      sendProgressMessage = { },
    )
    
    val request = GetScreenStateRequest(
      includeScreenshot = includeScreenshot,
      filterViewHierarchy = filterViewHierarchy,
      setOfMarkEnabled = false,
      screenshotMaxDimension1 = screenshotScalingConfig.maxDimension1,
      screenshotMaxDimension2 = screenshotScalingConfig.maxDimension2,
      screenshotImageFormat = screenshotScalingConfig.imageFormat,
      screenshotCompressionQuality = screenshotScalingConfig.compressionQuality,
    )
    
    return when (val result: RpcResult<GetScreenStateResponse> = rpcClient.rpcCall(request)) {
      is RpcResult.Success -> result.data
      is RpcResult.Failure -> null
    }
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

    // Clear cached screen state before executing - the screen will have changed
    _lastScreenState = null

    // Use custom executor if provided, otherwise convert to YAML and run
    if (trailblazeToolExecutor != null) {
      return trailblazeToolExecutor.invoke(tool, trailblazeDeviceId)
    }

    // Default implementation: convert tool to YAML and run via runYaml()
    Console.log("Executing TrailblazeTool via YAML conversion: ${tool::class.simpleName}")

    val yaml = createTrailblazeYaml().encodeToString(
      TrailblazeYamlBuilder()
        .tools(listOf(tool))
        .build()
    )

    Console.log("Generated YAML:\n$yaml")

    // Reuse the existing session if available, otherwise runYaml will create one
    val sessionId = runYaml(yaml, startNewSession = false)

    return "Executed ${tool::class.simpleName} on device ${trailblazeDeviceId.instanceId} (session: $sessionId)"
  }

  override suspend fun endSession(): Boolean {
    // Clear cached screen state and persistent device when ending session
    _lastScreenState = null
    closePersistentDevice()
    val deviceId = assertDeviceIsSelected()
    val endedSessionId = trailblazeDeviceManager.endSessionForDevice(deviceId)
    return endedSessionId != null
  }

  override fun getActiveSessionId(): SessionId? {
    val deviceId = selectedDeviceId ?: return null
    return trailblazeDeviceManager.getCurrentSessionIdForDevice(deviceId)
  }

  override suspend fun ensureSessionAndGetId(): SessionId? {
    val deviceId = selectedDeviceId ?: return null
    // Use "yaml" prefix to match runYaml() - ensures we monitor the same session
    val sessionResolution = trailblazeDeviceManager.getOrCreateSessionResolution(
      trailblazeDeviceId = deviceId,
      forceNewSession = false,
      sessionIdPrefix = "yaml"
    )

    // If this is a new session, emit a TrailblazeSessionStatusChangeLog
    // This is required for the desktop app to recognize the session
    if (sessionResolution.isNewSession) {
      emitSessionStartedLog(
        sessionId = sessionResolution.sessionId,
        deviceId = deviceId,
      )
    }

    return sessionResolution.sessionId
  }

  /**
   * Emits a [TrailblazeLog.TrailblazeSessionStatusChangeLog] with [SessionStatus.Started]
   * to initialize an MCP-initiated session. This enables the desktop app to display the session.
   */
  private fun emitSessionStartedLog(
    sessionId: SessionId,
    deviceId: TrailblazeDeviceId,
  ) {
    val repo = logsRepo ?: return

    // Get device info from the device state
    val deviceState = trailblazeDeviceManager.getDeviceState(deviceId)
    val device = deviceState?.device
    val driverType = device?.trailblazeDriverType ?: TrailblazeDriverType.ANDROID_HOST

    // Try to get real device dimensions from the Maestro driver
    // Prefer persistent device, then fall back to device manager's active driver
    val maestroDriver = _persistentDevice?.getMaestroDriver()
      ?: trailblazeDeviceManager.getActiveDriverForDevice(deviceId)

    // Get device dimensions from the driver if available, otherwise use defaults
    val (widthPixels, heightPixels) = if (maestroDriver != null) {
      try {
        val maestroDeviceInfo = maestroDriver.deviceInfo()
        maestroDeviceInfo.widthPixels to maestroDeviceInfo.heightPixels
      } catch (e: Exception) {
        Console.log("[MCP Bridge] Failed to get device info: ${e.message}, using defaults")
        DEFAULT_DEVICE_WIDTH to DEFAULT_DEVICE_HEIGHT
      }
    } else {
      DEFAULT_DEVICE_WIDTH to DEFAULT_DEVICE_HEIGHT // Actual dimensions captured in subsequent screen state logs
    }

    // Compute device classifiers using TrailblazeHostDeviceClassifier
    val classifiers = if (maestroDriver != null) {
      try {
        TrailblazeHostDeviceClassifier(
          trailblazeDriverType = driverType,
          maestroDeviceInfoProvider = { maestroDriver.deviceInfo() },
        ).getDeviceClassifiers()
      } catch (e: Exception) {
        Console.log("[MCP Bridge] Failed to compute classifiers: ${e.message}")
        emptyList()
      }
    } else {
      emptyList()
    }

    val deviceInfo = TrailblazeDeviceInfo(
      trailblazeDeviceId = deviceId,
      trailblazeDriverType = driverType,
      widthPixels = widthPixels,
      heightPixels = heightPixels,
      classifiers = classifiers,
    )

    val sessionStartedStatus = SessionStatus.Started(
      trailConfig = null, // MCP sessions don't have a trail config
      trailFilePath = null,
      hasRecordedSteps = false,
      testMethodName = "mcp_session", // Synthetic test name for MCP sessions
      testClassName = "MCP",
      trailblazeDeviceInfo = deviceInfo,
      trailblazeDeviceId = deviceId,
      rawYaml = null,
    )

    val log = TrailblazeLog.TrailblazeSessionStatusChangeLog(
      sessionStatus = sessionStartedStatus,
      session = sessionId,
      timestamp = Clock.System.now(),
    )

    repo.saveLogToDisk(log)
    Console.log("[MCP Bridge] Emitted TrailblazeSessionStatusChangeLog for new session: $sessionId")
  }

  override fun cancelAutomation(deviceId: TrailblazeDeviceId) {
    Console.log("MCP Bridge: Cancelling automation on device ${deviceId.instanceId}")
    trailblazeDeviceManager.cancelSessionForDevice(deviceId)
  }

  override suspend fun runYamlBlocking(
    yaml: String,
    objectives: List<String>,
    onProgress: (String) -> Unit,
    timeoutPerObjective: Duration,
    agentImplementation: AgentImplementation,
  ): RunYamlBlockingResult {
    val repo = logsRepo ?: return RunYamlBlockingResult.NotImplemented

    try {
      // Get/create session for this execution
      val sessionId = ensureSessionAndGetId()
        ?: return RunYamlBlockingResult.Error("No device selected")

      onProgress("[session] Using session: $sessionId")

      // Start YAML execution (fires background coroutine)
      runYaml(yaml, startNewSession = false, agentImplementation = agentImplementation)

      // Wait for each objective to complete
      for (objective in objectives) {
        onProgress("[objective] Waiting for: $objective")

        val completeLog = repo.awaitLog<TrailblazeLog.ObjectiveCompleteLog>(
          sessionId = sessionId,
          timeout = timeoutPerObjective,
          skipExisting = false,
        ) { it.promptStep.prompt == objective }

        if (completeLog == null) {
          return RunYamlBlockingResult.Timeout(objective)
        }

        onProgress("[objective] Completed: $objective")
      }

      return RunYamlBlockingResult.Success(objectives.size)
    } catch (e: Exception) {
      return RunYamlBlockingResult.Error(e.message ?: e::class.simpleName ?: "Unknown error")
    }
  }

  override fun selectAppTarget(appTargetId: String): String? {
    val matchingTarget = trailblazeDeviceManager.availableAppTargets.firstOrNull { it.id == appTargetId }
      ?: return null
    trailblazeDeviceManager.settingsRepo.targetAppSelected(matchingTarget)
    return matchingTarget.displayName
  }

  override fun getCurrentAppTargetId(): String? {
    return trailblazeDeviceManager.settingsRepo.getCurrentSelectedTargetApp()?.id
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
