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
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import maestro.Driver
import xyz.block.trailblaze.api.EffectiveScreenshotScalingConfig
import xyz.block.trailblaze.api.ScreenState
import xyz.block.trailblaze.mcp.AgentImplementation
import xyz.block.trailblaze.api.ViewHierarchyTreeNode
import xyz.block.trailblaze.devices.TrailblazeConnectedDeviceSummary
import xyz.block.trailblaze.devices.TrailblazeDeviceClassifier
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.devices.TrailblazeDevicePort
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.devices.WebInstanceIds
import xyz.block.trailblaze.capture.CaptureOptions
import xyz.block.trailblaze.host.capture.SessionCaptureCoordinator
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
import xyz.block.trailblaze.logs.model.TraceId
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
import xyz.block.trailblaze.util.AndroidHostAdbUtils
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
  initialAppTargets: Set<TrailblazeHostAppTarget>,
  /**
   * Re-runs full app-target discovery against the current workspace on disk — the same pass
   * that produced [initialAppTargets] at startup. Consumed only by [registerNewTarget];
   * when null (tests, embedders that don't support live registration), live registration is
   * unavailable and callers fall back to the restart-required flow.
   */
  private val freshAppTargetsProvider: (() -> Set<TrailblazeHostAppTarget>)? = null,
  val appIconProvider: AppIconProvider,
  val deviceClassifierIconProvider: DeviceClassifierIconProvider,
  private val runYamlLambda: (desktopAppRunYamlParams: DesktopAppRunYamlParams) -> Unit,
  private val installedAppIdsProviderBlocking: (TrailblazeDeviceId) -> Set<String>,
  private val appVersionInfoProviderBlocking: (TrailblazeDeviceId, String) -> AppVersionInfo? = { _, _ -> null },
  val onDeviceInstrumentationArgsProvider: () -> Map<String, String>,
  private val trailblazeAnalytics: TrailblazeAnalytics,
) {

  /**
   * Atomic holder AND observable source of the live app-target set. A [MutableStateFlow] rather
   * than a plain [AtomicReference] so desktop Compose read sites can [collectAsState] and
   * recompose when [registerNewTarget] appends a target — its [MutableStateFlow.compareAndSet]
   * is the additive-append arbiter, and its `value` backs the [availableAppTargets] snapshot
   * getter, so the flow and the getter can never drift (single holder).
   */
  private val _availableAppTargetsFlow: MutableStateFlow<Set<TrailblazeHostAppTarget>> =
    MutableStateFlow(initialAppTargets)

  /**
   * Observable live view of the app-target set for desktop Compose. Emits a new complete
   * immutable snapshot whenever [registerNewTarget] appends a target; collect it (rather than
   * reading [availableAppTargets] directly) anywhere a composable must recompose on a live
   * append. Trail Runner (web) doesn't consume this — it refetches the target list over RPC.
   */
  val availableAppTargetsFlow: StateFlow<Set<TrailblazeHostAppTarget>> =
    _availableAppTargetsFlow.asStateFlow()

  /**
   * Live view of the app-target set. Seeded from startup discovery; [registerNewTarget] appends
   * net-new targets and swaps in the freshly-discovered instance for an edited id (Create/Edit
   * Target saves). Individual target objects are immutable - a run that captured its resolved
   * target at run start keeps that reference and stays consistent even if the set entry is later
   * swapped. Every read sees a complete immutable snapshot (old or new, never partial); renames
   * and removals of existing targets still require a daemon restart (flagged by the
   * `target-drift` endpoint).
   *
   * This is a point-in-time snapshot getter — desktop composables that must recompose on a
   * live append should collect [availableAppTargetsFlow] instead.
   */
  val availableAppTargets: Set<TrailblazeHostAppTarget>
    get() = _availableAppTargetsFlow.value

  init {
    // The settings repo predates this manager (it feeds the config's startup discovery), so its
    // constructor-injected target provider reads the startup-frozen set. Re-point it at the live
    // set so a target appended by [registerNewTarget] also resolves through
    // [TrailblazeSettingsRepo.getCurrentSelectedTargetApp] (GetTargetApps, recording tool
    // discovery, run dispatch) — not just through direct reads of [availableAppTargets].
    settingsRepo.bindLiveTargetProvider { availableAppTargets }
  }

  /**
   * Additive-only live registration of a newly-created app target (Trail Runner's Create
   * Target flow), so it becomes selectable without a daemon restart.
   *
   * Re-runs full workspace discovery via [freshAppTargetsProvider] rather than resolving the
   * single target in isolation: the discovery pass's workspace tool/toolset overlay
   * registrations (idempotent, replace-not-append — see `AppTargetDiscovery`) are what make a
   * new target's custom `*.tool.yaml` / toolset files dispatchable, not just listable. Only
   * the target matching [targetId] is then appended to the live set; fresh instances of
   * already-registered ids are discarded so existing object identity is preserved.
   *
   * Returns the resolved target when it is (or is now) live — a net-new append OR an id that
   * was already registered (idempotent: a double-submit or a concurrent caller that won the
   * append still gets the target back, not a spurious null that would trigger the restart
   * banner). Returns null only when the target genuinely can't be made live: no provider
   * wired, discovery threw, or discovery ran but didn't surface the id. Every non-append
   * outcome logs why, so a "target didn't appear" report is diagnosable from the daemon log
   * without a restart — the whole point of the flow.
   */
  fun registerNewTarget(targetId: String): TrailblazeHostAppTarget? {
    val provider = freshAppTargetsProvider ?: run {
      Console.log(
        "[TrailblazeDeviceManager] Live target registration for '$targetId' skipped: " +
          "no fresh-discovery provider wired (falling back to restart-required flow)",
      )
      return null
    }
    val fresh = try {
      provider()
    } catch (e: Exception) {
      Console.error(
        "[TrailblazeDeviceManager] Live target registration for '$targetId' failed during discovery: " +
          "${e::class.simpleName}: ${e.message}\n${e.stackTraceToString()}",
      )
      return null
    }
    // The live set IS the StateFlow's value, so the CAS append emits to every desktop
    // composable collecting [availableAppTargetsFlow] — that's what makes the new target
    // appear in the picker without a restart.
    return casAppendNewTarget(_availableAppTargetsFlow, fresh, targetId)
  }

  /**
   * Single point of ownership for per-`SessionId` capture across CLI, MCP, and desktop-UI
   * paths. See [SessionCaptureCoordinator] for why this exists at the device-manager
   * level (where session lifecycle already lives) rather than scattered across runners.
   */
  val sessionCaptureCoordinator: SessionCaptureCoordinator = SessionCaptureCoordinator(logsRepo)

  /**
   * Manages the web browser lifecycle for web testing.
   * Use [launchWebBrowser] and [closeWebBrowser] to control the browser.
   */
  val webBrowserManager = WebBrowserManager()

  /**
   * Shared connection service for both the desktop recording tab and the HTTP recording
   * API. Centralizes the platform-specific connect logic so both surfaces stay in sync.
   */
  val connectionService = xyz.block.trailblaze.host.recording.DeviceConnectionService(this)

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
    val savedViewport = settingsRepo.serverStateFlow.value.appConfig.webViewport
    // Explicitly sync the slot's viewport spec so the desktop UI's stored value is
    // authoritative — including the "clear back to default" case. Without this,
    // [WebBrowserManager.launchBrowser] only writes when its `viewportSpec` arg is
    // non-null, so a slot that earlier received e.g. `device create web --emulate
    // "iPhone 14"` would inherit that stale spec on the next desktop-UI launch even
    // after the user cleared the desktop viewport field.
    webBrowserManager.setViewportSpec(
      instanceId = WebBrowserManager.PLAYWRIGHT_NATIVE_INSTANCE_ID,
      viewportSpec = savedViewport,
    )
    webBrowserManager.launchBrowser(viewportSpec = savedViewport) {
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

  /**
   * Per-Trailblaze-session target overrides, keyed by [SessionId]. Populated
   * by [setTargetForActiveSession] when the CLI passes `--target X` on an
   * action command (`tool`, `step`, `snapshot`, `ask`, `verify`, `session
   * start`). Cleared automatically when the session ends — see
   * [endSessionForDevice], [cancelSessionForDevice], and the
   * [clearEndedSessionFromDevice] hook.
   *
   * Tied to the recording session's lifetime, not to the device — `session
   * stop` followed by a fresh `tool` call on the same device starts a new
   * session that has no target override unless the user passes `--target`
   * again. This matches how `.trail.yaml` targets are read per run.
   *
   * Resolution chain on tool dispatch (see
   * `TrailblazeMcpBridgeImpl.resolveTargetAppIdForDevice`): per-session
   * override → daemon-wide `selectedTargetAppId`.
   *
   * State lives on a small dedicated [SessionTargetRegistry] so the
   * mutation semantics can be unit-tested in isolation — `TrailblazeDeviceManager`
   * itself has too many constructor dependencies to mock cheaply.
   */
  private val sessionTargetRegistry = SessionTargetRegistry()

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
    traceId: TraceId? = null,
    // Optional per-run overrides (Run-config dialog). All default to the
    // prior in-app behavior, so existing callers are unaffected:
    //   selfHeal=null → keep TrailblazeConfig's default; useRecordedSteps=false →
    //   LLM-drives (unchanged); maxLlmCalls=null → runner default; memory seeds empty.
    selfHeal: Boolean? = null,
    useRecordedSteps: Boolean = false,
    maxLlmCalls: Int? = null,
    initialMemorySeeds: Map<String, String> = emptyMap(),
    initialMemorySensitiveSeeds: Map<String, String> = emptyMap(),
    // Per-run override (Run-config dialog); null = appConfig default.
    captureNetworkTrafficOverride: Boolean? = null,
    onComplete: ((TrailExecutionResult) -> Unit)? = null,
  ) {
    // Load-time {{VAR}} template resolution — same contract as the daemon's /cli/run handler
    // (see [resolveSubmittedTrailYaml]). Desktop editor content and MCP runYaml submissions
    // carry no file path, so they are bare submissions resolving against the daemon's
    // environment; without this they'd skip load-time resolution entirely.
    val resolvedYamlToRun = resolveSubmittedTrailYaml(yamlToRun, trailFilePath = null)
    val settingsState = settingsRepo.serverStateFlow.value
    // Honor `config.driver` from the trail YAML on the desktop UI's "Run trail" path —
    // mirrors what [TrailCommand] does for the CLI direct path and what
    // a downstream `AndroidTrailblazeRule` subclass's `applyPerTrailDriverFromAsset` does for on-device runs.
    // Without this, a trail with `driver: ANDROID_ONDEVICE_ACCESSIBILITY` would silently run
    // on whatever driver the user-selected device defaults to, regardless of what the YAML
    // says it requires. [RunYamlRequest.driverType] is consumed downstream by
    // [AndroidStandaloneServerTest.handleRunRequest], which sets `driverTypeOverride` and
    // makes the rule resolve to the matching agent.
    val trailConfig = try {
      createTrailblazeYaml().extractTrailConfig(resolvedYamlToRun)
    } catch (_: Exception) {
      null
    }
    val trailConfigDriver = try {
      trailConfig?.driver?.let { TrailblazeDriverType.fromString(it) }
    } catch (_: Exception) {
      null
    }
    // Honor `config.target` from the trail YAML so a trail runs against its declared target
    // instead of whatever target is selected in the desktop app — mirrors the per-trail
    // `config.driver` handling above. Falls back to the selected target when the trail declares
    // none. Without this, the trail's `target:` was ignored and a trail launched whichever app
    // the previously-selected target pointed at.
    val trailConfigTarget = trailConfig?.target
    // A declared target that doesn't resolve is a hard error, never a silent substitution:
    // falling back to the globally-selected target ran (and bootstrapped) a completely unrelated
    // app while the session still reported the declared target's name.
    val resolvedTargetTestApp = if (trailConfigTarget != null) {
      availableAppTargets.find { it.id == trailConfigTarget }
        ?: error(
          "Trail declares target '$trailConfigTarget' which is not registered in this daemon " +
            "(available: ${availableAppTargets.map { it.id }.sorted()}). " +
            "Fix the trail's target:, create the target, or restart Trail Runner to pick up edits.",
        )
    } else {
      getCurrentSelectedTargetApp()
    }
    // Derive the recorded name from the SAME resolution as the target object — otherwise a
    // target resolved through the workspace `defaults.target` rung would run against the app
    // while recording `targetAppName = null`.
    val resolvedTargetAppName = trailConfigTarget ?: resolvedTargetTestApp?.id
    val baseConfig = TrailblazeConfig(
      overrideSessionId = existingSessionId,
      sendSessionStartLog = sendSessionStartLog,
      sendSessionEndLog = sendSessionEndLog,
      browserHeadless = !settingsState.appConfig.showWebBrowser,
      preferHostAgent = settingsState.appConfig.preferHostAgent,
      captureNetworkTraffic = captureNetworkTrafficOverride ?: settingsState.appConfig.captureNetworkTraffic,
    )
    val runYamlRequest = RunYamlRequest(
      yaml = resolvedYamlToRun,
      // Use title with ID appended for method name (e.g., for_your_business_page_5374142)
      // The class name will be auto-derived from testSectionName metadata
      testName = "test",
      useRecordedSteps = useRecordedSteps,
      trailblazeLlmModel = currentTrailblazeLlmModelProvider(),
      targetAppName = resolvedTargetAppName,
      trailFilePath = null,
      // Only override selfHeal when the caller set it; otherwise keep the config default.
      config = if (selfHeal != null) baseConfig.copy(selfHeal = selfHeal) else baseConfig,
      trailblazeDeviceId = trailblazeDeviceId,
      driverType = trailConfigDriver,
      referrer = referrer,
      agentImplementation = agentImplementation,
      traceId = traceId,
      // RunYamlRequest rejects maxLlmCalls with MULTI_AGENT_V3 and non-positive values.
      maxLlmCalls = maxLlmCalls?.takeIf { it > 0 && agentImplementation != AgentImplementation.MULTI_AGENT_V3 },
      initialMemorySeeds = initialMemorySeeds,
      initialMemorySensitiveSeeds = initialMemorySensitiveSeeds,
    )
    val params = DesktopAppRunYamlParams(
      forceStopTargetApp = forceStopTargetApp,
      runYamlRequest = runYamlRequest,
      targetTestApp = resolvedTargetTestApp,
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
    deviceSummary: TrailblazeConnectedDeviceSummary? = null,
    // Optional per-run capture overrides (Run-config dialog). null = fall back to
    // the daemon's appConfig default, so existing callers are unaffected.
    captureVideoOverride: Boolean? = null,
    captureLogcatOverride: Boolean? = null,
    captureIosLogsOverride: Boolean? = null,
  ): DeviceSessionResolution {
    // Two-phase to keep `startForSession` (which can block on adb/ffmpeg/xcrun
    // startup) OUTSIDE `sessionCreationLock` — otherwise every concurrent session
    // creation, end, and cancel across every device queues behind whatever
    // screenrecord is starting next. The lock only protects the
    // session-id-generation + trackActiveSession step.
    val resolution: DeviceSessionResolution
    val startCaptureFor: SessionId?
    val captureDeviceId: String
    val captureAppId: String?
    val captureOptions: CaptureOptions
    synchronized(sessionCreationLock) {
      val existingSessionId = if (forceNewSession) null else getCurrentSessionIdForDevice(trailblazeDeviceId)
      val isNewSession = existingSessionId == null
      val sessionId = existingSessionId ?: TrailblazeSessionManager.generateSessionId(sessionIdPrefix)
      if (isNewSession) {
        trackActiveSession(trailblazeDeviceId, sessionId, deviceSummary)
      }
      resolution = DeviceSessionResolution(sessionId, isNewSession)
      startCaptureFor = if (isNewSession) sessionId else null
      captureDeviceId = trailblazeDeviceId.instanceId
      // Resolve appId outside the lock would race with target updates, so capture both
      // the target-override + effective daemon-wide target (persisted selection → workspace
      // defaults.target) here. Raw selectedTargetAppId would be null under a workspace-default
      // target, dropping app-scoping from the capture.
      val appConfig = settingsRepo.serverStateFlow.value.appConfig
      captureAppId = sessionTargetRegistry.get(sessionId) ?: getCurrentSelectedTargetApp()?.id
      // Resolve capture options from the daemon's `appConfig` toggles. Per-run CLI
      // flags (--no-capture-video / --capture-logcat) are layered on by
      // `DesktopYamlRunner` when the CLI path also fires `startForSession`; the
      // coordinator's reservation pattern makes that second call a no-op so the
      // appConfig-derived options here are what win for MCP-only paths.
      captureOptions = CaptureOptions(
        captureVideo = captureVideoOverride ?: true,
        captureLogcat = captureLogcatOverride ?: appConfig.captureLogcat,
        captureIosLogs = captureIosLogsOverride ?: appConfig.captureIosLogs,
        spriteFrameFps = 2,
        spriteFrameHeight = 720,
        spriteQuality = 80,
      )
    }

    // Phase 2 (outside the lock): start capture for the new session. Idempotent — the
    // CLI path's `DesktopYamlRunner.captureSessionStarted` callback may also fire
    // `startForSession` later, but the coordinator's reserve-then-start protocol
    // ensures only one wins.
    if (startCaptureFor != null) {
      sessionCaptureCoordinator.startForSession(
        sessionId = startCaptureFor,
        deviceId = captureDeviceId,
        platform = trailblazeDeviceId.trailblazeDevicePlatform,
        options = captureOptions,
        appId = captureAppId,
      )
    }
    return resolution
  }

  /**
   * Result of assigning a per-session target via [setTargetForActiveSession].
   * Surfaces the resolved session id so callers (the MCP tool, the CLI) can
   * report it back to the user — and the `isNewSession` flag for "we just
   * started a session as a side effect of setting a target."
   */
  data class SessionTargetAssignment(
    /**
     * The session the override was applied to, or `null` when the caller
     * requested a clear (`appTargetId` null/blank) on a device that had no
     * active session — in that case the operation is a true no-op and no
     * session id was synthesized.
     */
    val sessionId: SessionId?,
    val appTargetId: String?,
    val isNewSession: Boolean,
  )

  /**
   * Set or clear the target for the device's currently-active Trailblaze
   * session. If no session is active for the device, creates one — setting a
   * target implicitly starts a recording. Pass `null` or blank for
   * [appTargetId] to clear the override (falling back to daemon-wide on next
   * tool dispatch).
   *
   * The target lives on the session, not the device — `session stop` on this
   * device wipes the override automatically.
   */
  fun setTargetForActiveSession(
    trailblazeDeviceId: TrailblazeDeviceId,
    appTargetId: String?,
    sessionIdPrefix: String = "session",
    deviceSummary: TrailblazeConnectedDeviceSummary? = null,
  ): SessionTargetAssignment {
    // Fast path: clearing a target on a device that has no active session
    // must be a true no-op. Without this guard, `getOrCreateSessionResolution`
    // below would implicitly start a fresh recording just to immediately not
    // store anything on it — a surprising side effect for callers that just
    // wanted to ensure no override is set.
    val existingSessionId = getCurrentSessionIdForDevice(trailblazeDeviceId)
    if (appTargetId.isNullOrBlank() && existingSessionId == null) {
      return SessionTargetAssignment(
        sessionId = null,
        appTargetId = null,
        isNewSession = false,
      )
    }
    val resolution = getOrCreateSessionResolution(
      trailblazeDeviceId = trailblazeDeviceId,
      forceNewSession = false,
      sessionIdPrefix = sessionIdPrefix,
      deviceSummary = deviceSummary,
    )
    sessionTargetRegistry.set(resolution.sessionId, appTargetId)
    return SessionTargetAssignment(
      sessionId = resolution.sessionId,
      appTargetId = appTargetId?.takeIf { it.isNotBlank() },
      isNewSession = resolution.isNewSession,
    )
  }

  /**
   * Returns the target override for the device's currently-active session,
   * or `null` if the device has no active session or no override set on it.
   * Tool dispatch falls back to [settingsRepo]'s daemon-wide
   * `selectedTargetAppId` when this returns null.
   */
  fun getTargetForActiveSession(trailblazeDeviceId: TrailblazeDeviceId): String? {
    val sessionId = getCurrentSessionIdForDevice(trailblazeDeviceId) ?: return null
    return sessionTargetRegistry.get(sessionId)
  }

  /**
   * Returns the target override for a specific session id, or `null` if
   * unset. Used by `session info` to render the target for any queried
   * session, not just the active one.
   */
  fun getTargetForSession(sessionId: SessionId): String? = sessionTargetRegistry.get(sessionId)

  /**
   * Ends the current session for a device.
   * Clears the session ID from activeDeviceSessionsFlow and writes a session end log.
   * Use [cancelSessionForDevice] if you need to forcefully stop a running test.
   *
   * @param trailblazeDeviceId The device to end the session for
   * @return The session ID that was ended, or null if no session was active
   */
  fun endSessionForDevice(trailblazeDeviceId: TrailblazeDeviceId): SessionId? {
    // Read sessionId, drop from active map, AND clear the registry under
    // [sessionCreationLock] — the same lock [getOrCreateSessionResolution]
    // takes. Otherwise a concurrent `setTargetForActiveSession` between the
    // read and the clear could synthesize a new session for the device
    // (under the lock) and write its override to the registry, leaving us to
    // clear the OLD session id while the NEW session's override sits
    // orphaned (the device's active id has moved on but our clear targets
    // the wrong id).
    val sessionId = synchronized(sessionCreationLock) {
      val current = getCurrentSessionIdForDevice(trailblazeDeviceId) ?: return null
      _activeDeviceSessionsFlow.value -= trailblazeDeviceId
      sessionTargetRegistry.clear(current)
      current
    }
    closeAndRemovePlaywrightNativeTestForDevice(trailblazeDeviceId)
    closeAndRemovePlaywrightElectronTestForDevice(trailblazeDeviceId)
    // Web browser intentionally NOT closed here. The browser is the "device" — durable
    // across sessions just like an Android emulator or iOS simulator. Per-session state
    // isolation (cookies, localStorage, IndexedDB, tabs) is the job of
    // PlaywrightBrowserManager.resetSession(), which is called by TrailblazeHostYamlRunner
    // at session start and recreates the BrowserContext for true newContext-level isolation.

    // Stop the per-session capture stream (video / sprite / logcat) BEFORE writing the
    // session-end log — capture's stopAll triggers the WebM finalize / sprite-extract
    // ffmpeg passes which can take a few seconds, and we want any "session ended"
    // observers reading the directory afterwards to see the final state.
    sessionCaptureCoordinator.stopForSession(sessionId)

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
      TrailblazeDriverType.IOS_AXE -> {
        val axeDevice = deviceState.device as? xyz.block.trailblaze.host.devices.AxeConnectedDevice
        if (axeDevice == null) {
          Console.log("⚠️ IOS_AXE driver type but connected device is not AxeConnectedDevice")
          null
        } else {
          xyz.block.trailblaze.host.screenstate.AxeScreenState(
            udid = axeDevice.udid,
            deviceWidth = axeDevice.deviceWidth,
            deviceHeight = axeDevice.deviceHeight,
          )
        }
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
      
      val request = GetScreenStateRequest(includeScreenshot = true)
        .withScreenshotScalingConfig(EffectiveScreenshotScalingConfig.effective)
      
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
   * Last unfiltered result from [loadDevicesSuspendImpl]. Cached so concurrent callers that coalesce
   * on [loadDevicesMutex] can still satisfy `applyDriverFilter = false` — [deviceStateFlow] only
   * stores the filtered list ([targetDeviceFilter] strips virtual devices when the user hasn't
   * set `testingEnvironment = WEB`), so returning it for a CLI caller that asked for everything
   * would silently drop PLAYWRIGHT_NATIVE etc. and surface as "No matching device found".
   *
   * Exposed as [allDiscoveredDevicesFlow] so the Run Configuration picker can list a virtual-only
   * trail's virtual device even when [targetDeviceFilter] hides it (see `devicesForRunPicker`).
   */
  private val _allDiscoveredDevicesFlow = MutableStateFlow<List<TrailblazeConnectedDeviceSummary>>(emptyList())
  val allDiscoveredDevicesFlow: StateFlow<List<TrailblazeConnectedDeviceSummary>> =
    _allDiscoveredDevicesFlow.asStateFlow()

  /**
   * Load available devices from the system (suspend version).
   * This will update the deviceStateFlow with the discovered devices and cache installed app IDs.
   */
  suspend fun loadDevicesSuspend(applyDriverFilter: Boolean = true): List<TrailblazeConnectedDeviceSummary> {
    // Coalesce concurrent calls: if a load is already running, wait for it instead of starting another.
    if (!loadDevicesMutex.tryLock()) {
      loadDevicesMutex.withLock { /* wait for the running call to finish */ }
      return if (applyDriverFilter) {
        deviceStateFlow.value.devices.values.map { it.device }
      } else {
        _allDiscoveredDevicesFlow.value
      }
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

        // Connected iOS Simulators — always emit IOS_HOST; emit IOS_AXE only when the
        // `axe` CLI is installed on this host. Otherwise users would see an IOS_AXE
        // entry they can't actually use, which would fail at connect time with a
        // confusing error.
        val axeAvailable = xyz.block.trailblaze.host.axe.AxeCli.isAvailable()
        iosSimulators.forEach { (udid, name) ->
          add(
            TrailblazeConnectedDeviceSummary(
              trailblazeDriverType = TrailblazeDriverType.IOS_HOST,
              instanceId = udid,
              description = name,
            )
          )
          if (axeAvailable) {
            add(
              TrailblazeConnectedDeviceSummary(
                trailblazeDriverType = TrailblazeDriverType.IOS_AXE,
                instanceId = udid,
                description = name,
              )
            )
          }
        }

        // Include any currently running web browsers. Each named instance
        // (e.g. `--device web/foo`) is provisioned on demand by the MCP bridge,
        // so the running set is what's worth listing alongside the always-on
        // virtual default below.
        val runningWebBrowsers = webBrowserManager.getAllRunningBrowserSummaries()
        runningWebBrowsers.forEach { add(it) }

        // Playwright-native is a virtual device (no hardware connection needed) —
        // always include it so web trails work from both GUI and CLI even when
        // no browser has been launched yet. Skip when the running set already
        // includes it to avoid a duplicate entry.
        if (runningWebBrowsers.none { it.instanceId == PLAYWRIGHT_NATIVE_INSTANCE_ID }) {
          add(
            TrailblazeConnectedDeviceSummary(
              trailblazeDriverType = TrailblazeDriverType.PLAYWRIGHT_NATIVE,
              instanceId = PLAYWRIGHT_NATIVE_INSTANCE_ID,
              description = "Playwright Browser (Native)",
            )
          )
        }

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

        // Compose — only show if the RPC server is responding. Instance id is "self"
        // so the device addresses as `desktop/self` (one logical instance per host —
        // the desktop window itself); the platform is `DESKTOP` per
        // `TrailblazeDriverType.COMPOSE.platform`.
        if (composeAvailable) {
          add(
            TrailblazeConnectedDeviceSummary(
              trailblazeDriverType = TrailblazeDriverType.COMPOSE,
              instanceId = "self",
              description = "Compose Desktop (RPC)",
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

      // Cache the unfiltered list so concurrent callers who coalesce on loadDevicesMutex with
      // applyDriverFilter=false still see virtual devices (see [allDiscoveredDevicesFlow]).
      _allDiscoveredDevicesFlow.value = allDevices

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
    // Always drop the per-session target override for the ended session id —
    // even if a newer session has already taken over for the device, the
    // override on the old session id is unreachable and must be reclaimed to
    // avoid a slow leak in long-running daemons.
    sessionTargetRegistry.clear(endedSessionId)
  }

  fun getAllSupportedDriverTypes() = settingsRepo.getAllSupportedDriverTypes()

  fun getCurrentSelectedTargetApp(): TrailblazeHostAppTarget? = settingsRepo.getCurrentSelectedTargetApp()

  /**
   * Effective target for a CLI-dispatched `run`, anchoring the workspace `defaults.target` rung
   * at the caller's [callerWorkspaceDir] rather than this daemon's frozen configured-trails-dir.
   * See [TrailblazeSettingsRepo.getCurrentSelectedTargetAppForCallerCwd].
   */
  fun getCurrentSelectedTargetAppForCallerCwd(callerWorkspaceDir: String?): TrailblazeHostAppTarget? =
    settingsRepo.getCurrentSelectedTargetAppForCallerCwd(callerWorkspaceDir)

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
   *
   * @param knownSessionId the session the CALLER is cancelling, when it has one in hand. The
   * device->session mapping may already be cleared by the time this runs (an ended-but-wedged
   * session), and capture teardown keyed only on that mapping leaked screenrecord/logcat streams
   * that ran for hours (observed: 34% daemon CPU, a 25MB device.log, no mp4).
   * @return true if a live execution was actually cancelled (an active coroutine scope was
   * cancelled or an active session was registered for the device); false when there was nothing
   * running to cancel, so callers can report an honest no-op instead of a phantom success.
   */
  fun cancelSessionForDevice(trailblazeDeviceId: TrailblazeDeviceId, knownSessionId: SessionId? = null): Boolean {
    Console.log("FORCEFULLY CANCELLING test on device: ${trailblazeDeviceId.instanceId}")

    closeAndRemoveMaestroDriverForDevice(trailblazeDeviceId)
    closeAndRemovePlaywrightNativeTestForDevice(trailblazeDeviceId)
    closeAndRemovePlaywrightElectronTestForDevice(trailblazeDeviceId)

    // Step 2: Cancel the coroutine job (stop any remaining work)
    val scopeCancelled = cancelAndRemoveCoroutineScopeForDeviceIfActive(trailblazeDeviceId)

    // Clear the session from the sessions flow + per-session target override
    // under [sessionCreationLock] so a concurrent setTargetForActiveSession
    // can't synthesize a new session between the read and the clear (same
    // race condition addressed in [endSessionForDevice]).
    val cancelledSessionId = synchronized(sessionCreationLock) {
      val sid = _activeDeviceSessionsFlow.value[trailblazeDeviceId]
      _activeDeviceSessionsFlow.value = _activeDeviceSessionsFlow.value - trailblazeDeviceId
      sid?.let { sessionTargetRegistry.clear(it) }
      sid
    }
    // Best-effort stop of capture for the cancelled session — running outside the
    // sessionCreationLock so a slow ffmpeg sprite-extract pass on stopAll can't deadlock
    // a concurrent device-management call (sprite gen is bound by `FFMPEG_TIMEOUT_SECONDS`
    // but seconds is enough to be felt). Idempotent if endSessionForDevice already ran.
    // The caller-supplied id covers the mapping-already-cleared case (see kdoc).
    setOfNotNull(cancelledSessionId, knownSessionId).forEach { sessionCaptureCoordinator.stopForSession(it) }
    return scopeCancelled || cancelledSessionId != null
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

  /** @return true if an active scope existed for the device and was cancelled. */
  private fun cancelAndRemoveCoroutineScopeForDeviceIfActive(trailblazeDeviceId: TrailblazeDeviceId): Boolean {
    var cancelledActiveScope = false
    coroutineScopeByDevice[trailblazeDeviceId]?.let { coroutineScopeForDevice ->
      Console.log("Cancelling coroutine job for device: ${trailblazeDeviceId.instanceId}")
      Console.log("  Scope isActive BEFORE cancel: ${coroutineScopeForDevice.isActive}")
      try {
        if (coroutineScopeForDevice.isActive) {
          cancelledActiveScope = true
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
    return cancelledActiveScope
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

  /** Outcome of the pure [appendNewTarget] decision — one variant per caller-observable branch. */
  internal sealed interface AppendDecision {
    /** [targetId] is net-new: [updatedTargets] is [newTarget] appended to the current set. */
    data class Appended(
      val newTarget: TrailblazeHostAppTarget,
      val updatedTargets: Set<TrailblazeHostAppTarget>,
    ) : AppendDecision

    /** [targetId] is already in the current set; [existing] is that target (idempotent success). */
    data class AlreadyPresent(val existing: TrailblazeHostAppTarget) : AppendDecision

    /** [targetId] is neither current nor in fresh discovery — nothing to append. */
    data object NotDiscovered : AppendDecision
  }

  companion object {

    /**
     * Pure decision for live registration. Total function (no null): returns
     * [AppendDecision.Appended] when [targetId] resolves in [fresh] - net-new ids are appended
     * and an already-registered id is REPLACED with the fresh instance (an Edit Target save
     * changes the on-disk manifest, and serving the stale snapshot made an edited target read
     * "Not installed on any connected device" until a daemon restart). Returns
     * [AppendDecision.AlreadyPresent] only when the id is registered but absent from [fresh]
     * (nothing newer to swap in), and [AppendDecision.NotDiscovered] otherwise. Other entries
     * survive by identity, and in-flight runs keep their captured reference to a replaced
     * instance (nothing mutates it), which is what keeps a live swap safe.
     */
    internal fun appendNewTarget(
      current: Set<TrailblazeHostAppTarget>,
      fresh: Set<TrailblazeHostAppTarget>,
      targetId: String,
    ): AppendDecision {
      val existing = current.firstOrNull { it.id == targetId }
      val newTarget = fresh.firstOrNull { it.id == targetId }
        ?: return existing?.let { AppendDecision.AlreadyPresent(it) } ?: AppendDecision.NotDiscovered
      val updated = if (existing == null) current + newTarget else current - existing + newTarget
      return AppendDecision.Appended(newTarget = newTarget, updatedTargets = updated)
    }

    /**
     * Runs the additive CAS-append loop against [holder] (the manager's live-target
     * [MutableStateFlow]): re-decides via [appendNewTarget], retries on a lost race, and on a
     * net-new [AppendDecision.Appended] swaps [holder]'s value — which emits to every collector
     * of [availableAppTargetsFlow]. Returns the resolved target (net-new append, or the existing
     * instance for an idempotent [AppendDecision.AlreadyPresent]) or null for
     * [AppendDecision.NotDiscovered]. Extracted from [registerNewTarget] so the emission is
     * unit-testable against a real [MutableStateFlow] without constructing the manager (its ~14
     * constructor dependencies make direct construction impractical — same rationale as
     * [appendNewTarget]).
     */
    internal fun casAppendNewTarget(
      holder: MutableStateFlow<Set<TrailblazeHostAppTarget>>,
      fresh: Set<TrailblazeHostAppTarget>,
      targetId: String,
    ): TrailblazeHostAppTarget? {
      while (true) {
        val current = holder.value
        when (val decision = appendNewTarget(current = current, fresh = fresh, targetId = targetId)) {
          is AppendDecision.AlreadyPresent -> {
            Console.log("[TrailblazeDeviceManager] Target '$targetId' is already registered; nothing to do")
            return decision.existing
          }
          AppendDecision.NotDiscovered -> {
            Console.error(
              "[TrailblazeDeviceManager] Live target registration for '$targetId' failed: id not found in " +
                "fresh discovery (${fresh.size} targets: ${fresh.map { it.id }.sorted()}). The target's " +
                "config may not be on disk yet, or discovery filtered it out.",
            )
            return null
          }
          is AppendDecision.Appended ->
            if (holder.compareAndSet(current, decision.updatedTargets)) {
              Console.log(
                "[TrailblazeDeviceManager] Live-registered app target '$targetId' " +
                  "(${decision.updatedTargets.size} targets now available)",
              )
              return decision.newTarget
            }
          // CAS lost to a concurrent mutation — loop and re-decide against the new current set.
        }
      }
    }

    const val PLAYWRIGHT_NATIVE_INSTANCE_ID: String = WebInstanceIds.PLAYWRIGHT_NATIVE
    const val PLAYWRIGHT_ELECTRON_INSTANCE_ID: String = WebInstanceIds.PLAYWRIGHT_ELECTRON

    internal const val DEVICE_DISCOVERY_TIMEOUT_SECONDS = 10L

    // Per-property cosmetic name probe (`getprop` avd_name / product model). Kept strictly less than
    // the overall discovery budget (DEVICE_DISCOVERY_TIMEOUT_SECONDS) so a single wedged probe can't
    // starve discovery of every device — guarded by DeviceNameResolutionTest. Note the
    // reconnect-on-timeout in AndroidHostAdbUtils means a wedged probe can cost up to 2x this before
    // falling back, which is why naming runs in parallel under its own budget below.
    internal const val DEVICE_NAME_RESOLUTION_TIMEOUT_MS = 2_000L

    // Total wall-clock budget discovery spends WAITING for display-name resolution across ALL
    // devices — a single shared deadline (see listConnectedAdbDevices), NOT a per-device cap. It does
    // not bound the underlying probe work (which can run longer: up to 3 properties x 2 attempts x
    // DEVICE_NAME_RESOLUTION_TIMEOUT_MS with the reconnect retry); that work is simply abandoned and
    // the device falls back to its serial name. Kept comfortably under DEVICE_DISCOVERY_TIMEOUT_
    // SECONDS so naming can never blow the overall discovery budget, regardless of device count.
    private const val DEVICE_NAME_RESOLUTION_BUDGET_MS = 6_000L

    // Cap on concurrent name-resolution threads so a host with many devices attached doesn't spawn an
    // unbounded daemon pool; device counts are normally 1-3.
    private const val MAX_NAME_RESOLUTION_THREADS = 8

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
     * Lists connected Android devices via the adb host-services protocol (no `adb` binary spawn).
     * Returns list of (instanceId, description) pairs.
     * For emulators, resolves the AVD name as the description; for physical devices,
     * uses the product model. Falls back to the serial number if resolution fails.
     */
    internal fun listConnectedAdbDevices(): List<Pair<String, String>> {
      // Enumeration is authoritative: `host:devices` is fast and already filters to `device`-state
      // serials (offline/unauthorized dropped upstream). The human-readable name is *cosmetic* and
      // must never remove a confirmed device — a slow/wedged `getprop avd_name` previously sank the
      // entire Android discovery, because the per-probe timeout equalled the overall discovery
      // budget, so the discovery future timed out, returned emptyList, and a perfectly healthy
      // device "disappeared" from `device list`. (Paired with the reconnect-on-timeout fix in
      // AndroidHostAdbUtils for the stale-transport case.)
      val serials = try {
        AndroidHostAdbUtils.listConnectedAdbDevices().map { it.instanceId }
      } catch (e: Exception) {
        Console.log("[loadDevices] [Android] adb devices failed: ${e.message}")
        return emptyList()
      }
      if (serials.isEmpty()) return emptyList()
      // Resolve names concurrently and best-effort on a dedicated, bounded daemon pool — NOT the
      // ForkJoin common pool, whose threads we'd otherwise tie up on blocking ADB probes and starve
      // unrelated parallel work. Each probe is short-bounded (DEVICE_NAME_RESOLUTION_TIMEOUT_MS,
      // strictly < the overall discovery budget); a slow/failed name only degrades the device's
      // label to its serial, never its presence.
      val executor = Executors.newFixedThreadPool(
        serials.size.coerceAtMost(MAX_NAME_RESOLUTION_THREADS),
      ) { r -> Thread(r, "device-name-resolve").apply { isDaemon = true } }
      return try {
        val nameFutures = serials.associateWith { serial ->
          executor.submit(Callable { resolveAndroidDeviceName(serial) })
        }
        awaitNamesUnderSharedDeadline(
          serials = serials,
          budgetMs = DEVICE_NAME_RESOLUTION_BUDGET_MS,
        ) { serial -> nameFutures.getValue(serial) }
      } finally {
        executor.shutdownNow()
      }
    }

    /**
     * Awaits the per-device name [futureFor]s under a SINGLE shared deadline (computed once from
     * [budgetMs]), pairing each serial with its resolved name and falling back to the serial when a
     * future doesn't finish in the time remaining to that deadline. The shared deadline — rather than
     * a fresh budget per `.get()` — is what stops several slow devices from accumulating waits past
     * the outer discovery budget and dropping every device (the "device disappears" regression).
     *
     * [nowNanos] is injected so the deadline behaviour is unit-testable without a real clock or a
     * thread pool.
     */
    internal fun awaitNamesUnderSharedDeadline(
      serials: List<String>,
      budgetMs: Long,
      nowNanos: () -> Long = System::nanoTime,
      futureFor: (String) -> Future<String>,
    ): List<Pair<String, String>> {
      val deadlineNanos = nowNanos() + TimeUnit.MILLISECONDS.toNanos(budgetMs)
      return buildAndroidDeviceList(serials) { serial ->
        try {
          val remainingMs = (deadlineNanos - nowNanos()) / 1_000_000
          if (remainingMs <= 0) null
          else futureFor(serial).get(remainingMs, TimeUnit.MILLISECONDS)
        } catch (_: Exception) {
          null
        }
      }
    }

    /**
     * Pure policy: pair every confirmed-present [serials] entry with a display name, falling back to
     * the serial when [resolveName] returns null/blank or throws. This guards the invariant that
     * naming is best-effort and *never* removes a device from discovery — the fix for a wedged
     * cosmetic `getprop` silently dropping a healthy device — so it is unit-tested directly with no
     * device required.
     */
    internal fun buildAndroidDeviceList(
      serials: List<String>,
      resolveName: (String) -> String?,
    ): List<Pair<String, String>> = serials.map { serial ->
      val name = runCatching { resolveName(serial) }.getOrNull()?.takeIf { it.isNotBlank() } ?: serial
      serial to name
    }

    /**
     * Resolves a human-readable name for an Android device given its ADB serial.
     * For emulators, queries the AVD name; for physical devices, queries the product model.
     * Falls back to the serial number if both fail.
     */
    private fun resolveAndroidDeviceName(serial: String): String {
      val deviceId = TrailblazeDeviceId(serial, TrailblazeDevicePlatform.ANDROID)
      // For emulators, try AVD name — stored in different properties depending on
      // the emulator/API version.
      if (serial.startsWith("emulator-")) {
        val avdName =
          queryAdbProperty(deviceId, "ro.boot.qemu.avd_name")
            ?: queryAdbProperty(deviceId, "ro.kernel.qemu.avd_name")
        if (avdName != null) return avdName
      }
      // Fall back to product model (e.g., "sdk_gphone64_arm64", "Pixel 6")
      return queryAdbProperty(deviceId, "ro.product.model") ?: serial
    }

    private fun queryAdbProperty(deviceId: TrailblazeDeviceId, property: String): String? {
      return try {
        // Cosmetic probe — bounded well under the overall discovery budget
        // (DEVICE_DISCOVERY_TIMEOUT_SECONDS) so a single wedged `getprop` can't starve discovery of
        // every device. A failure here only degrades the device's display name (it falls back to the
        // serial in buildAndroidDeviceList), never its presence in the list.
        val value = AndroidHostAdbUtils.execAdbShellCommandWithTimeout(
          deviceId = deviceId,
          args = listOf("getprop", property),
          timeoutMs = DEVICE_NAME_RESOLUTION_TIMEOUT_MS,
        )?.trim()
        value?.takeIf { it.isNotEmpty() && !it.startsWith("error:", ignoreCase = true) }
      } catch (_: Exception) {
        null
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
