package xyz.block.trailblaze.ui

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
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
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.HttpURLConnection
import java.net.URI
import java.util.concurrent.Callable
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import maestro.Driver
import xyz.block.trailblaze.api.ScreenState
import xyz.block.trailblaze.mcp.AgentImplementation
import xyz.block.trailblaze.api.ViewHierarchyTreeNode
import xyz.block.trailblaze.devices.TrailblazeConnectedDeviceSummary
import xyz.block.trailblaze.devices.TrailblazeDeviceClassifier
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.devices.TrailblazeDevicePort
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.host.devices.WebBrowserManager
import xyz.block.trailblaze.host.devices.WebBrowserState
import xyz.block.trailblaze.host.screenstate.HostMaestroDriverScreenState
import xyz.block.trailblaze.llm.RunYamlRequest
import xyz.block.trailblaze.llm.TrailblazeLlmModel
import xyz.block.trailblaze.llm.TrailblazeReferrer
import xyz.block.trailblaze.logs.client.TrailblazeLog
import xyz.block.trailblaze.logs.client.TrailblazeSessionManager
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.logs.model.SessionStatus
import xyz.block.trailblaze.mcp.android.ondevice.rpc.GetScreenStateRequest
import xyz.block.trailblaze.mcp.android.ondevice.rpc.OnDeviceRpcClient
import xyz.block.trailblaze.mcp.android.ondevice.rpc.RpcResult
import xyz.block.trailblaze.model.AppVersionInfo
import xyz.block.trailblaze.model.DesktopAppRunYamlParams
import xyz.block.trailblaze.model.TrailExecutionResult
import xyz.block.trailblaze.model.TrailblazeConfig
import xyz.block.trailblaze.model.TrailblazeHostAppTarget
import xyz.block.trailblaze.report.utils.LogsRepo
import xyz.block.trailblaze.toolcalls.TrailblazeTool
import xyz.block.trailblaze.ui.composables.DeviceClassifierIconProvider
import xyz.block.trailblaze.ui.devices.DeviceManagerState
import xyz.block.trailblaze.ui.devices.DeviceState
import xyz.block.trailblaze.ui.models.AppIconProvider
import xyz.block.trailblaze.ui.models.TrailblazeServerState
import xyz.block.trailblaze.yaml.createTrailblazeYaml
import xyz.block.trailblaze.yaml.models.TrailblazeYamlBuilder
import java.util.Base64
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import xyz.block.trailblaze.host.rules.BasePlaywrightElectronTest
import xyz.block.trailblaze.host.rules.BasePlaywrightNativeTest
import xyz.block.trailblaze.util.Console
import xyz.block.trailblaze.util.isMacOs
import xyz.block.trailblaze.revyl.RevylCliClient
import xyz.block.trailblaze.revyl.RevylScreenState

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
  private val appVersionInfoProviderBlocking: (TrailblazeDeviceId, String) -> AppVersionInfo? = { _, _ -> null },
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
   * Launches a web browser for testing asynchronously.
   * The browser will appear as a device in the device list once running.
   * Browser state can be observed via [webBrowserStateFlow].
   */
  fun launchWebBrowser() {
    webBrowserManager.launchBrowser {
      // Refresh device list to include the new browser
      loadDevices()
    }
  }

  /**
   * Closes the web browser asynchronously.
   * The browser will be removed from the device list.
   */
  fun closeWebBrowser() {
    webBrowserManager.closeBrowser {
      // Refresh device list to remove the browser
      loadDevices()
    }
  }

  private val targetDeviceFilter: (List<TrailblazeConnectedDeviceSummary>) -> List<TrailblazeConnectedDeviceSummary> =
    { connectedDeviceSummaries ->
      val isWebMode =
        settingsRepo.serverStateFlow.value.appConfig.testingEnvironment ==
          TrailblazeServerState.TestingEnvironment.WEB
      connectedDeviceSummaries.filter { connectedDeviceSummary ->
        when (connectedDeviceSummary.trailblazeDriverType) {
          // Virtual devices (Playwright, Compose) — only shown when web mode is enabled.
          TrailblazeDriverType.PLAYWRIGHT_NATIVE,
          TrailblazeDriverType.PLAYWRIGHT_ELECTRON,
          TrailblazeDriverType.COMPOSE -> isWebMode
          TrailblazeDriverType.REVYL_ANDROID,
          TrailblazeDriverType.REVYL_IOS -> true
          else -> settingsRepo.getEnabledDriverTypes().contains(connectedDeviceSummary.trailblazeDriverType)
        }
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

  /** Guards [getOrCreateSessionResolution] against concurrent session creation for the same device. */
  private val sessionCreationLock = Any()

  /**
   * Tracks installed app IDs per device.
   * Populated when loadDevices() is called.
   */
  private val _installedAppIdsByDeviceFlow = MutableStateFlow<Map<TrailblazeDeviceId, Set<String>>>(emptyMap())
  val installedAppIdsByDeviceFlow: StateFlow<Map<TrailblazeDeviceId, Set<String>>> =
    _installedAppIdsByDeviceFlow.asStateFlow()

  /**
   * Tracks app version info per device.
   * Key is a DeviceAppKey (deviceId, appId) to support multiple apps per device.
   * Populated when loadDevices() is called.
   */
  private val _appVersionInfoByDeviceFlow = MutableStateFlow<Map<DeviceAppKey, AppVersionInfo>>(emptyMap())
  val appVersionInfoByDeviceFlow: StateFlow<Map<DeviceAppKey, AppVersionInfo>> =
    _appVersionInfoByDeviceFlow.asStateFlow()

  private val loadDevicesScope = CoroutineScope(Dispatchers.IO)

  // Mutex to coalesce concurrent loadDevices calls — if one is running, others wait for it.
  private val loadDevicesMutex = kotlinx.coroutines.sync.Mutex()

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
    referrer: TrailblazeReferrer,
    agentImplementation: AgentImplementation = AgentImplementation.DEFAULT,
    onComplete: ((TrailExecutionResult) -> Unit)? = null,
  ) {
    val settingsState = settingsRepo.serverStateFlow.value
    val runYamlRequest = RunYamlRequest(
      yaml = yamlToRun,
      // Use title with ID appended for method name (e.g., for_your_business_page_5374142)
      // The class name will be auto-derived from testSectionName metadata
      testName = "test",
      useRecordedSteps = false,
      trailblazeLlmModel = currentTrailblazeLlmModelProvider(),
      targetAppName = settingsState.appConfig.selectedTargetAppId,
      trailFilePath = null,
      config = TrailblazeConfig(
        overrideSessionId = existingSessionId,
        sendSessionStartLog = sendSessionStartLog,
        sendSessionEndLog = sendSessionEndLog,
        browserHeadless = !settingsState.appConfig.showWebBrowser,
        preferHostAgent = settingsState.appConfig.preferHostAgent,
      ),
      trailblazeDeviceId = trailblazeDeviceId,
      referrer = referrer,
      agentImplementation = agentImplementation,
    )
    val params = DesktopAppRunYamlParams(
      forceStopTargetApp = forceStopTargetApp,
      runYamlRequest = runYamlRequest,
      targetTestApp = this.getCurrentSelectedTargetApp(),
      onProgressMessage = {},
      onConnectionStatus = {},
      additionalInstrumentationArgs = onDeviceInstrumentationArgsProvider(),
      onComplete = onComplete,
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
  ): DeviceSessionResolution = synchronized(sessionCreationLock) {
    val existingSessionId = if (forceNewSession) null else getCurrentSessionIdForDevice(trailblazeDeviceId)
    val isNewSession = existingSessionId == null
    val sessionId = existingSessionId ?: TrailblazeSessionManager.generateSessionId(sessionIdPrefix)

    // Track the session ID immediately so subsequent calls can find it
    if (isNewSession) {
      trackActiveSession(trailblazeDeviceId, sessionId, deviceSummary)
    }

    DeviceSessionResolution(sessionId, isNewSession)
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
    closeAndRemovePlaywrightNativeTestForDevice(trailblazeDeviceId)
    closeAndRemovePlaywrightElectronTestForDevice(trailblazeDeviceId)

    Console.log("Ended session $sessionId for device: ${trailblazeDeviceId.instanceId}")

    // Write session end log
    try {
      val sessionEndLog = TrailblazeLog.TrailblazeSessionStatusChangeLog(
        session = sessionId,
        timestamp = kotlinx.datetime.Clock.System.now(),
        sessionStatus = SessionStatus.Ended.Succeeded(durationMs = 0L),
      )
      logsRepo.saveLogToDisk(sessionEndLog)
    } catch (e: Exception) {
      Console.log("Failed to write session end log: ${e.message}")
      // Don't fail session end if log write fails
    }

    return sessionId
  }

  suspend fun runTool(
    trailblazeDeviceId: TrailblazeDeviceId,
    trailblazeTool: TrailblazeTool,
    referrer: TrailblazeReferrer
  ) {
    val yaml = createTrailblazeYaml().encodeToString(
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
   * Captures the current screen state for a device using the appropriate method:
   * - For on-device Android instrumentation: Uses RPC to call GetScreenStateRequestHandler
   * - For host drivers: Uses HostMaestroDriverScreenState with the active driver
   * - For accessibility: Not currently supported
   *
   * This method is used from the MCP server.
   */
  suspend fun getCurrentScreenState(trailblazeDeviceId: TrailblazeDeviceId): ScreenState? {
    val deviceState = getDeviceState(trailblazeDeviceId) ?: return null
    val driverType = deviceState.device.trailblazeDriverType

    return when (driverType) {
      TrailblazeDriverType.ANDROID_ONDEVICE_ACCESSIBILITY,
      TrailblazeDriverType.ANDROID_ONDEVICE_INSTRUMENTATION -> {
        // Use RPC for on-device Android instrumentation
        getCurrentScreenStateViaRpc(trailblazeDeviceId)
      }
      TrailblazeDriverType.IOS_HOST,
      TrailblazeDriverType.PLAYWRIGHT_NATIVE,
      TrailblazeDriverType.PLAYWRIGHT_ELECTRON -> {
        // Use direct Maestro driver access for host drivers
        getCurrentScreenStateViaDriver(trailblazeDeviceId)
      }
      TrailblazeDriverType.COMPOSE -> {
        // Not currently supported for direct screen capture
        Console.log("⚠️ Screen state capture not supported for ${driverType.name} driver")
        null
      }
      TrailblazeDriverType.REVYL_ANDROID,
      TrailblazeDriverType.REVYL_IOS -> {
        val platform = if (driverType == TrailblazeDriverType.REVYL_ANDROID) "android" else "ios"
        val activeClient = getActiveRevylCliClient(trailblazeDeviceId)
        if (activeClient != null) {
          RevylScreenState(activeClient, platform)
        } else {
          Console.log("No active Revyl session for ${trailblazeDeviceId.instanceId}, screen state unavailable")
          null
        }
      }
    }
  }
  
  /**
   * Captures screen state via RPC for on-device Android instrumentation.
   */
  private suspend fun getCurrentScreenStateViaRpc(trailblazeDeviceId: TrailblazeDeviceId): ScreenState? {
    return try {
      val rpcClient = OnDeviceRpcClient(
        trailblazeDeviceId = trailblazeDeviceId,
        sendProgressMessage = { },
      )
      
      val request = GetScreenStateRequest(
        includeScreenshot = true,
      )
      
      when (val result = rpcClient.rpcCall(request)) {
        is RpcResult.Success -> {
          val response = result.data
          val screenshotBytes = response.screenshotBase64?.let { 
            Base64.getDecoder().decode(it)
          }
          
          object : ScreenState {
            override val screenshotBytes: ByteArray? = screenshotBytes
            override val deviceWidth: Int = response.deviceWidth
            override val deviceHeight: Int = response.deviceHeight
            override val viewHierarchy: ViewHierarchyTreeNode = response.viewHierarchy
            override val trailblazeDevicePlatform: TrailblazeDevicePlatform = 
              trailblazeDeviceId.trailblazeDevicePlatform
            override val deviceClassifiers: List<TrailblazeDeviceClassifier> = emptyList()
          }
        }
        is RpcResult.Failure -> {
          Console.log("❌ Failed to get screen state via RPC: ${result.message}")
          null
        }
      }
    } catch (e: Exception) {
      Console.log("❌ Exception getting screen state via RPC: ${e.message}")
      e.printStackTrace()
      null
    }
  }
  
  /**
   * Captures screen state via direct Maestro driver access for host drivers.
   */
  private fun getCurrentScreenStateViaDriver(trailblazeDeviceId: TrailblazeDeviceId): ScreenState? {
    val driver = getActiveDriverForDevice(trailblazeDeviceId) ?: return null
    
    return try {
      HostMaestroDriverScreenState(
        maestroDriver = driver,
      )
    } catch (e: Exception) {
      Console.log("❌ Exception getting screen state via driver: ${e.message}")
      e.printStackTrace()
      null
    }
  }

  /**
   * Load available devices from the system (suspend version).
   * This will update the deviceStateFlow with the discovered devices and cache installed app IDs.
   */
  suspend fun loadDevicesSuspend(applyDriverFilter: Boolean = true): List<TrailblazeConnectedDeviceSummary> {
    // Coalesce concurrent calls: if a load is already running, wait for it instead of starting another.
    if (!loadDevicesMutex.tryLock()) {
      loadDevicesMutex.withLock { /* wait for the running call to finish */ }
      return deviceStateFlow.value.devices.values.map { it.device }
    }

    try {
      return loadDevicesSuspendImpl(applyDriverFilter)
    } finally {
      loadDevicesMutex.unlock()
    }
  }

  private suspend fun loadDevicesSuspendImpl(applyDriverFilter: Boolean): List<TrailblazeConnectedDeviceSummary> {
    withContext(Dispatchers.Default) {
      updateDeviceState { currDeviceState ->
        currDeviceState.copy(isLoading = true, error = null)
      }
    }

    try {
      // Run all device discovery in parallel via direct CLI calls with timeouts.
      val androidFuture = CompletableFuture.supplyAsync {
        listConnectedAdbDevices()
      }
      val iosFuture = CompletableFuture.supplyAsync {
        listBootedIosSimulators()
      }
      val electronCdpFuture = CompletableFuture.supplyAsync {
        isElectronCdpAvailable()
      }
      val composeRpcFuture = CompletableFuture.supplyAsync {
        isComposeRpcAvailable()
      }

      val androidDevices = try {
        androidFuture.get(10, TimeUnit.SECONDS)
      } catch (e: Exception) {
        Console.log("Android device discovery timed out or failed: ${e.message}")
        emptyList()
      }
      val iosSimulators = try {
        iosFuture.get(60, TimeUnit.SECONDS)
      } catch (e: Exception) {
        Console.log("iOS device discovery timed out or failed: ${e.message}")
        emptyList()
      }
      val electronAvailable = try {
        electronCdpFuture.get(1, TimeUnit.SECONDS)
      } catch (e: Exception) {
        false
      }
      val composeAvailable = try {
        composeRpcFuture.get(1, TimeUnit.SECONDS)
      } catch (e: Exception) {
        false
      }

      val allDevices = buildList {
        // Connected Android Devices
        androidDevices.forEach { (instanceId, description) ->
          add(
            TrailblazeConnectedDeviceSummary(
              trailblazeDriverType = TrailblazeDriverType.ANDROID_ONDEVICE_INSTRUMENTATION,
              instanceId = instanceId,
              description = description,
            )
          )
          add(
            TrailblazeConnectedDeviceSummary(
              trailblazeDriverType = TrailblazeDriverType.ANDROID_ONDEVICE_ACCESSIBILITY,
              instanceId = instanceId,
              description = description,
            )
          )
        }

        // Connected iOS Simulators
        iosSimulators.forEach { (udid, name) ->
          add(
            TrailblazeConnectedDeviceSummary(
              trailblazeDriverType = TrailblazeDriverType.IOS_HOST,
              instanceId = udid,
              description = name,
            )
          )
        }

        // Include web browser device only if the browser is currently running
        webBrowserManager.getRunningBrowserSummary()?.let { browserSummary ->
          add(browserSummary)
        }

        // Playwright-native is a virtual device (no hardware connection needed) —
        // always include it so web trails work from both GUI and CLI.
        add(
          TrailblazeConnectedDeviceSummary(
            trailblazeDriverType = TrailblazeDriverType.PLAYWRIGHT_NATIVE,
            instanceId = PLAYWRIGHT_NATIVE_INSTANCE_ID,
            description = "Playwright Browser (Native)",
          )
        )

        // Playwright-electron — only show if CDP endpoint is responding.
        if (electronAvailable) {
          add(
            TrailblazeConnectedDeviceSummary(
              trailblazeDriverType = TrailblazeDriverType.PLAYWRIGHT_ELECTRON,
              instanceId = PLAYWRIGHT_ELECTRON_INSTANCE_ID,
              description = "Playwright Electron (CDP)",
            )
          )
        }

        // Compose — only show if the RPC server is responding.
        if (composeAvailable) {
          add(
            TrailblazeConnectedDeviceSummary(
              trailblazeDriverType = TrailblazeDriverType.COMPOSE,
              instanceId = "compose",
              description = "Compose (RPC)",
            )
          )
        }

        // Revyl cloud devices — only show if the CLI is installed.
        if (revylCliClient.isCliAvailable) {
          add(
            TrailblazeConnectedDeviceSummary(
              trailblazeDriverType = TrailblazeDriverType.REVYL_ANDROID,
              instanceId = "revyl-android-phone",
              description = "Revyl Android (Default)",
            )
          )
          add(
            TrailblazeConnectedDeviceSummary(
              trailblazeDriverType = TrailblazeDriverType.REVYL_IOS,
              instanceId = "revyl-ios-iphone",
              description = "Revyl iOS (Default)",
            )
          )

          val targets = runWithTimeout(10, "revyl-catalog", "device targets") {
            revylCliClient.getDeviceTargets()
          } ?: emptyList()
          for (target in targets) {
            val driverType = if (target.platform == TrailblazeDevicePlatform.ANDROID)
              TrailblazeDriverType.REVYL_ANDROID else TrailblazeDriverType.REVYL_IOS
            add(
              TrailblazeConnectedDeviceSummary(
                trailblazeDriverType = driverType,
                instanceId = "revyl-model:${target.model}::${target.osVersion}",
                description = "Revyl ${target.model} (${target.osVersion})",
              )
            )
          }
        }
      }

      Console.log("[loadDevices] Discovered ${allDevices.size} device(s): ${allDevices.map { "${it.trailblazeDriverType.name}/${it.instanceId}" }}")

      // Always filter for device state — Android driver variants share the same
      // TrailblazeDeviceId key (instanceId + platform), so unfiltered results would let
      // the last-added variant overwrite the configured driver type.
      val devicesForState = targetDeviceFilter(allDevices)
      val devicesToReturn = if (applyDriverFilter) devicesForState else allDevices

      // Query installed app IDs for each device (with per-device timeout to avoid hanging)
      val installedAppIdsByDevice: Map<TrailblazeDeviceId, Set<String>> = devicesForState.associate { device ->
        val appIds = runWithTimeout(10, device.instanceId, "installed apps") {
          installedAppIdsProviderBlocking(device.trailblazeDeviceId)
        } ?: emptySet()
        device.trailblazeDeviceId to appIds
      }
      _installedAppIdsByDeviceFlow.value = installedAppIdsByDevice

      // Query version info only for apps that belong to available app targets (not all installed apps)
      // This is important for performance - querying version info for all apps would be very slow
      val relevantAppIds = availableAppTargets.flatMap { target ->
        target.getPossibleAppIdsForPlatform(TrailblazeDevicePlatform.ANDROID).orEmpty() +
            target.getPossibleAppIdsForPlatform(TrailblazeDevicePlatform.IOS).orEmpty()
      }.toSet()

      val appVersionInfoByDevice = mutableMapOf<DeviceAppKey, AppVersionInfo>()
      devicesForState.forEach { device ->
        val installedAppIds = installedAppIdsByDevice[device.trailblazeDeviceId] ?: emptySet()
        val appsToQuery = installedAppIds.intersect(relevantAppIds)
        appsToQuery.forEach { appId ->
          val versionInfo = runWithTimeout(10, device.instanceId, "version info for $appId") {
            appVersionInfoProviderBlocking(device.trailblazeDeviceId, appId)
          }
          if (versionInfo != null) {
            appVersionInfoByDevice[DeviceAppKey(device.trailblazeDeviceId, appId)] = versionInfo
          }
        }
      }
      _appVersionInfoByDeviceFlow.value = appVersionInfoByDevice

      withContext(Dispatchers.Default) {
        updateDeviceState { currState ->
          val newDeviceStates: Map<TrailblazeDeviceId, DeviceState> = devicesForState.associate { device ->
            device.trailblazeDeviceId to DeviceState(device = device)
          }

          currState.copy(
            devices = newDeviceStates,
            isLoading = false,
            error = null
          )
        }
      }

      return devicesToReturn
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

  fun getCurrentSelectedTargetApp(): TrailblazeHostAppTarget? = settingsRepo.getCurrentSelectedTargetApp()

  private val revylCliClient: RevylCliClient by lazy { RevylCliClient() }

  // Store running test instances per device - allows forceful driver shutdown
  private val maestroDriverByDeviceMap: MutableMap<TrailblazeDeviceId, Driver> =
    java.util.concurrent.ConcurrentHashMap()

  // Store active Revyl CLI clients per device for session reuse across MCP calls
  private val revylCliClientByDeviceMap: MutableMap<TrailblazeDeviceId, RevylCliClient> =
    java.util.concurrent.ConcurrentHashMap()

  // Store running Playwright-native test instances per device for browser reuse across MCP calls
  private val playwrightNativeTestByDeviceMap: MutableMap<TrailblazeDeviceId, BasePlaywrightNativeTest> =
    java.util.concurrent.ConcurrentHashMap()

  // Store running Playwright-electron test instances per device for session reuse
  private val playwrightElectronTestByDeviceMap: MutableMap<TrailblazeDeviceId, BasePlaywrightElectronTest> =
    java.util.concurrent.ConcurrentHashMap()

  // Store running coroutine jobs per device - allows cancellation of test execution
  private val coroutineScopeByDevice: MutableMap<TrailblazeDeviceId, CoroutineScope> =
    java.util.concurrent.ConcurrentHashMap()

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
    Console.log("FORCEFULLY CANCELLING test on device: ${trailblazeDeviceId.instanceId}")

    closeAndRemoveMaestroDriverForDevice(trailblazeDeviceId)
    closeAndRemovePlaywrightNativeTestForDevice(trailblazeDeviceId)
    closeAndRemovePlaywrightElectronTestForDevice(trailblazeDeviceId)

    // Step 2: Cancel the coroutine job (stop any remaining work)
    cancelAndRemoveCoroutineScopeForDeviceIfActive(trailblazeDeviceId)

    // Clear the session from the sessions flow
    _activeDeviceSessionsFlow.value = _activeDeviceSessionsFlow.value - trailblazeDeviceId
  }

  private fun closeAndRemoveMaestroDriverForDevice(trailblazeDeviceId: TrailblazeDeviceId) {
    // Get the running test and KILL its driver (kills child processes)
    maestroDriverByDeviceMap[trailblazeDeviceId]?.let { maestroDriver ->
      try {
        Console.log("Forcefully closing driver for device: ${trailblazeDeviceId.instanceId}")
        // This closes the underlying driver and kills child processes (XCUITest, adb, etc.)
        maestroDriver.close()
        Console.log("Driver closed successfully for device: ${trailblazeDeviceId.instanceId}")
      } catch (e: Exception) {
        Console.log("Error closing driver (continuing anyway): ${e.message}")
        // Continue with coroutine cancellation even if driver close fails
      } finally {
        maestroDriverByDeviceMap.remove(trailblazeDeviceId)
      }
    } ?: Console.log("No Maestro Driver found for device: ${trailblazeDeviceId.instanceId}")
  }

  private fun closeAndRemovePlaywrightNativeTestForDevice(trailblazeDeviceId: TrailblazeDeviceId) {
    playwrightNativeTestByDeviceMap.remove(trailblazeDeviceId)?.let { test ->
      try {
        test.close()
        Console.log("Playwright-native test closed for device: ${trailblazeDeviceId.instanceId}")
      } catch (e: Exception) {
        Console.log("Error closing Playwright-native test (continuing anyway): ${e.message}")
      }
    }
  }

  /**
   * Clears only the coroutine scope for a device WITHOUT closing the driver.
   * Use this for MCP sessions where the driver should stay alive between tool calls.
   */
  fun clearCoroutineScopeForDevice(trailblazeDeviceId: TrailblazeDeviceId) {
    coroutineScopeByDevice.remove(trailblazeDeviceId)
  }

  private fun cancelAndRemoveCoroutineScopeForDeviceIfActive(trailblazeDeviceId: TrailblazeDeviceId) {
    coroutineScopeByDevice[trailblazeDeviceId]?.let { coroutineScopeForDevice ->
      Console.log("Cancelling coroutine job for device: ${trailblazeDeviceId.instanceId}")
      Console.log("  Scope isActive BEFORE cancel: ${coroutineScopeForDevice.isActive}")
      try {
        if (coroutineScopeForDevice.isActive) {
          coroutineScopeForDevice.cancel(CancellationException("Session cancelled by user - driver forcefully closed"))

          // Verify cancellation propagated by checking status over time
          Console.log("  Scope isActive AFTER cancel (immediate): ${coroutineScopeForDevice.isActive}")

          // Monitor cancellation propagation
          repeat(5) { attempt ->
            Thread.sleep(100)
            val stillActive = coroutineScopeForDevice.isActive
            Console.log("  Scope isActive check #${attempt + 1} (after ${(attempt + 1) * 100}ms): $stillActive")
            if (!stillActive) {
              Console.log("  ✓ Scope successfully cancelled and inactive")
              return@repeat
            }
          }

          // If still active after 500ms, warn
          if (coroutineScopeForDevice.isActive) {
            Console.log("  ⚠️ WARNING: Scope still active after 500ms - cancellation may not have propagated!")
          }
        } else {
          Console.log("  Scope was already inactive, nothing to cancel")
        }
      } finally {
        coroutineScopeByDevice.remove(trailblazeDeviceId)
        Console.log("  Scope removed from map for device: ${trailblazeDeviceId.instanceId}")
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

  /**
   * Gets an existing coroutine scope for the device, or creates a new one if none exists.
   * Unlike [createNewCoroutineScopeForDevice], this does NOT cancel any existing scope.
   * Use this for MCP sessions where multiple tool calls should share a persistent connection.
   */
  fun getOrCreateCoroutineScopeForDevice(trailblazeDeviceId: TrailblazeDeviceId): CoroutineScope {
    return coroutineScopeByDevice[trailblazeDeviceId]?.takeIf { it.isActive }
      ?: CoroutineScope(Dispatchers.IO).also {
        coroutineScopeByDevice[trailblazeDeviceId] = it
      }
  }

  fun setActiveDriverForDevice(trailblazeDeviceId: TrailblazeDeviceId, maestroDriver: Driver) {
    maestroDriverByDeviceMap[trailblazeDeviceId] = maestroDriver
  }

  /**
   * Returns the active Maestro driver for the specified device, if one exists.
   * The driver is set when a test/tool execution starts.
   */
  fun getActiveDriverForDevice(trailblazeDeviceId: TrailblazeDeviceId): Driver? {
    return maestroDriverByDeviceMap[trailblazeDeviceId]
  }

  fun setActivePlaywrightNativeTest(
    trailblazeDeviceId: TrailblazeDeviceId,
    test: BasePlaywrightNativeTest,
  ) {
    playwrightNativeTestByDeviceMap[trailblazeDeviceId] = test
  }

  fun getActivePlaywrightNativeTest(
    trailblazeDeviceId: TrailblazeDeviceId,
  ): BasePlaywrightNativeTest? {
    return playwrightNativeTestByDeviceMap[trailblazeDeviceId]
  }

  fun setActivePlaywrightElectronTest(
    trailblazeDeviceId: TrailblazeDeviceId,
    test: BasePlaywrightElectronTest,
  ) {
    playwrightElectronTestByDeviceMap[trailblazeDeviceId] = test
  }

  fun getActivePlaywrightElectronTest(
    trailblazeDeviceId: TrailblazeDeviceId,
  ): BasePlaywrightElectronTest? {
    return playwrightElectronTestByDeviceMap[trailblazeDeviceId]
  }

  fun setActiveRevylCliClient(
    trailblazeDeviceId: TrailblazeDeviceId,
    client: RevylCliClient,
  ) {
    revylCliClientByDeviceMap[trailblazeDeviceId] = client
  }

  fun getActiveRevylCliClient(
    trailblazeDeviceId: TrailblazeDeviceId,
  ): RevylCliClient? {
    return revylCliClientByDeviceMap[trailblazeDeviceId]
  }

  fun removeActiveRevylCliClient(trailblazeDeviceId: TrailblazeDeviceId) {
    revylCliClientByDeviceMap.remove(trailblazeDeviceId)
  }

  private fun closeAndRemovePlaywrightElectronTestForDevice(trailblazeDeviceId: TrailblazeDeviceId) {
    playwrightElectronTestByDeviceMap.remove(trailblazeDeviceId)?.let { test ->
      try {
        test.close()
        Console.log("Playwright-electron test closed for device: ${trailblazeDeviceId.instanceId}")
      } catch (e: Exception) {
        Console.log("Error closing Playwright-electron test (continuing anyway): ${e.message}")
      }
    }
  }

  fun getInstalledAppIdsFlow(trailblazeDeviceId: TrailblazeDeviceId): StateFlow<Set<String>> {
    return installedAppIdsByDeviceFlow.map { it[trailblazeDeviceId] ?: emptySet() }
      .stateIn(
        loadDevicesScope,
        SharingStarted.Eagerly,
        installedAppIdsByDeviceFlow.value[trailblazeDeviceId] ?: emptySet()
      )
  }

  companion object {
    const val PLAYWRIGHT_NATIVE_INSTANCE_ID = "playwright-native"
    const val PLAYWRIGHT_ELECTRON_INSTANCE_ID = "playwright-electron"

    private const val DEVICE_DISCOVERY_TIMEOUT_SECONDS = 10L

    /**
     * Runs a blocking operation with a timeout. Returns null if it times out or fails.
     */
    private fun <T> runWithTimeout(timeoutSeconds: Long, deviceId: String, label: String, block: () -> T): T? {
      val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "device-query-$deviceId").apply { isDaemon = true }
      }
      return try {
        executor.submit(Callable { block() })
          .get(timeoutSeconds, TimeUnit.SECONDS)
      } catch (e: TimeoutException) {
        Console.log("[loadDevices] $label for $deviceId TIMED OUT after ${timeoutSeconds}s")
        null
      } catch (e: Exception) {
        Console.log("[loadDevices] $label for $deviceId FAILED: ${e.message}")
        null
      } finally {
        executor.shutdownNow()
      }
    }

    /**
     * Quick probe to check if an Electron app's CDP endpoint is responding.
     * Uses a 500ms connect/read timeout — if nothing is listening, this fails fast.
     */
    internal fun isElectronCdpAvailable(): Boolean {
      var connection: HttpURLConnection? = null
      return try {
        val port = System.getenv("TRAILBLAZE_ELECTRON_CDP_PORT")?.toIntOrNull() ?: 9222
        val url = URI("http://localhost:$port/json/version").toURL()
        connection = url.openConnection() as HttpURLConnection
        connection.connectTimeout = 500
        connection.readTimeout = 500
        connection.responseCode == 200
      } catch (_: Exception) {
        false
      } finally {
        connection?.disconnect()
      }
    }

    /**
     * Quick probe to check if the Compose RPC server is responding.
     * Uses a 500ms connect/read timeout — if nothing is listening, this fails fast.
     */
    internal fun isComposeRpcAvailable(): Boolean {
      var connection: HttpURLConnection? = null
      return try {
        val url = URI("http://localhost:${TrailblazeDevicePort.COMPOSE_DEFAULT_RPC_PORT}/ping").toURL()
        connection = url.openConnection() as HttpURLConnection
        connection.connectTimeout = 500
        connection.readTimeout = 500
        connection.responseCode == 200
      } catch (_: Exception) {
        false
      } finally {
        connection?.disconnect()
      }
    }

    /**
     * Lists connected Android devices via `adb devices`.
     * Returns list of (instanceId, description) pairs.
     */
    internal fun listConnectedAdbDevices(): List<Pair<String, String>> {
      return try {
        val process = ProcessBuilder("adb", "devices")
          .redirectErrorStream(true)
          .start()
        val finished = process.waitFor(DEVICE_DISCOVERY_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        if (!finished) {
          process.destroyForcibly()
          Console.log("[loadDevices] [Android] adb devices timed out after ${DEVICE_DISCOVERY_TIMEOUT_SECONDS}s")
          return emptyList()
        }
        val output = process.inputStream.bufferedReader().readText()
        // Parse lines like "emulator-5554\tdevice" — skip header and blank/unauthorized lines
        output.lines()
          .drop(1) // skip "List of devices attached" header
          .mapNotNull { line ->
            val parts = line.split("\t")
            if (parts.size == 2 && parts[1].trim() == "device") {
              val instanceId = parts[0].trim()
              instanceId to instanceId
            } else null
          }
      } catch (e: Exception) {
        Console.log("[loadDevices] [Android] adb devices failed: ${e.message}")
        emptyList()
      }
    }

    /**
     * Lists booted iOS simulators via `xcrun simctl list devices booted`.
     * Returns list of (udid, description) pairs.
     */
    internal fun listBootedIosSimulators(): List<Pair<String, String>> {
      if (!isMacOs()) return emptyList()
      return try {
        val process = ProcessBuilder("xcrun", "simctl", "list", "devices", "booted")
          .redirectErrorStream(true)
          .start()
        val finished = process.waitFor(60, TimeUnit.SECONDS)
        if (!finished) {
          process.destroyForcibly()
          process.waitFor(5, TimeUnit.SECONDS)
          Console.log("[loadDevices] [iOS] xcrun simctl timed out after 60s")
          return emptyList()
        }
        val output = process.inputStream.bufferedReader().use { it.readText() }
        // Parse lines like "    iPad (A16) (6171FEAD-...) (Booted)"
        val deviceRegex = Regex("""^\s+(.+?)\s+\(([0-9A-Fa-f-]{36})\)\s+\(Booted\)""")
        val results = output.lines().mapNotNull { line ->
          deviceRegex.find(line)?.let { match ->
            val name = match.groupValues[1]
            val udid = match.groupValues[2]
            udid to name
          }
        }
        Console.log("[loadDevices] [iOS] Found ${results.size} booted simulator(s)")
        results
      } catch (e: Exception) {
        Console.log("[loadDevices] [iOS] xcrun simctl failed: ${e.message}")
        emptyList()
      }
    }
  }
}

/**
 * Key for looking up app version info by device and app ID.
 */
data class DeviceAppKey(
  val deviceId: TrailblazeDeviceId,
  val appId: String,
)

/**
 * Extension function to look up app version info by device ID and app ID.
 */
fun Map<DeviceAppKey, AppVersionInfo>.getVersionInfo(
  deviceId: TrailblazeDeviceId,
  appId: String,
): AppVersionInfo? = this[DeviceAppKey(deviceId, appId)]
