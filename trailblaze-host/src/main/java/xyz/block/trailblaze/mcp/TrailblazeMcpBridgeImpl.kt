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
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
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

  /**
   * Returns the effective device ID for the current execution context.
   *
   * Precedence: thread-local override (multi-session safe) → shared [selectedDeviceId] (STDIO fallback).
   */
  private fun getEffectiveDeviceId(): TrailblazeDeviceId? {
    return McpDeviceContext.currentDeviceId.get() ?: selectedDeviceId
  }

  private val _selectedDeviceIdFlow = MutableStateFlow<TrailblazeDeviceId?>(null)

  /**
   * Per-device cached screen states from the last tool execution.
   * Keyed by device instanceId. Allows multiple devices to have cached state simultaneously.
   */
  private val cachedScreenStates = ConcurrentHashMap<String, ScreenState>()

  /**
   * Per-device persistent connections for direct screen state capture.
   * Keyed by device instanceId. Each device maintains its own Maestro driver connection,
   * so switching devices doesn't destroy existing connections.
   */
  private val persistentDevices = ConcurrentHashMap<String, TrailblazeConnectedDevice>()

  /** Per-device locks to prevent two threads from simultaneously opening Maestro connections. */
  private val persistentDeviceLocks = ConcurrentHashMap<String, Any>()

  /**
   * Per-device latches for callers waiting on in-progress driver creation.
   * When a driver is being created in the background, callers (e.g., getDirectScreenStateProvider)
   * can wait on this latch instead of returning null immediately.
   */
  private val driverCreationLatches = ConcurrentHashMap<String, CountDownLatch>()

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

    // Create persistent Maestro driver connection on a background thread with a timeout.
    // The Maestro driver install + startup can take 30-90s (installs APK, starts instrumentation).
    // We use a real thread + CountDownLatch because the blocking driver creation code
    // (synchronized blocks, Thread.sleep) is not cooperative with coroutine cancellation.
    // If the driver isn't ready within the timeout, the tool call returns success anyway —
    // the driver continues creating in the background and will be available for later calls.
    val key = trailblazeDeviceId.instanceId
    if (!persistentDevices.containsKey(key)) {
      // Reuse existing latch if driver creation is already in progress (e.g., preselectDeviceForSession
      // already started it). This prevents spawning redundant daemon threads.
      val existingLatch = driverCreationLatches[key]
      if (existingLatch != null) {
        // Another call already started driver creation — just wait on the existing latch
        if (!existingLatch.await(DEVICE_CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
          Console.log(
            "[MCP Bridge] Persistent device connection still initializing for $key " +
              "after ${DEVICE_CONNECT_TIMEOUT_SECONDS}s — continuing without it"
          )
        }
      } else {
        val latch = CountDownLatch(1)
        // putIfAbsent returns null if we won the race (we should start the thread),
        // or the existing latch if another thread beat us.
        val raceLatch = driverCreationLatches.putIfAbsent(key, latch)
        if (raceLatch != null) {
          // Another thread created the latch between our check and putIfAbsent — wait on theirs
          if (!raceLatch.await(DEVICE_CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            Console.log(
              "[MCP Bridge] Persistent device connection still initializing for $key " +
                "after ${DEVICE_CONNECT_TIMEOUT_SECONDS}s — continuing without it"
            )
          }
        } else {
          val driverThread = Thread {
            try {
              synchronized(persistentDeviceLocks.computeIfAbsent(key) { Any() }) {
                if (!persistentDevices.containsKey(key)) {
                  createPersistentDevice(trailblazeDeviceId)?.let { device ->
                    persistentDevices[key] = device
                  }
                }
              }
            } finally {
              latch.countDown()
              driverCreationLatches.remove(key)
            }
          }
          driverThread.name = "mcp-driver-init-$key"
          driverThread.isDaemon = true
          driverThread.start()

          if (!latch.await(DEVICE_CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            Console.log(
              "[MCP Bridge] Persistent device connection still initializing for $key " +
                "after ${DEVICE_CONNECT_TIMEOUT_SECONDS}s — continuing without it"
            )
          }
        }
      }
    }

    return trailblazeDeviceManager.getDeviceState(trailblazeDeviceId)?.device
      ?: error("Device $trailblazeDeviceId is not available.")
  }

  companion object {
    /**
     * How long to wait for the Maestro driver during device connect.
     * Short timeout so the tool call returns fast. If the driver isn't ready,
     * it continues initializing in the background and will be available for later calls.
     */
    private const val DEVICE_CONNECT_TIMEOUT_SECONDS = 5L

    /**
     * How long getDirectScreenStateProvider() waits for an in-progress driver creation
     * before returning null. Set to 0 — no wait.
     *
     * The wait is a BLOCKING call (CountDownLatch.await) inside a non-cooperative
     * runBlocking, so it cannot be cancelled by withTimeoutOrNull. Any non-zero value
     * directly delays blaze/verify/ask responses, causing Claude Code to time out and
     * close the STDIO connection with "Connection closed" (-32000).
     *
     * When the persistent device isn't ready yet, return null immediately and let
     * captureScreenState fall through to session-based capture as a backup.
     */
    private const val DRIVER_READY_WAIT_SECONDS = 0L
  }

  /**
   * Creates a persistent Maestro driver connection for the specified device.
   * This enables direct, fast screen state capture without session overhead.
   *
   * @return The connected device, or a sentinel null that will not be stored by computeIfAbsent.
   */
  private fun createPersistentDevice(trailblazeDeviceId: TrailblazeDeviceId): TrailblazeConnectedDevice? {
    return try {
      val connectedDevice = TrailblazeDeviceService.getConnectedDevice(
        trailblazeDeviceId = trailblazeDeviceId,
        appTarget = trailblazeDeviceManager.getCurrentSelectedTargetApp()
      )
      connectedDevice
    } catch (e: Exception) {
      // Don't fail device selection if persistent connection fails - fallback will be used
      Console.log("[MCP Bridge] Persistent device connection failed (fallback will be used): ${e.message}")
      null
    }
  }

  /**
   * Closes the persistent device connection for a specific device.
   */
  private fun closePersistentDevice(deviceId: TrailblazeDeviceId) {
    val key = deviceId.instanceId
    // Cancel any in-progress driver creation latch so waiters don't block forever
    driverCreationLatches.remove(key)?.countDown()
    // Synchronize on the same lock used by selectDevice() to prevent a race where
    // another thread creates a new connection between our remove and the lock cleanup.
    // We intentionally never remove from persistentDeviceLocks — they are tiny Any objects
    // and removing them would allow a concurrent selectDevice() to create a new lock,
    // bypassing mutual exclusion.
    val lock = persistentDeviceLocks.computeIfAbsent(key) { Any() }
    synchronized(lock) {
      persistentDevices.remove(key)?.let { device ->
        try {
          device.getMaestroDriver().close()
        } catch (e: Exception) {
          Console.log("[MCP Bridge] Exception closing persistent device $key (already closed?): ${e.message}")
        }
      }
    }
  }

  override fun releasePersistentDeviceConnection(deviceId: TrailblazeDeviceId) {
    cachedScreenStates.remove(deviceId.instanceId)
    closePersistentDevice(deviceId)
    Console.log("[MCP Bridge] Released persistent connection for device ${deviceId.instanceId}")
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
    return getEffectiveDeviceId()
  }

  override suspend fun getCurrentScreenState(): ScreenState? {
    val trailblazeDeviceId = assertDeviceIsSelected()
    val key = trailblazeDeviceId.instanceId

    // Return cached state if available (from last tool execution)
    cachedScreenStates[key]?.let { return it }

    // Otherwise capture fresh state (creates a new session)
    return trailblazeDeviceManager.getCurrentScreenState(trailblazeDeviceId).also {
      if (it != null) cachedScreenStates[key] = it
    }
  }

  /**
   * Returns the cached screen state without capturing a new one.
   * Returns null if no cached state is available.
   */
  fun getCachedScreenState(): ScreenState? {
    val key = getEffectiveDeviceId()?.instanceId ?: return null
    return cachedScreenStates[key]
  }

  /**
   * Clears the cached screen state for the currently selected device.
   * Call this when you want to force a fresh capture on next request.
   */
  fun clearCachedScreenState() {
    val key = getEffectiveDeviceId()?.instanceId ?: return
    cachedScreenStates.remove(key)
  }

  override fun getDirectScreenStateProvider(): ((ScreenshotScalingConfig) -> ScreenState)? {
    val deviceId = getEffectiveDeviceId() ?: return null
    val key = deviceId.instanceId

    // Use persistent device connection if available (preferred - always ready)
    persistentDevices[key]?.let { device ->
      val driver = device.getMaestroDriver()
      return { scalingConfig ->
        HostMaestroDriverScreenState(
          maestroDriver = driver,
          setOfMarkEnabled = false,
          screenshotScalingConfig = scalingConfig,
        )
      }
    }

    // If driver creation is in progress, wait for it rather than returning null.
    // This handles the common case where device(action=ANDROID) returned before the
    // Maestro driver was ready, and now blaze/ask/verify needs it.
    driverCreationLatches[key]?.let { latch ->
      Console.log("[MCP Bridge] Waiting for persistent device driver for $key...")
      if (latch.await(DRIVER_READY_WAIT_SECONDS, TimeUnit.SECONDS)) {
        // Driver creation finished — check if it succeeded
        persistentDevices[key]?.let { device ->
          Console.log("[MCP Bridge] Persistent device driver ready for $key")
          val driver = device.getMaestroDriver()
          return { scalingConfig ->
            HostMaestroDriverScreenState(
              maestroDriver = driver,
              setOfMarkEnabled = false,
              screenshotScalingConfig = scalingConfig,
            )
          }
        }
        // Driver creation finished but failed (createPersistentDevice returned null)
        Console.log("[MCP Bridge] Persistent device driver creation failed for $key")
      } else {
        Console.log("[MCP Bridge] Timed out waiting for persistent device driver for $key")
      }
    }

    // Fallback: use active driver from device manager (only available during YAML execution)
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
    val deviceId = getEffectiveDeviceId() ?: return false
    val deviceState = trailblazeDeviceManager.getDeviceState(deviceId)
    return deviceState?.device?.trailblazeDriverType == TrailblazeDriverType.ANDROID_ONDEVICE_INSTRUMENTATION
  }

  /**
   * Gets the driver type for the currently selected device.
   */
  override fun getDriverType(): TrailblazeDriverType? {
    val deviceId = getEffectiveDeviceId() ?: return null
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
    val deviceId = getEffectiveDeviceId() ?: return null

    if (!isOnDeviceInstrumentation()) {
      return null
    }
    
    val rpcClient = OnDeviceRpcClient(
      trailblazeDeviceId = deviceId,
      sendProgressMessage = { },
    )

    return try {
      val request = GetScreenStateRequest(
        includeScreenshot = includeScreenshot,
        filterViewHierarchy = filterViewHierarchy,
        setOfMarkEnabled = false,
        screenshotMaxDimension1 = screenshotScalingConfig.maxDimension1,
        screenshotMaxDimension2 = screenshotScalingConfig.maxDimension2,
        screenshotImageFormat = screenshotScalingConfig.imageFormat,
        screenshotCompressionQuality = screenshotScalingConfig.compressionQuality,
      )

      when (val result: RpcResult<GetScreenStateResponse> = rpcClient.rpcCall(request)) {
        is RpcResult.Success -> result.data
        is RpcResult.Failure -> null
      }
    } finally {
      rpcClient.close()
    }
  }

  override suspend fun getAvailableDevices(): Set<TrailblazeConnectedDeviceSummary> {
    val availableDevices = trailblazeDeviceManager.loadDevicesSuspend(applyDriverFilter = false).toSet()
    return availableDevices
  }

  override suspend fun getInstalledAppIds(): Set<String> {
    val trailblazeDeviceId = assertDeviceIsSelected()
    return trailblazeDeviceManager.getInstalledAppIdsFlow(trailblazeDeviceId).value
  }

  override suspend fun executeTrailblazeTool(tool: TrailblazeTool): String {
    val trailblazeDeviceId = assertDeviceIsSelected()

    // Clear cached screen state before executing - the screen will have changed
    cachedScreenStates.remove(trailblazeDeviceId.instanceId)

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
    val deviceId = assertDeviceIsSelected()
    // Clear cached screen state and persistent device for this device
    cachedScreenStates.remove(deviceId.instanceId)
    closePersistentDevice(deviceId)
    val endedSessionId = trailblazeDeviceManager.endSessionForDevice(deviceId)
    return endedSessionId != null
  }

  override fun getActiveSessionId(): SessionId? {
    val deviceId = getEffectiveDeviceId() ?: return null
    return trailblazeDeviceManager.getCurrentSessionIdForDevice(deviceId)
  }

  override suspend fun ensureSessionAndGetId(): SessionId? {
    val deviceId = getEffectiveDeviceId() ?: return null
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
    val maestroDriver = persistentDevices[deviceId.instanceId]?.getMaestroDriver()
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

  override fun getConfiguredDriverType(platform: TrailblazeDevicePlatform): TrailblazeDriverType? {
    return trailblazeDeviceManager.settingsRepo.serverStateFlow.value
      .appConfig.selectedTrailblazeDriverTypes[platform]
  }

  override fun selectDeviceForSession(deviceId: TrailblazeDeviceId) {
    McpDeviceContext.currentDeviceId.set(deviceId)
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // Configuration access (for config MCP tool)
  // ─────────────────────────────────────────────────────────────────────────────

  override fun setConfiguredDriverType(
    platform: TrailblazeDevicePlatform,
    driverType: TrailblazeDriverType,
  ): String? {
    trailblazeDeviceManager.settingsRepo.updateAppConfig { config ->
      config.copy(
        selectedTrailblazeDriverTypes = config.selectedTrailblazeDriverTypes + (platform to driverType),
      )
    }
    return null
  }

  override fun getLlmConfig(): Pair<String, String>? {
    val config = trailblazeDeviceManager.settingsRepo.serverStateFlow.value.appConfig
    return config.llmProvider to config.llmModel
  }

  override fun setLlmConfig(provider: String?, model: String?): String? {
    trailblazeDeviceManager.settingsRepo.updateAppConfig { config ->
      config.copy(
        llmProvider = provider ?: config.llmProvider,
        llmModel = model ?: config.llmModel,
      )
    }
    return null
  }

  override fun getAgentImplementation(): AgentImplementation {
    return trailblazeDeviceManager.settingsRepo.serverStateFlow.value.appConfig.agentImplementation
  }

  override fun setAgentImplementation(implementation: AgentImplementation): String? {
    trailblazeDeviceManager.settingsRepo.updateAppConfig { config ->
      config.copy(agentImplementation = implementation)
    }
    return null
  }

  private suspend fun assertDeviceIsSelected(requestedDeviceId: TrailblazeDeviceId? = null): TrailblazeDeviceId {
    // If a specific device is requested, validate and select it
    if (requestedDeviceId != null) {
      // Check if already selected
      if (getEffectiveDeviceId() == requestedDeviceId) {
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

    // No specific device requested - use currently selected if available.
    // Capture in a local val to avoid TOCTOU race (another thread could null it between check and use).
    val currentDeviceId = getEffectiveDeviceId()
    if (currentDeviceId != null) {
      return currentDeviceId
    }

    // No device selected - pick the first available one
    val firstDevice = getAvailableDevices().firstOrNull()
      ?: error("No devices are connected, please connect a device to continue.")

    return firstDevice.trailblazeDeviceId.also {
      selectedDeviceId = it
    }
  }
}
