package xyz.block.trailblaze.ui

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import xyz.block.trailblaze.ui.composables.DeviceClassifierIconProvider
import maestro.Driver
import xyz.block.trailblaze.api.ScreenState
import xyz.block.trailblaze.api.TrailblazeElementSelector
import xyz.block.trailblaze.api.ViewHierarchyTreeNode
import xyz.block.trailblaze.devices.TrailblazeConnectedDeviceSummary
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.host.devices.HostWebDriverFactory
import xyz.block.trailblaze.host.devices.HostWebDriverFactory.Companion.DEFAULT_PLAYWRIGHT_WEB_TRAILBLAZE_DEVICE_ID
import xyz.block.trailblaze.host.devices.WebBrowserManager
import xyz.block.trailblaze.host.devices.WebBrowserState
import xyz.block.trailblaze.llm.RunYamlRequest
import xyz.block.trailblaze.llm.TrailblazeLlmModel
import xyz.block.trailblaze.llm.TrailblazeReferrer
import xyz.block.trailblaze.logs.client.TrailblazeLog
import xyz.block.trailblaze.logs.client.TrailblazeSessionManager
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.logs.model.SessionStatus
import xyz.block.trailblaze.model.DesktopAppRunYamlParams
import xyz.block.trailblaze.model.TrailblazeConfig
import xyz.block.trailblaze.model.TrailblazeHostAppTarget
import xyz.block.trailblaze.report.utils.LogsRepo
import xyz.block.trailblaze.toolcalls.TrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.AssertVisibleBySelectorTrailblazeTool
import xyz.block.trailblaze.ui.devices.DeviceManagerState
import xyz.block.trailblaze.ui.devices.DeviceState
import xyz.block.trailblaze.ui.models.AppIconProvider
import xyz.block.trailblaze.yaml.TrailblazeYaml
import xyz.block.trailblaze.yaml.models.TrailblazeYamlBuilder
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import maestro.device.Device as MaestroDevice
import maestro.device.DeviceService as MaestroDeviceService
import maestro.device.Platform as MaestroPlatform

/**
 * Manages device discovery, selection, and state across the application.
 * Can be shared across multiple Composables to maintain consistent device state.
 */
class TrailblazeDeviceManager(
  val logsRepo: LogsRepo,
  val settingsRepo: TrailblazeSettingsRepo,
  val defaultHostAppTarget: TrailblazeHostAppTarget,
  val currentTrailblazeLlmModelProvider: () -> TrailblazeLlmModel,
  val availableAppTargets: Set<TrailblazeHostAppTarget>,
  val appIconProvider: AppIconProvider,
  val deviceClassifierIconProvider: DeviceClassifierIconProvider,
  private val runYamlLambda: (desktopAppRunYamlParams: DesktopAppRunYamlParams) -> Unit,
  private val installedAppIdsProviderBlocking: (TrailblazeDeviceId) -> Set<String>,
  val onDeviceInstrumentationArgsProvider: () -> Map<String, String>,
  private val trailblazeAnalytics: TrailblazeAnalytics,
) {

  /**
   * Manages the web browser lifecycle for web testing.
   * Use [launchWebBrowser] and [closeWebBrowser] to control the browser.
   */
  val webBrowserManager = WebBrowserManager()

  /**
   * Exposes the web browser state for UI observation.
   */
  val webBrowserStateFlow: StateFlow<WebBrowserState> = webBrowserManager.browserStateFlow

  /**
   * Launches a web browser for testing.
   * The browser will appear as a device in the device list once running.
   *
   * @return true if the browser was launched successfully
   */
  fun launchWebBrowser(): Boolean {
    val device = webBrowserManager.launchBrowser()
    if (device != null) {
      // Refresh device list to include the new browser
      loadDevices()
      return true
    }
    return false
  }

  /**
   * Closes the web browser.
   * The browser will be removed from the device list.
   */
  fun closeWebBrowser() {
    webBrowserManager.closeBrowser()
    // Refresh device list to remove the browser
    loadDevices()
  }

  private val targetDeviceFilter: (List<TrailblazeConnectedDeviceSummary>) -> List<TrailblazeConnectedDeviceSummary> =
    { connectedDeviceSummaries ->
      connectedDeviceSummaries.filter { connectedDeviceSummary ->
        settingsRepo.getEnabledDriverTypes().contains(connectedDeviceSummary.trailblazeDriverType)
      }
    }

  private val _deviceStateFlow = MutableStateFlow(DeviceManagerState())
  val deviceStateFlow: StateFlow<DeviceManagerState> = _deviceStateFlow.asStateFlow()

  /**
   * Tracks active session IDs by device ID.
   * Separate from device summaries since sessions can exist for devices
   * we don't have full summaries for yet, and the mapping is a different concern.
   */
  private val _activeDeviceSessionsFlow = MutableStateFlow<Map<TrailblazeDeviceId, SessionId>>(emptyMap())
  val activeDeviceSessionsFlow: StateFlow<Map<TrailblazeDeviceId, SessionId>> = _activeDeviceSessionsFlow.asStateFlow()

  /**
   * Tracks installed app IDs per device.
   * Populated when loadDevices() is called.
   */
  private val _installedAppIdsByDeviceFlow = MutableStateFlow<Map<TrailblazeDeviceId, Set<String>>>(emptyMap())
  val installedAppIdsByDeviceFlow: StateFlow<Map<TrailblazeDeviceId, Set<String>>> =
    _installedAppIdsByDeviceFlow.asStateFlow()

  private val loadDevicesScope = CoroutineScope(Dispatchers.IO)

  // Track session IDs we've already processed to detect new sessions
  private val knownSessionIds = mutableSetOf<SessionId>()

  init {
    // Monitor sessionInfoFlow for new sessions and ended sessions to update device state
    loadDevicesScope.launch {
      logsRepo.sessionInfoFlow.collect { sessionInfos ->
        sessionInfos.forEach { sessionInfo ->
          val sessionId = sessionInfo.sessionId
          val deviceId = sessionInfo.trailblazeDeviceId

          // If this is a new session with a device ID, update the device state
          if (sessionId !in knownSessionIds && deviceId != null) {
            knownSessionIds.add(sessionId)
            trackActiveSession(deviceId, sessionId)
          }

          // If session has ended, clear it from device state so next call creates a new session
          if (sessionInfo.latestStatus is SessionStatus.Ended && deviceId != null) {
            clearEndedSessionFromDevice(deviceId, sessionId)
          }
        }
      }
    }
  }

  suspend fun runYaml(
    yamlToRun: String,
    trailblazeDeviceId: TrailblazeDeviceId,
    sendSessionStartLog: Boolean,
    sendSessionEndLog: Boolean,
    existingSessionId: SessionId?,
    forceStopTargetApp: Boolean = false,
    referrer: TrailblazeReferrer
  ) {
    val settingsState = settingsRepo.serverStateFlow.value
    val runYamlRequest = RunYamlRequest(
      yaml = yamlToRun,
      // Use title with ID appended for method name (e.g., for_your_business_page_5374142)
      // The class name will be auto-derived from testSectionName metadata
      testName = "test",
      useRecordedSteps = false,
      trailblazeLlmModel = currentTrailblazeLlmModelProvider(),
      targetAppName = settingsState.appConfig.selectedTargetAppName,
      trailFilePath = null,
      config = TrailblazeConfig(
        overrideSessionId = existingSessionId,
        sendSessionStartLog = sendSessionStartLog,
        sendSessionEndLog = sendSessionEndLog,
        setOfMarkEnabled = settingsState.appConfig.setOfMarkEnabled,
      ),
      trailblazeDeviceId = trailblazeDeviceId,
      referrer = referrer
    )
    val params = DesktopAppRunYamlParams(
      forceStopTargetApp = forceStopTargetApp,
      runYamlRequest = runYamlRequest,
      targetTestApp = this.getCurrentSelectedTargetApp(),
      onProgressMessage = {},
      onConnectionStatus = {},
      additionalInstrumentationArgs = onDeviceInstrumentationArgsProvider()
    )

    runYamlLambda(params)
    // Wait until the first session log has bene received for this session
    awaitSessionForDevice(trailblazeDeviceId)
  }

  /**
   * Result of resolving/creating a session for a device.
   */
  data class DeviceSessionResolution(
    val sessionId: SessionId,
    val isNewSession: Boolean
  )

  /**
   * Gets the current session for a device, or creates a new one if none exists.
   * Automatically tracks the session in the device state.
   *
   * @param trailblazeDeviceId The device to get/create a session for
   * @param forceNewSession If true, always creates a new session even if one exists
   * @param sessionIdPrefix Prefix for generated session IDs (e.g., "tool", "yaml")
   * @param deviceSummary Optional device summary for creating DeviceState if device isn't tracked yet
   */
  fun getOrCreateSessionResolution(
    trailblazeDeviceId: TrailblazeDeviceId,
    forceNewSession: Boolean = false,
    sessionIdPrefix: String = "session",
    deviceSummary: TrailblazeConnectedDeviceSummary? = null
  ): DeviceSessionResolution {
    val existingSessionId = if (forceNewSession) null else getCurrentSessionIdForDevice(trailblazeDeviceId)
    val isNewSession = existingSessionId == null
    val sessionId = existingSessionId ?: TrailblazeSessionManager.generateSessionId(sessionIdPrefix)

    // Track the session ID immediately so subsequent calls can find it
    if (isNewSession) {
      trackActiveSession(trailblazeDeviceId, sessionId, deviceSummary)
    }

    return DeviceSessionResolution(sessionId, isNewSession)
  }

  /**
   * Ends the current session for a device.
   * Clears the session ID from activeDeviceSessionsFlow and writes a session end log.
   * Use [cancelSessionForDevice] if you need to forcefully stop a running test.
   *
   * @param trailblazeDeviceId The device to end the session for
   * @return The session ID that was ended, or null if no session was active
   */
  fun endSessionForDevice(trailblazeDeviceId: TrailblazeDeviceId): SessionId? {
    val sessionId = getCurrentSessionIdForDevice(trailblazeDeviceId) ?: return null

    // Clear the session from the sessions flow
    _activeDeviceSessionsFlow.value -= trailblazeDeviceId

    println("Ended session $sessionId for device: ${trailblazeDeviceId.instanceId}")

    // Write session end log
    try {
      val sessionEndLog = TrailblazeLog.TrailblazeSessionStatusChangeLog(
        session = sessionId,
        timestamp = kotlinx.datetime.Clock.System.now(),
        sessionStatus = SessionStatus.Ended.Succeeded(durationMs = 0L),
      )
      logsRepo.saveLogToDisk(sessionEndLog)
    } catch (e: Exception) {
      println("Failed to write session end log: ${e.message}")
      // Don't fail session end if log write fails
    }

    return sessionId
  }

  suspend fun runTool(
    trailblazeDeviceId: TrailblazeDeviceId,
    trailblazeTool: TrailblazeTool,
    referrer: TrailblazeReferrer
  ) {
    val yaml = TrailblazeYaml.Default.encodeToString(
      TrailblazeYamlBuilder()
        .tools(listOf(trailblazeTool))
        .build()
    )
    val session = getOrCreateSessionResolution(trailblazeDeviceId, sessionIdPrefix = "tool")

    runYaml(
      yamlToRun = yaml,
      trailblazeDeviceId = trailblazeDeviceId,
      sendSessionStartLog = session.isNewSession,
      sendSessionEndLog = false,
      existingSessionId = session.sessionId,
      referrer = referrer
    )
  }

  suspend fun awaitSessionForDevice(
    trailblazeDeviceId: TrailblazeDeviceId,
    timeout: Duration = 30.seconds,
  ): SessionId? {
    val currentSession = getCurrentSessionIdForDevice(trailblazeDeviceId)
    if (currentSession != null) {
      return currentSession
    }
    // If null, wait for up to the timeout length. Subscribe to sessionInfoFlow until a matching session appears.
    return withTimeoutOrNull(timeout) {
      logsRepo.sessionInfoFlow
        .mapNotNull { sessionInfos ->
          sessionInfos.firstOrNull { it.trailblazeDeviceId == trailblazeDeviceId }?.sessionId
        }
        .first()
    }
  }

  /**
   * This does a always successful assertion which captures the view hierarchy and screenshot.
   *
   * This allows us to query the screen state and use the data written to the log file.
   *
   * This method is used from the MCP server.
   */
  suspend fun getCurrentScreenState(trailblazeDeviceId: TrailblazeDeviceId): ScreenState? {
    CoroutineScope(currentCoroutineContext()).launch {
      runTool(
        trailblazeDeviceId = trailblazeDeviceId,
        trailblazeTool = AssertVisibleBySelectorTrailblazeTool(
          selector = TrailblazeElementSelector(
            index = "0"
          )
        ),
        referrer = TrailblazeReferrer.MCP
      )
    }
    val sessionIdForDevice = awaitSessionForDevice(trailblazeDeviceId)
    sessionIdForDevice?.let { sessionId ->
      val newLog = logsRepo.awaitLog<TrailblazeLog.MaestroDriverLog>(
        sessionId = sessionId,
        skipExisting = true
      ) { log ->
        // some logic will go here, true for default
        true

      }
      if (newLog != null) {
        val screenshotFile = logsRepo.getScreenshotFile(newLog)
        if (screenshotFile != null) {
          val viewHierarchy = newLog.viewHierarchy!!
          return object : ScreenState {
            override val screenshotBytes: ByteArray = screenshotFile.readBytes()
            override val deviceWidth: Int = newLog.deviceWidth
            override val deviceHeight: Int = newLog.deviceHeight
            override val viewHierarchyOriginal: ViewHierarchyTreeNode = viewHierarchy
            override val viewHierarchy: ViewHierarchyTreeNode = viewHierarchy
            override val trailblazeDevicePlatform: TrailblazeDevicePlatform =
              trailblazeDeviceId.trailblazeDevicePlatform
          }
        }
      }
    }
    return null
  }

  /**
   * Load available devices from the system (suspend version).
   * This will update the deviceStateFlow with the discovered devices and cache installed app IDs.
   */
  suspend fun loadDevicesSuspend(): List<TrailblazeConnectedDeviceSummary> {
    withContext(Dispatchers.Default) {
      updateDeviceState { currDeviceState ->
        currDeviceState.copy(isLoading = true, error = null)
      }
    }

    try {
      val devices: List<MaestroDevice.Connected> = MaestroDeviceService.listConnectedDevices()

      val allDevices = buildList {
        // Connected iOS and Android Devices
        devices.forEach { maestroConnectedDevice: MaestroDevice.Connected ->
          when (maestroConnectedDevice.platform) {
            MaestroPlatform.ANDROID -> {
              add(
                TrailblazeConnectedDeviceSummary(
                  trailblazeDriverType = TrailblazeDriverType.ANDROID_ONDEVICE_INSTRUMENTATION,
                  instanceId = maestroConnectedDevice.instanceId,
                  description = maestroConnectedDevice.description,
                )
              )
              add(
                TrailblazeConnectedDeviceSummary(
                  trailblazeDriverType = TrailblazeDriverType.ANDROID_HOST,
                  instanceId = maestroConnectedDevice.instanceId,
                  description = maestroConnectedDevice.description,
                )
              )
            }

            MaestroPlatform.IOS -> {
              add(
                TrailblazeConnectedDeviceSummary(
                  trailblazeDriverType = TrailblazeDriverType.IOS_HOST,
                  instanceId = maestroConnectedDevice.instanceId,
                  description = maestroConnectedDevice.description,
                )
              )
            }

            MaestroPlatform.WEB -> {
              // Web devices from Maestro DeviceService are not used - we manage web browsers ourselves
              // via WebBrowserManager
            }
          }
        }

        // Include web browser device only if the browser is currently running
        webBrowserManager.getRunningBrowserSummary()?.let { browserSummary ->
          add(browserSummary)
        }
      }

      val filteredDevices = targetDeviceFilter(allDevices)

      // Query installed app IDs for each device
      val installedAppIdsByDevice: Map<TrailblazeDeviceId, Set<String>> = filteredDevices.associate { device ->
        device.trailblazeDeviceId to installedAppIdsProviderBlocking(device.trailblazeDeviceId)
      }
      _installedAppIdsByDeviceFlow.value = installedAppIdsByDevice

      withContext(Dispatchers.Default) {
        updateDeviceState { currState ->
          // Create DeviceState for each device (sessions tracked separately in activeDeviceSessionsFlow)
          val newDeviceStates: Map<TrailblazeDeviceId, DeviceState> = filteredDevices.associate { device ->
            device.trailblazeDeviceId to DeviceState(device = device)
          }

          currState.copy(
            devices = newDeviceStates,
            isLoading = false,
            error = null
          )
        }
      }

      return filteredDevices
    } catch (e: Exception) {
      updateDeviceState { deviceState ->
        deviceState.copy(
          devices = emptyMap(),
          isLoading = false,
          error = e.message ?: "Unknown error loading devices"
        )
      }
      throw e
    }
  }

  /**
   * Load available devices from the system.
   * This will update the deviceStateFlow with the discovered devices.
   */
  fun loadDevices() = loadDevicesScope.launch { loadDevicesSuspend() }

  fun updateDeviceState(deviceStateUpdater: (DeviceManagerState) -> DeviceManagerState) {
    _deviceStateFlow.value = deviceStateUpdater(_deviceStateFlow.value)
  }

  /**
   * Tracks a session on a device.
   * Updates activeDeviceSessionsFlow. If deviceSummary is provided and device isn't
   * already tracked, also adds the device to the devices map.
   */
  fun trackActiveSession(
    trailblazeDeviceId: TrailblazeDeviceId,
    sessionId: SessionId,
    deviceSummary: TrailblazeConnectedDeviceSummary? = null
  ) {
    // Update the session mapping
    _activeDeviceSessionsFlow.value += (trailblazeDeviceId to sessionId)

    // Optionally add device to devices map if not present and summary provided
    if (deviceSummary != null && deviceStateFlow.value.devices[trailblazeDeviceId] == null) {
      updateDeviceState { state ->
        state.copy(
          devices = state.devices + (trailblazeDeviceId to DeviceState(device = deviceSummary))
        )
      }
    }
  }

  /**
   * Clears an ended session from activeDeviceSessionsFlow.
   * Only clears if the session ID matches the current session for the device
   * (to avoid clearing a newer session that started after this one ended).
   */
  private fun clearEndedSessionFromDevice(
    trailblazeDeviceId: TrailblazeDeviceId,
    endedSessionId: SessionId
  ) {
    // Only clear if this is the current session for the device
    if (_activeDeviceSessionsFlow.value[trailblazeDeviceId] == endedSessionId) {
      _activeDeviceSessionsFlow.value -= trailblazeDeviceId
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

    // Clear the session from the sessions flow
    _activeDeviceSessionsFlow.value = _activeDeviceSessionsFlow.value - trailblazeDeviceId
  }

  private fun closeAndRemoveMaestroDriverForDevice(trailblazeDeviceId: TrailblazeDeviceId) {
    // Step 1: Get the running test and KILL its driver (kills child processes)
    maestroDriverByDeviceMap[trailblazeDeviceId]?.let { maestroDriver ->
      try {
        // For web browsers managed by WebBrowserManager, don't close the browser - just reset the session.
        // This allows the browser to stay open between test runs for debugging.
        // The user can explicitly close the browser via the UI when done.
        val isWebBrowserManagedByUs = trailblazeDeviceId == DEFAULT_PLAYWRIGHT_WEB_TRAILBLAZE_DEVICE_ID &&
            webBrowserManager.isRunning()

        if (isWebBrowserManagedByUs) {
          println("Web browser managed by WebBrowserManager - keeping browser open, resetting session")
          // Reset the browser session (clear cookies, navigate to about:blank, etc.)
          // but don't close the browser window.
          // The driver in maestroDriverByDeviceMap is wrapped in a LoggingDriver, so we need to
          // get the actual MaestroPlaywrightDriver from the HostWebDriverFactory's cache.
          // Calling getOrCreateDriver with resetSession=true will reset the session without closing.
          HostWebDriverFactory.getOrCreateDriver(headless = false, resetSession = true)
          println("Browser session reset successfully")
        } else {
          println("Forcefully closing driver for device: ${trailblazeDeviceId.instanceId}")
          // This closes the underlying driver and kills child processes (XCUITest, adb, etc.)
          maestroDriver.close()
          println("Driver closed successfully for device: ${trailblazeDeviceId.instanceId}")
        }
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

  fun getCurrentSessionIdForDevice(trailblazeDeviceId: TrailblazeDeviceId): SessionId? {
    return _activeDeviceSessionsFlow.value[trailblazeDeviceId]
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

  fun getInstalledAppIdsFlow(trailblazeDeviceId: TrailblazeDeviceId): StateFlow<Set<String>> {
    return installedAppIdsByDeviceFlow.map { it[trailblazeDeviceId] ?: emptySet() }
      .stateIn(
        loadDevicesScope,
        SharingStarted.Eagerly,
        installedAppIdsByDeviceFlow.value[trailblazeDeviceId] ?: emptySet()
      )
  }
}
