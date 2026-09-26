package xyz.block.trailblaze.ui

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.Callable
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import maestro.Driver
import xyz.block.trailblaze.api.ScreenState
import xyz.block.trailblaze.mcp.AgentImplementation
import xyz.block.trailblaze.devices.TrailblazeConnectedDeviceSummary
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.devices.WebInstanceIds
import xyz.block.trailblaze.capture.CaptureOptions
import xyz.block.trailblaze.host.animations.SessionAnimationDisabler
import xyz.block.trailblaze.host.dexopt.SessionAppCompileEnsurer
import xyz.block.trailblaze.host.turbo.SessionTurboAttacher
import xyz.block.trailblaze.host.capture.SessionCaptureCoordinator
import xyz.block.trailblaze.host.capture.finalizeHostSessionResources
import xyz.block.trailblaze.host.devices.HostProbe
import xyz.block.trailblaze.host.devices.WebBrowserManager
import xyz.block.trailblaze.host.driver.DeviceListingVisibility
import xyz.block.trailblaze.host.driver.BootedIosSimulator
import xyz.block.trailblaze.host.driver.ConnectedAdbDevice
import xyz.block.trailblaze.host.driver.DescriptorDeviceDiscovery
import xyz.block.trailblaze.host.driver.HostDeviceInventory
import xyz.block.trailblaze.host.driver.HostScreenStateDeps
import xyz.block.trailblaze.host.driver.HostDriverDescriptorRegistry
import xyz.block.trailblaze.host.devices.WebBrowserState
import xyz.block.trailblaze.host.recording.DeviceConnectionService
import xyz.block.trailblaze.llm.RunYamlRequest
import xyz.block.trailblaze.llm.TrailblazeLlmModel
import xyz.block.trailblaze.llm.TrailblazeReferrer
import xyz.block.trailblaze.logs.client.TrailblazeLog
import xyz.block.trailblaze.logs.client.TrailblazeSessionManager
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.mcp.BoundDeviceRosterMember
import xyz.block.trailblaze.logs.model.SessionStatus
import xyz.block.trailblaze.logs.model.TraceId
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
import xyz.block.trailblaze.yaml.fromTrailblazeTool
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import xyz.block.trailblaze.host.rules.BasePlaywrightElectronTest
import xyz.block.trailblaze.host.rules.BasePlaywrightNativeTest
import xyz.block.trailblaze.util.AndroidHostAdbUtils
import xyz.block.trailblaze.util.Console
import xyz.block.trailblaze.util.isMacOs

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
   * that produced [initialAppTargets] at startup. Consumed by [registerNewTarget] and
   * [reloadAppTargets]; when null (tests, embedders that don't support live registration),
   * both are unavailable and callers fall back to the restart-required flow.
   *
   * The `failFast` argument is forwarded to `AppTargetDiscovery.discover`. [registerNewTarget]
   * passes false because it only ever ADDS, so a masked failure just means the new id isn't found
   * and the caller is told registration failed. [reloadAppTargets] passes true because it
   * REPLACES, and installing discovery's one-target error fallback over a real set would silently
   * empty the picker.
   */
  private val freshAppTargetsProvider: ((failFast: Boolean) -> Set<TrailblazeHostAppTarget>)? = null,
  val appIconProvider: AppIconProvider,
  val deviceClassifierIconProvider: DeviceClassifierIconProvider,
  private val runYamlLambda: (desktopAppRunYamlParams: DesktopAppRunYamlParams) -> Unit,
  private val installedAppIdsProviderBlocking: (TrailblazeDeviceId) -> Set<String>,
  private val appVersionInfoProviderBlocking: (TrailblazeDeviceId, String) -> AppVersionInfo? = { _, _ -> null },
  val onDeviceInstrumentationArgsProvider: () -> Map<String, String>,
  private val trailblazeAnalytics: TrailblazeAnalytics,
  /**
   * The drivers this app has plugged in. Discovery, listing, screen-state capture and host runs
   * all resolve through it, with no `when (driverType)` arms left to fall back to.
   *
   * Defaults to empty, so a hand-built manager — a test's, typically — drives no devices at all
   * rather than half-driving whichever ones happened to have a fallback: nothing discovers a
   * device and screen-state capture throws. An app that wants any driver passes its registry.
   */
  val hostDriverDescriptors: HostDriverDescriptorRegistry = HostDriverDescriptorRegistry.EMPTY,
  /**
   * Single point of ownership for per-`SessionId` capture across CLI, MCP, and desktop-UI
   * paths. See [SessionCaptureCoordinator] for why this exists at the device-manager
   * level (where session lifecycle already lives) rather than scattered across runners.
   *
   * A parameter so a test can hand in a coordinator with a fake capture-session factory and see
   * what capture was actually started with — the app id in particular, which this class resolves.
   */
  val sessionCaptureCoordinator: SessionCaptureCoordinator = SessionCaptureCoordinator(logsRepo),
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
   * Target saves), and [reloadAppTargets] replaces the whole set on a workspace switch (the one
   * path that can also drop targets). Individual target objects are immutable - a run that
   * captured its resolved target at run start keeps that reference and stays consistent even if
   * the set entry is later swapped. Every read sees a complete immutable snapshot (old or new,
   * never partial). The `target-drift` endpoint stays as the backstop for the cases neither path
   * covers — an in-place edit of the current workspace's trailmaps, or a reload that failed.
   *
   * This is a point-in-time snapshot getter — desktop composables that must recompose on a
   * live append should collect [availableAppTargetsFlow] instead.
   */
  val availableAppTargets: Set<TrailblazeHostAppTarget>
    get() = _availableAppTargetsFlow.value

  /**
   * Serializes every call into [freshAppTargetsProvider]. A discovery pass doesn't just return a
   * set — it also REPLACES the process-global workspace tool and toolset overlays (see
   * `AppTargetDiscovery`), and those overlays only make sense paired with the target set from the
   * same pass. Two overlapping passes would otherwise interleave, leaving one workspace's overlays
   * installed alongside another's targets, so a tool the live target declares resolves as missing.
   *
   * Serializing also removes the need to detect a superseded reload: [freshAppTargetsProvider]
   * reads the workspace directory at call time (not one captured when the switch was requested),
   * so whichever pass runs last necessarily discovers the workspace the user actually settled on.
   */
  private val appTargetDiscoveryLock = Mutex()

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
  suspend fun registerNewTarget(targetId: String): TrailblazeHostAppTarget? {
    val provider = freshAppTargetsProvider ?: run {
      Console.log(
        "[TrailblazeDeviceManager] Live target registration for '$targetId' skipped: " +
          "no fresh-discovery provider wired (falling back to restart-required flow)",
      )
      return null
    }
    return appTargetDiscoveryLock.withLock {
      val fresh = try {
        provider(false)
      } catch (e: Exception) {
        Console.error(
          "[TrailblazeDeviceManager] Live target registration for '$targetId' failed during discovery: " +
            "${e::class.simpleName}: ${e.message}\n${e.stackTraceToString()}",
        )
        return@withLock null
      }
      // The live set IS the StateFlow's value, so the CAS append emits to every desktop
      // composable collecting [availableAppTargetsFlow] — that's what makes the new target
      // appear in the picker without a restart.
      casAppendNewTarget(_availableAppTargetsFlow, fresh, targetId)
    }
  }

  /**
   * Replaces the live app-target set with a full re-discovery against the CURRENT workspace, so
   * switching workspaces changes the target picker without a daemon restart. Called from the
   * settings patch that moves the trails directory.
   *
   * Unlike [registerNewTarget] this is deliberately NOT additive: a target the new workspace
   * doesn't declare is dropped. Keeping the previous workspace's targets in the picker is the
   * bug, not the safety net — a switch is a statement about which workspace is in play. Target
   * objects are immutable and a run captures its resolved target at run start, so an in-flight
   * run keeps working against the reference it already holds.
   *
   * Discovery runs with `failFast = true` so a broken workspace surfaces as a thrown exception
   * rather than as discovery's one-target fallback set, which this method would otherwise install
   * over a perfectly good set.
   *
   * Discovery takes long enough (disk plus the scripted-tool analyzer) that two quick switches can
   * overlap, so the pass runs under [appTargetDiscoveryLock]. Serialized passes each read the
   * workspace directory saved at the moment they run, so the last one to finish is the last one to
   * start and the picker settles on the workspace the user actually chose.
   *
   * Returns the new set, or null when the reload couldn't run (no provider wired, or discovery
   * threw). In both null cases the live set is left exactly as it was, so the `target-drift`
   * endpoint still sees a difference and the UI's restart nudge remains the fallback.
   */
  suspend fun reloadAppTargets(): Set<TrailblazeHostAppTarget>? =
    swapAppTargets(
      holder = _availableAppTargetsFlow,
      provider = freshAppTargetsProvider?.let { discover -> { discover(true) } },
      lock = appTargetDiscoveryLock,
    )

  /**
   * Manages the web browser lifecycle for web testing.
   * Use [launchWebBrowser] and [closeWebBrowser] to control the browser.
   */
  val webBrowserManager = WebBrowserManager()

  /**
   * Shared connection service for both the desktop recording tab and the HTTP recording
   * API. Centralizes the platform-specific connect logic so both surfaces stay in sync.
   */
  val connectionService = DeviceConnectionService(this)

  /**
   * Shared registry of live [xyz.block.trailblaze.recording.DeviceScreenStream] instances, keyed by
   * device. It's what the `/devices/api/stream` live-video socket and the `/rpc` screen-poll /
   * SubscribeFrames handlers resolve a device against. Owned here (one per daemon) so every surface
   * that connects a device — the `/rpc` device viewer and Trail Runner's interactive recorder — can
   * publish into the same registry, and the shared streaming endpoint can serve any of them.
   */
  val hostDeviceSessionManager = xyz.block.trailblaze.host.recording.rpc.HostDeviceSessionManager()

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

  // Internal (not private) so WebModeGateMembershipTest can exercise the gate directly.
  internal val targetDeviceFilter: (List<TrailblazeConnectedDeviceSummary>) -> List<TrailblazeConnectedDeviceSummary> =
    { connectedDeviceSummaries ->
      val isWebMode =
        settingsRepo.serverStateFlow.value.appConfig.testingEnvironment ==
          TrailblazeServerState.TestingEnvironment.WEB
      connectedDeviceSummaries.filter { connectedDeviceSummary ->
        val driverType = connectedDeviceSummary.trailblazeDriverType
        // An addressable-but-unlisted driver stays in device state whatever the user's enabled
        // -drivers setting says: `--device <its id>` has to keep working, and the listings that
        // shouldn't show it strip it themselves via DeviceListingVisibility.
        if (hostDriverDescriptors.forDriverOrNull(driverType)?.listingVisibility ==
          DeviceListingVisibility.ADDRESSABLE_NOT_LISTED
        ) {
          return@filter true
        }
        when {
          // Virtual devices (web browsers, desktop windows) — only shown when web mode is
          // enabled. Keyed on the platform fact, not driver names, so a new virtual driver
          // is gated the same way without a change here.
          driverType.platform.usesVirtualDevice -> isWebMode
          else -> settingsRepo.getEnabledDriverTypes().contains(driverType)
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
   * Sessions whose capture is still starting, completed once it has (or has failed to). Every
   * member of a named cast is on the session as soon as it is opened, but its recorders start only
   * after [sessionCreationLock] is released — so a call on the session in that window waits here,
   * or its first action would run before its device's recording does. Guarded by
   * [sessionCreationLock] for insertion; completed and removed by the call that opened the session.
   */
  private val captureStarting = mutableMapOf<SessionId, CompletableFuture<Unit>>()

  /**
   * The session each device was brought onto by a cast (see [joinSessionLocked]), as opposed to one
   * it opened itself. A dissolved cast lets go of these, so a session another client was running
   * goes on without the devices the cast added. Guarded by [sessionCreationLock].
   */
  private val joinedThroughCast = mutableMapOf<TrailblazeDeviceId, SessionId>()

  /**
   * Sessions a cast member opened while in its cast. Any other session a cast shares was running
   * before the cast adopted it — another client's, typically — and the device it runs on is not the
   * cast's to take off it. Guarded by [sessionCreationLock].
   */
  private val openedByCast = mutableSetOf<SessionId>()

  /**
   * The named casts MCP sessions have bound, keyed by the MCP session that bound them — see
   * [setDeviceRoster]. Guarded by [sessionCreationLock], because a roster decides which devices a
   * new Trailblaze session is tracked on.
   */
  private val deviceRosters = LinkedHashMap<String, List<BoundDeviceRosterMember>>()

  // SupervisorJob: children of this scope include long-lived collectors, fire-and-forget
  // discovery refreshes (which rethrow on discovery failure), and every [DeviceAppInventory]
  // probe. Without a supervisor, one failed child would cancel the scope's Job and every future
  // probe would come back as an already-cancelled Deferred.
  private val loadDevicesScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

  /**
   * On-demand per-device app inventory (installed app IDs + version info). Discovery no longer
   * probes this eagerly — the per-device probe (`pm list packages` / `simctl listapps`) was the
   * dominant cost of every enumeration whenever an iOS simulator was booted (OSS issue
   * block/trailblaze#216). Consumers that need inventory pull it via [refreshAppInventory] /
   * [refreshAppInventoryAsync]; discovery only [DeviceAppInventory.prune]s disconnected devices.
   */
  private val appInventory = DeviceAppInventory(
    scope = loadDevicesScope,
    installedAppIdsProvider = { deviceId ->
      // runWithTimeout's null (timeout / failure) IS the probe-failure signal the inventory
      // wants — do not coerce it to an empty set, which downstream reads as "nothing installed".
      runWithTimeout(10, deviceId.instanceId, "installed apps") {
        installedAppIdsProviderBlocking(deviceId)
      }
    },
    appVersionInfoProvider = { deviceId, appId ->
      runWithTimeout(10, deviceId.instanceId, "version info for $appId") {
        appVersionInfoProviderBlocking(deviceId, appId)
      }
    },
  )

  /** Last-probed installed app IDs per device — see [DeviceAppInventory.installedAppIdsByDeviceFlow]. */
  val installedAppIdsByDeviceFlow: StateFlow<Map<TrailblazeDeviceId, Set<String>>> =
    appInventory.installedAppIdsByDeviceFlow

  /** Last-probed version info per (device, app) — see [DeviceAppInventory.appVersionInfoByDeviceFlow]. */
  val appVersionInfoByDeviceFlow: StateFlow<Map<DeviceAppKey, AppVersionInfo>> =
    appInventory.appVersionInfoByDeviceFlow

  // Mutex to coalesce concurrent loadDevices calls — if one is running, others wait for it.
  private val loadDevicesMutex = kotlinx.coroutines.sync.Mutex()

  // Short-TTL cache so the several *sequential* discovery passes one CLI command fans out into
  // (LIST, platform-select, connect fallback, session pin) share ONE real enumeration instead of
  // each re-running `adb devices` (and, on macOS, `xcrun simctl list`). The per-device installed
  // -apps probe that used to dominate a pass now runs on demand — see [appInventory].
  // See [DeviceDiscoveryCache] for the freshness/invalidation contract.
  private val discoveryCache = DeviceDiscoveryCache(ttlMs = resolveDiscoveryCacheTtlMs())

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
    // Per-run video-capture override (Trail Runner / Run-config "Capture video" toggle); null =
    // default (no video). Threaded into DesktopAppRunYamlParams.captureVideo so it reaches the
    // web / Electron self-instrumented capture path, which the SessionCaptureCoordinator (and thus
    // getOrCreateSessionResolution's captureVideoOverride) skips for WEB.
    captureVideoOverride: Boolean? = null,
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
    //
    // Deliberately STRICTER than both CLI transports, which warn and fall back via
    // [resolveRunTargetApp] — a trail file travelling to another workspace should still run there,
    // where this path serves an operator who can fix the target and re-run. The CLI's fallback is
    // what makes `DesktopAppRunYamlParams.unresolvedDeclaredTarget` necessary; nothing here needs
    // it, because nothing here reaches a run with a substituted target.
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
      // Use title with ID appended for method name (e.g., for_your_business_page_1019)
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
      captureVideo = captureVideoOverride,
      onComplete = onComplete,
      // Same registry the session target resolves against above, so a multi-device configuration's
      // per-device `target:` override resolves by the same rules as `config.target`.
      findTargetById = { id -> availableAppTargets.find { it.id == id } },
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

  /** A run executing on a device, from [beginRun] until [endRun]. */
  class RunInFlight internal constructor(val trailblazeDeviceId: TrailblazeDeviceId)

  private val runsInFlight: MutableSet<RunInFlight> = ConcurrentHashMap.newKeySet()

  /**
   * Registers a run executing on [trailblazeDeviceId] until [endRun]. Runs may share a device, and
   * the device's session pointer names only the latest, so this is how [releaseReplacedSession]
   * tells a replaced interactive session from a sibling run that is still executing — and how a
   * cast member forcing a new session tells a companion it must not take off its session yet.
   *
   * A caller that resolves a session and then dispatches a run on it begins the run BEFORE
   * resolving and ends it once the runner has registered its own, so the run is never unregistered
   * while it holds a session. Such a caller passes it as `callerRun` to [getOrCreateSessionResolution].
   */
  fun beginRun(trailblazeDeviceId: TrailblazeDeviceId): RunInFlight =
    RunInFlight(trailblazeDeviceId).also(runsInFlight::add)

  /**
   * Ends [run]. Idempotent. The device's last run ending releases the sessions replaced while it ran
   * (see [releaseReplacedSession]) and lets the device follow its cast (see [followCastAfterRun]).
   */
  fun endRun(run: RunInFlight) {
    if (!runsInFlight.remove(run)) return
    val device = run.trailblazeDeviceId
    if (runsInFlight.any { it.trailblazeDeviceId == device }) return
    // Unbinds first: they mark a session this device still logged into, which holds its release.
    unbindPendingAfterRuns(device)
    releasePendingAfterRuns(device)
    runCatching { followCastAfterRun(device) }.onFailure {
      Console.log("Moving ${device.instanceId} onto its cast's session failed: ${it.message}")
    }
  }

  /**
   * Replaced sessions whose release [releaseReplacedSession] skipped because a sibling run was
   * executing on the device, performed once its last run ends. Guarded by [sessionCreationLock].
   */
  private val pendingReleases = mutableMapOf<TrailblazeDeviceId, MutableSet<SessionId>>()

  /**
   * Recordings of a device, as (session, name), that a session it left keeps making, stopped once
   * its last run ends (see [stopRecordingLeftBehindAfterRuns]). Guarded by [sessionCreationLock].
   */
  private val pendingUnbinds = mutableMapOf<TrailblazeDeviceId, MutableSet<Pair<SessionId, String>>>()

  /**
   * Cast members left on a replaced session because a run was executing on them, and the session
   * each moves to once its last run ends: device → (replaced, new). Guarded by [sessionCreationLock].
   */
  private val pendingCastMoves = mutableMapOf<TrailblazeDeviceId, Pair<SessionId, SessionId>>()

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
    captureMemoryOverride: Boolean? = null,
    /**
     * The target this session is about to be given, for the caller that is creating the session in
     * order to set one. Capture resolves the app inside the lock below, so a caller that stored the
     * target only after this returned would have capture bound to the daemon-wide target while the
     * tools drove a different app. Passed here instead, it is registered before capture reads it.
     */
    sessionTargetToApply: String? = null,
    /** The run this session is resolved for, begun before this call (see [beginRun]); not a sibling of itself. */
    callerRun: RunInFlight? = null,
  ): DeviceSessionResolution {
    // Two-phase to keep `startForSession` (which can block on adb/ffmpeg/xcrun
    // startup) OUTSIDE `sessionCreationLock` — otherwise every concurrent session
    // creation, end, and cancel across every device queues behind whatever
    // screenrecord is starting next. The lock only protects the
    // session-id-generation + trackActiveSession step.
    val resolution: DeviceSessionResolution
    val startCaptureFor: SessionId?
    val captureDeviceId: String
    val captureTarget: TrailblazeHostAppTarget?
    val captureAppIds: List<String>
    val captureOptions: CaptureOptions
    val replacedSessionId: SessionId?
    // What the cast left when a member forced this session (see moveCastOffReplacedSessionLocked).
    var castMove = CastMove.NONE
    // The opener's own capture start, for a call that joins the session while it is still running.
    var awaitCaptureOf: CompletableFuture<Unit>? = null
    synchronized(sessionCreationLock) {
      val existingSessionId = if (forceNewSession) null else getCurrentSessionIdForDevice(trailblazeDeviceId)
      replacedSessionId = if (forceNewSession) getCurrentSessionIdForDevice(trailblazeDeviceId) else null
      val isNewSession = existingSessionId == null
      val sessionId = existingSessionId ?: TrailblazeSessionManager.generateSessionId(sessionIdPrefix)
      if (isNewSession) {
        trackActiveSession(trailblazeDeviceId, sessionId, deviceSummary)
        // Every member of this device's named cast is on the session from the start; each is
        // recorded once capture has started (one running a session of its own is not, see joinSessionLocked).
        val roster = rosterContaining(trailblazeDeviceId)
        castMove = replacedSessionId?.let { moveCastOffReplacedSessionLocked(trailblazeDeviceId, roster, it, sessionId) } ?: CastMove.NONE
        if (roster.isNotEmpty()) openedByCast += sessionId
        roster.forEach { member -> joinSessionLocked(member.trailblazeDeviceId, sessionId) }
        captureStarting[sessionId] = CompletableFuture()
      } else {
        awaitCaptureOf = captureStarting[sessionId]
      }
      resolution = DeviceSessionResolution(sessionId, isNewSession)
      startCaptureFor = if (isNewSession) sessionId else null
      // Before the capture resolution below reads the registry. A blank id is a caller clearing the
      // override, which leaves the daemon-wide target as the answer — the same thing an absent
      // entry means, so there is nothing to register.
      if (!sessionTargetToApply.isNullOrBlank()) {
        sessionTargetRegistry.set(sessionId, trailblazeDeviceId, sessionTargetToApply)
      }
      captureDeviceId = trailblazeDeviceId.instanceId
      // Resolve appId outside the lock would race with target updates, so capture both
      // the target-override + effective daemon-wide target (persisted selection → workspace
      // defaults.target) here. Raw selectedTargetAppId would be null under a workspace-default
      // target, dropping app-scoping from the capture.
      val appConfig = settingsRepo.serverStateFlow.value.appConfig
      val captureTargetId = sessionTargetRegistry.get(sessionId, trailblazeDeviceId) ?: getCurrentSelectedTargetApp()?.id
      // A target id (`sampleapp`) is not an installed package, and capture needs the package: a
      // memory reading resolves the app's pid with `pidof <appId>`, which answers nothing for a
      // target id and leaves every event device-only. Resolve the target back to the ids it may run
      // under on this platform — the same resolution the CLI path does in `DesktopYamlRunner`, so
      // both paths scope capture to the same app.
      captureTarget = availableAppTargets.find { it.id == captureTargetId }
      captureAppIds = captureTarget
        ?.getPossibleAppIdsForPlatform(trailblazeDeviceId.trailblazeDevicePlatform)
        .orEmpty()
      // Resolve capture options from the daemon's `appConfig` toggles. Per-run CLI
      // flags (--capture-video / --capture-logcat) are layered on by
      // `DesktopYamlRunner` when the CLI path also fires `startForSession`; the
      // coordinator's reservation pattern makes that second call a no-op so the
      // appConfig-derived options here are what win for MCP-only paths. That makes
      // `trailblaze config capture-video true` the way interactive `session start` and
      // MCP sessions opt into video — they have no positive per-run flag to pass.
      captureOptions = CaptureOptions.hostCaptureOptions(
        captureVideo = captureVideoOverride,
        persistedCaptureVideo = appConfig.captureVideo,
        captureLogcat = captureLogcatOverride ?: appConfig.captureLogcat,
        captureIosLogs = captureIosLogsOverride ?: appConfig.captureIosLogs,
        captureMemory = captureMemoryOverride ?: appConfig.captureMemory,
      )
    }

    // A call that joined a session still starting its capture waits for it (see [captureStarting]).
    awaitCaptureOf?.let { awaitCaptureStart(it, resolution.sessionId, trailblazeDeviceId.instanceId) }
    // Phase 2 (outside the lock): start capture for the new session. Idempotent — the
    // CLI path's `DesktopYamlRunner.captureSessionStarted` callback may also fire
    // `startForSession` later, but the coordinator's reserve-then-start protocol
    // ensures only one wins.
    // Before the new session's capture starts, for the same reason `DesktopYamlRunner` releases
    // first: the replaced session restores the device settings it changed before the new one reads
    // them. A run that forces a new session reserves it here, so the runner never sees the old one.
    (listOfNotNull(replacedSessionId) + castMove.alsoLeft).forEach {
      releaseReplacedSession(trailblazeDeviceId, it, resolution.sessionId, callerRun)
    }
    stopRecordingLeftBehind(castMove.stillRecording)
    stopRecordingLeftBehindAfterRuns(trailblazeDeviceId, castMove.openerStillRecording, callerRun)
    if (startCaptureFor != null) {
      try {
        sessionCaptureCoordinator.startForSession(
          sessionId = startCaptureFor,
          deviceId = captureDeviceId,
          platform = trailblazeDeviceId.trailblazeDevicePlatform,
          options = captureOptions,
          // Resolved out here because it asks the device what is installed, which must not happen
          // under `sessionCreationLock` — and not asked at all when no stream will use the answer,
          // so a session that captures nothing does not wait on a device probe first.
          appId = if (captureOptions.hasAnyCaptureEnabled) {
            resolveCaptureAppId(trailblazeDeviceId, captureTarget, captureAppIds)
          } else {
            null
          },
        )
        // The cast as it stands now, not as it was at open: a member removed while capture was
        // starting is not started here (its removal waits on this start, see setDeviceRoster).
        val castNow = synchronized(sessionCreationLock) {
          membersOnSessionLocked(rosterContaining(trailblazeDeviceId), startCaptureFor)
        }
        if (castNow.isNotEmpty()) recordRoster(startCaptureFor, castNow)
      } finally {
        // Every recorder is running (or failed to start): calls that joined meanwhile may act now.
        synchronized(sessionCreationLock) { captureStarting.remove(startCaptureFor) }?.complete(Unit)
      }
      // Experimental opt-in (gated internally, idempotent like the capture start above); restored
      // by the finalization barrier every session-end path runs.
      SessionAnimationDisabler.startForSession(startCaptureFor, trailblazeDeviceId)
      // `captureAppIds` is every applicationId the target may run under, in declared priority
      // order — which flavor is installed varies by lane, and both hooks below pick among them by
      // what they find on the device.
      //
      // On by default (kill switch inside), idempotent per session: makes sure the app has compiled
      // ART artifacts so its cold starts do not re-verify the APK. Before turbo, whose own compile
      // is unforced and finds the artifacts already there.
      SessionAppCompileEnsurer.startForSession(
        sessionId = startCaptureFor.toString(),
        deviceId = trailblazeDeviceId,
        candidateAppIds = captureAppIds,
      )
      SessionTurboAttacher.startForSession(
        sessionId = startCaptureFor.toString(),
        deviceId = trailblazeDeviceId,
        candidateAppIds = captureAppIds,
      )
    }
    return resolution
  }

  /**
   * Which of a target's declared applicationIds capture should sample on THIS device.
   *
   * A target commonly declares several — a debug id, an internal id, the production id — and only
   * one of them is installed on any given device. Taking the first declared one binds capture to
   * an app that is not there, and the failure is quiet: every reading reports the app as not
   * running, so the session records device memory and nothing else. Ask the device instead, the
   * same way every other caller does ([TrailblazeHostAppTarget.getAppIdIfInstalled]).
   *
   * The probe is skipped when there is nothing to choose between, so the common single-id target
   * costs no device round trip. When nothing can be learned about the device at all, the probe
   * answers an empty set and this falls back to the first declared id — no worse than not asking,
   * and logged so it is diagnosable. See [probeInstalledAppIdsForCapture] for what it tries first.
   */
  internal fun resolveCaptureAppId(
    trailblazeDeviceId: TrailblazeDeviceId,
    target: TrailblazeHostAppTarget?,
    candidateAppIds: List<String>,
    installedAppIdsProvider: (TrailblazeDeviceId) -> Set<String> = { probeInstalledAppIdsForCapture(it) },
  ): String? {
    if (target == null || candidateAppIds.size < 2) return candidateAppIds.firstOrNull()
    val installed = installedAppIdsProvider(trailblazeDeviceId)
    val resolved = target.getAppIdIfInstalled(trailblazeDeviceId.trailblazeDevicePlatform, installed)
    if (resolved == null) {
      Console.log(
        "[TrailblazeDeviceManager] none of ${target.id}'s app ids [${candidateAppIds.joinToString()}] " +
          "are installed on ${trailblazeDeviceId.instanceId} (${installed.size} package(s) seen); " +
          "capture will use ${candidateAppIds.first()}",
      )
    }
    return resolved ?: candidateAppIds.first()
  }

  /**
   * What is installed on [deviceId] right now, for the purpose of scoping capture.
   *
   * Asks the device rather than reading the cached inventory first. The cache holds whatever the
   * last probe saw, which can predate a reinstall that swapped one declared flavor for another —
   * and scoping capture to a package that is no longer there is the exact silent failure
   * [resolveCaptureAppId] exists to prevent: every reading reports the app as not running.
   *
   * Bounded like every other device probe. The previous direct call had no deadline, so an
   * uncached multi-id target against a wedged transport held up session startup indefinitely.
   *
   * An EMPTY answer counts as no answer, not as "nothing is installed". A running device always
   * has packages, so empty means the probe broke without saying so, and taking it at face value
   * would throw away a perfectly usable cached inventory. So a probe that times out, throws, or
   * comes back empty answers with the cached inventory — stale is
   * still better than nothing — and an empty set when there is no cache either, which
   * [resolveCaptureAppId] reads as "could not tell" and answers with the first declared id.
   */
  internal fun probeInstalledAppIdsForCapture(
    deviceId: TrailblazeDeviceId,
    timeoutSeconds: Long = CAPTURE_APP_PROBE_TIMEOUT_SECONDS,
  ): Set<String> {
    val fresh = runWithTimeout(timeoutSeconds, deviceId.instanceId, "installed apps for capture") {
      installedAppIdsProviderBlocking(deviceId)
    }
    if (!fresh.isNullOrEmpty()) return fresh
    val cached = installedAppIdsByDeviceFlow.value[deviceId]?.takeIf { it.isNotEmpty() }
    Console.log(
      "[TrailblazeDeviceManager] installed-app probe " +
        (if (fresh == null) "did not answer" else "found no packages") +
        " for ${deviceId.instanceId} while scoping capture; " +
        if (cached != null) "using the last inventory seen (${cached.size} package(s))" else "no inventory cached",
    )
    return cached ?: emptySet()
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
      // When this call is what creates the session, capture has to see the target before it starts.
      sessionTargetToApply = appTargetId,
    )
    // Still the authority for an EXISTING session, and for clearing the override. Capture on an
    // existing session has already bound its app, so changing the target mid-session re-points the
    // tools but not the memory readings.
    sessionTargetRegistry.set(resolution.sessionId, trailblazeDeviceId, appTargetId)
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
    return sessionTargetRegistry.get(sessionId, trailblazeDeviceId)
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
    val (sessionId, sessionDevices) = synchronized(sessionCreationLock) {
      val current = getCurrentSessionIdForDevice(trailblazeDeviceId) ?: return null
      val devices = forgetSessionLocked(current)
      sessionTargetRegistry.clear(current)
      current to devices
    }
    // The device's registered driver goes with the session, gracefully rather than forcefully.
    // Usually a run's own cleanup already closed it and there is nothing here, but an MCP-referrer
    // run deliberately leaves its driver registered between tool calls, and this is where an MCP
    // session ending finally lets go of it - the only other release is a later run displacing it.
    // A named cast's session is every member's, so every member's driver goes with it.
    (sessionDevices + trailblazeDeviceId).forEach(::releaseDriversForDevice)
    // Web browser intentionally NOT closed here. The browser is the "device" — durable
    // across sessions just like an Android emulator or iOS simulator. Per-session state
    // isolation (cookies, localStorage, IndexedDB, tabs) is the job of
    // PlaywrightBrowserManager.resetSession(), which is called by TrailblazeHostYamlRunner
    // at session start and recreates the BrowserContext for true newContext-level isolation.

    // Finish downstream evidence before the session directory appears complete. Hold any
    // failure until the session-end bookkeeping below completes — throwing here would leave a
    // zombie session (cleared from the active map but never marked ended).
    val finalizationFailure =
      runCatching {
        finalizeHostSessionResources(listOf(sessionId), sessionCaptureCoordinator::stopForSession)
      }.exceptionOrNull()

    Console.log("Ended session $sessionId for device: ${trailblazeDeviceId.instanceId}")

    // The session ran from its first log to its last, not until this release. Read after
    // finalization, which flushes log writes still queued (the iOS runner's) but writes no logs of
    // its own — its capture artifacts are not log files, so the teardown time is not counted.
    val activityWindow = logsRepo.activityWindowMs(sessionId)
    val durationMs = activityWindow?.let { it.last - it.first } ?: 0L
    // Write times are truncated to the millisecond while a log's own timestamp is not, so the last
    // log can be stamped later within that millisecond. Ending at its start would sort the end
    // before a Started log written in it, and the session would read as still in progress.
    val endedAt = activityWindow?.let { kotlinx.datetime.Instant.fromEpochMilliseconds(it.last + 1) }
      ?: kotlinx.datetime.Clock.System.now()

    // Write session end log. A failed finalization means the session's captured evidence may be
    // incomplete, which is a warning on the session, not its outcome: releasing the device says
    // nothing about whether the work done in the session succeeded.
    try {
      val sessionStatus = SessionStatus.Ended.Succeeded(
        durationMs = durationMs,
        captureWarning = finalizationFailure?.let(::describeCaptureFailure),
      )
      val sessionEndLog = TrailblazeLog.TrailblazeSessionStatusChangeLog(
        session = sessionId,
        timestamp = endedAt,
        sessionStatus = sessionStatus,
      )
      logsRepo.saveLogToDisk(sessionEndLog)
    } catch (e: Exception) {
      Console.log("Failed to write session end log: ${e.message}")
      // Don't fail session end if log write fails
    }

    // Now that the session is consistently ended, surface incomplete-evidence loudly.
    finalizationFailure?.let { throw it }

    return sessionId
  }

  /**
   * The finalization barrier's own message only counts the failures ("1 host session finalizer(s)
   * failed …"), so the first cause — the capture that actually failed — goes with it.
   */
  private fun describeCaptureFailure(failure: Throwable): String =
    listOfNotNull(failure.message, failure.cause?.message)
      .distinct()
      .joinToString(": ")
      .ifBlank { failure::class.simpleName ?: "Session finalization failed" }

  suspend fun runTool(
    trailblazeDeviceId: TrailblazeDeviceId,
    trailblazeTool: TrailblazeTool,
    referrer: TrailblazeReferrer
  ) {
    // Single-tool dispatch encodes a bare tool-wrapper envelope (`- <toolName>:`), decoded by the
    // runners via decodeTrailOrToolEnvelope → decodeTools — never the legacy list-shape trail
    // parser. Same resulting trail item (one ToolTrailItem) the old `- tools:` synthesis produced.
    val yaml = createTrailblazeYaml().encodeTools(listOf(fromTrailblazeTool(trailblazeTool)))
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
   * Captures the current screen state for a device, through that device's driver descriptor.
   *
   * This method is used from the MCP server.
   */
  suspend fun getCurrentScreenState(trailblazeDeviceId: TrailblazeDeviceId): ScreenState? {
    val deviceState = getDeviceState(trailblazeDeviceId) ?: return null
    val driverType = deviceState.device.trailblazeDriverType

    // Contained like descriptor discovery is: a plug-in that throws costs the caller this capture,
    // not the whole MCP-facing screen-state call.
    hostDriverDescriptors.forDriverOrNull(driverType)?.let { descriptor ->
      return try {
        descriptor.screenState(
          driverType,
          trailblazeDeviceId,
          HostScreenStateDeps(activeMaestroDriver = ::getActiveDriverForDevice),
        )
      } catch (e: CancellationException) {
        // A capture is cancellable work; swallowing this leaves the caller's coroutine looking
        // alive and makes daemon shutdown unresponsive.
        throw e
      } catch (e: Exception) {
        Console.log("❌ Exception getting screen state from $driverType descriptor: ${e.message}")
        e.printStackTrace()
        null
      }
    }

    // Every driver captures through its descriptor, so reaching here means one isn't plugged in.
    error(
      "$driverType has no screen-state path: no HostDriverDescriptor is registered for it. Add " +
        "one to this app config's hostDriverDescriptors.",
    )
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
  suspend fun loadDevicesSuspend(
    applyDriverFilter: Boolean = true,
    forceRefresh: Boolean = false,
  ): List<TrailblazeConnectedDeviceSummary> {
    // Fast path: a discovery pass finished within the TTL — reuse its result without touching
    // adb/simctl. This is what collapses one command's several sequential passes to a single
    // real enumeration.
    if (!forceRefresh && discoveryCache.isFresh()) {
      return cachedDevices(applyDriverFilter)
    }

    // Miss: serialize on the mutex. This also coalesces genuinely concurrent callers — whoever
    // loses the race re-checks freshness under the lock and returns the winner's cached result
    // instead of starting a second enumeration.
    return loadDevicesMutex.withLock {
      if (!forceRefresh && discoveryCache.isFresh()) {
        cachedDevices(applyDriverFilter)
      } else {
        // markRefreshed only on success — a thrown discovery must not leave the cache "fresh".
        loadDevicesSuspendImpl(applyDriverFilter).also { discoveryCache.markRefreshed() }
      }
    }
  }

  /**
   * Returns the last discovered device list without re-running discovery, from the unfiltered
   * [_allDiscoveredDevicesFlow] that [loadDevicesSuspendImpl] refreshes on every pass.
   *
   * Mirrors a real pass exactly. [targetDeviceFilter] depends on `testingEnvironment` and the
   * enabled driver types, which can change (e.g. the user selects Web) between the discovery pass
   * and a cached read within the TTL, so the filter is (re-)applied *here* against the current
   * config rather than serving a pre-filtered snapshot. And like a real pass — which always writes
   * the filtered set to [deviceStateFlow] regardless of [applyDriverFilter] — a cache hit
   * republishes it too, so callers that read [deviceStateFlow] after `loadDevicesSuspend()` for
   * its side effect (e.g. `GetConnectedDevicesHandler`) see the current-config set, not a stale
   * variant. Republishing an unchanged set is a no-op emission ([DeviceManagerState] is a data
   * class, so [MutableStateFlow] dedupes equal values).
   */
  private fun cachedDevices(applyDriverFilter: Boolean): List<TrailblazeConnectedDeviceSummary> {
    val all = _allDiscoveredDevicesFlow.value
    val filtered = targetDeviceFilter(all)
    publishFilteredDeviceState(filtered)
    return if (applyDriverFilter) filtered else all
  }

  /**
   * The last scan's devices, unfiltered, without scanning again — empty if nothing has scanned yet.
   *
   * Republishes [deviceStateFlow] against the current driver config, as a cache hit in
   * [loadDevicesSuspend] does. Tool dispatch routes on [deviceStateFlow], so a caller that answers
   * from the last scan instead of loading devices must not skip that, or a changed driver setting
   * would reach the tool list but not the dispatch.
   */
  fun lastScannedDevices(): List<TrailblazeConnectedDeviceSummary> {
    if (_allDiscoveredDevicesFlow.value.isEmpty()) return emptyList()
    return cachedDevices(applyDriverFilter = false)
  }

  /** Stands in for a finished scan, for tests that need a device list without a device. */
  internal fun rememberScanForTest(devices: List<TrailblazeConnectedDeviceSummary>) {
    _allDiscoveredDevicesFlow.value = devices
  }

  /**
   * Publish [filtered] (an already driver-filtered device list) as the current [deviceStateFlow]
   * device set, marking discovery complete. Shared by a real discovery pass and by a filtered
   * cache hit so both leave the state flow reflecting the current config.
   */
  private fun publishFilteredDeviceState(filtered: List<TrailblazeConnectedDeviceSummary>) {
    updateDeviceState { currState ->
      currState.copy(
        devices = filtered.associate { it.trailblazeDeviceId to DeviceState(device = it) },
        isLoading = false,
        error = null,
      )
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
      // Whatever the plugged-in drivers find, given the transports enumerated above so none of
      // them re-runs an enumeration the host already paid for. Runs concurrently with itself and
      // contains its own failures — see [DescriptorDeviceDiscovery].
      val descriptorDevices = DescriptorDeviceDiscovery.discoverAll(
        descriptors = hostDriverDescriptors.descriptors,
        inventory = HostDeviceInventory(
          adbDevices = androidDevices.map { (serial, description) ->
            ConnectedAdbDevice(serial = serial, description = description)
          },
          bootedIosSimulators = iosSimulators.map { (udid, name) ->
            BootedIosSimulator(udid = udid, name = name)
          },
          runningWebBrowsers = webBrowserManager.getAllRunningBrowserSummaries(),
        ),
      )

      // Every device comes from a driver's descriptor, mapped out of the inventory enumerated
      // above: each connected `adb` device under all three Android drivers, each booted simulator
      // under the iOS drivers that are usable on this host, and the web devices (running browsers,
      // the always-on Playwright-native default, an answering Electron CDP endpoint). A descriptor
      // returns nothing when its driver isn't usable here, so an app that doesn't register one
      // never sees its devices.
      val allDevices = descriptorDevices

      Console.log("[loadDevices] Discovered ${allDevices.size} device(s): ${allDevices.map { "${it.trailblazeDriverType.name}/${it.instanceId}" }}")

      // Cache the unfiltered list so concurrent callers who coalesce on loadDevicesMutex with
      // applyDriverFilter=false still see virtual devices (see [allDiscoveredDevicesFlow]).
      _allDiscoveredDevicesFlow.value = allDevices

      // Always filter for device state — Android driver variants share the same
      // TrailblazeDeviceId key (instanceId + platform), so unfiltered results would let
      // the last-added variant overwrite the configured driver type.
      val devicesForState = targetDeviceFilter(allDevices)
      val devicesToReturn = if (applyDriverFilter) devicesForState else allDevices

      // Discovery does NOT probe installed apps / version info — the per-device probe (a
      // `pm list packages` per Android device, a multi-second `simctl listapps` per booted iOS
      // simulator) is exactly what made every enumeration slow (OSS issue block/trailblaze#216). Consumers pull
      // inventory on demand via [refreshAppInventory]; discovery only drops stale entries for
      // devices that are no longer connected.
      appInventory.prune(allDevices.map { it.trailblazeDeviceId }.toSet())

      withContext(Dispatchers.Default) {
        publishFilteredDeviceState(devicesForState)
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
  fun loadDevices() = run {
    // The fire-and-forget entry point is only ever called to *refresh* — UI refresh buttons,
    // app init, and post-topology-change hooks (browser launch/close). Invalidate synchronously
    // here so a concurrent cached read racing the async pass below can't serve a device list that
    // predates the change, then force a fresh pass (bypassing the TTL) inside the coroutine.
    discoveryCache.invalidate()
    loadDevicesScope.launch { loadDevicesSuspend(forceRefresh = true) }
  }

  fun updateDeviceState(deviceStateUpdater: (DeviceManagerState) -> DeviceManagerState) {
    _deviceStateFlow.value = deviceStateUpdater(_deviceStateFlow.value)
  }

  /**
   * Tracks a session on a device.
   * Updates activeDeviceSessionsFlow. If deviceSummary is provided and device isn't
   * already tracked, also adds the device to the devices map.
   */
  /**
   * Declares the named cast the MCP session [rosterId] has bound, in bind order — the interactive
   * counterpart of a trail's `config.devices:`.
   *
   * One Trailblaze session for the whole cast: a session any member opens (or is already running)
   * is tracked on every member, so a `switchDevice` handover keeps logging into the same session
   * instead of opening a second one on the other device, and [SessionCaptureCoordinator.bindDevices]
   * records every display of it. Called again whenever the cast changes: a member added to a cast
   * whose session is live joins it and starts recording from that moment; a member removed leaves
   * the session and its recording stops (what it captured stays in the session); an empty
   * [members] dissolves the cast. A member already running a session of its own is left on it and
   * reported, rather than torn out of live work.
   *
   * The session itself is never opened here — it starts, as always, on the first action a member
   * takes. That keeps a bind cheap and means a cast that never acts records nothing.
   *
   * @return the session the cast shares right now, or null when no member has one yet.
   */
  fun setDeviceRoster(rosterId: String, members: List<BoundDeviceRosterMember>): SessionId? {
    var captureStillStarting: List<Pair<SessionId, CompletableFuture<Unit>>> = emptyList()
    val (sessionId, stopRecording, toRecord) = synchronized(sessionCreationLock) {
      val previous = deviceRosters[rosterId].orEmpty()
      if (members.isEmpty()) deviceRosters.remove(rosterId) else deviceRosters[rosterId] = members
      // The cast's session is one a CURRENT member holds. A member that left may be on a session
      // of its own (it was refused the cast's, see joinSessionLocked); that is not the cast's.
      val sessionId = members.firstNotNullOfOrNull { getCurrentSessionIdForDevice(it.trailblazeDeviceId) }
      val removed = previous.filter { was ->
        members.none { it.name == was.name && it.trailblazeDeviceId == was.trailblazeDeviceId }
      }
      // A removed name's recording lives in the session its device is on NOW — read before it leaves.
      val stopRecording = removed.mapNotNull { was ->
        getCurrentSessionIdForDevice(was.trailblazeDeviceId)?.let { it to was.name }
      }
      if (members.isEmpty()) {
        // A dissolved cast lets go of the devices it brought onto a session. Its own session is
        // normally ended by then (an MCP close ends it before forgetting the cast); a session another
        // client was running goes on without them, and stops recording them (the stops below).
        previous.forEach { was -> joinedThroughCast[was.trailblazeDeviceId]?.let { leaveSessionLocked(was.trailblazeDeviceId, it) } }
      }
      if (sessionId != null) {
        // A device no longer in the cast leaves; a device that only changed name stays. So does the
        // device a session the cast adopted runs on: that session is its own, not the cast's.
        removed
          .filter { was -> members.none { it.trailblazeDeviceId == was.trailblazeDeviceId } }
          .filter { was -> sessionId in openedByCast || joinedThroughCast[was.trailblazeDeviceId] == sessionId }
          .forEach { leaveSessionLocked(it.trailblazeDeviceId, sessionId) }
        members.forEach { joinSessionLocked(it.trailblazeDeviceId, sessionId) }
      }
      captureStillStarting = (stopRecording.map { it.first } + listOfNotNull(sessionId)).distinct()
        .mapNotNull { touched -> captureStarting[touched]?.let { touched to it } }
      Triple(sessionId, stopRecording, sessionId?.let { membersOnSessionLocked(members, it) }.orEmpty())
    }
    // Recorders start and stop outside the lock: they talk to adb / xcrun, and the coordinator has
    // its own reserve-then-start protocol against a session that ends meanwhile.
    //
    // A session whose capture is still starting records no companion yet, and its opener records
    // the cast once that start finishes — so a change touching it waits the start out first. A stop
    // before then finds nothing to stop, and the opener could still start the member it removed.
    captureStillStarting.forEach { (starting, future) -> awaitCaptureStart(future, starting, "roster $rosterId") }
    // Stops first, so a name that moved to another device is free before its new holder starts
    // recording under it.
    stopRecording.forEach { (recordedIn, name) -> sessionCaptureCoordinator.unbindDevice(recordedIn, name) }
    if (sessionId == null) return null
    // After a wait, the cast as it stands by then (a later change may have moved it on).
    val recordNow = if (captureStillStarting.any { it.first == sessionId }) {
      synchronized(sessionCreationLock) { membersOnSessionLocked(deviceRosters[rosterId].orEmpty(), sessionId) }
    } else {
      toRecord
    }
    if (recordNow.isNotEmpty()) recordRoster(sessionId, recordNow)
    return sessionId
  }

  /** Waits, bounded, for [sessionId]'s capture to finish starting (see [captureStarting]). */
  private fun awaitCaptureStart(starting: CompletableFuture<Unit>, sessionId: SessionId, waiter: String) {
    try {
      starting.get(CAPTURE_START_WAIT_SECONDS, TimeUnit.SECONDS)
    } catch (e: TimeoutException) {
      Console.log(
        "Session $sessionId capture still starting after ${CAPTURE_START_WAIT_SECONDS}s; " +
          "$waiter proceeds without waiting further",
      )
    }
  }

  /** The members of [roster] tracked on [sessionId] — the ones its capture may record. Call under [sessionCreationLock]. */
  private fun membersOnSessionLocked(roster: List<BoundDeviceRosterMember>, sessionId: SessionId): List<BoundDeviceRosterMember> =
    roster.filter { _activeDeviceSessionsFlow.value[it.trailblazeDeviceId] == sessionId }

  /** The cast [trailblazeDeviceId] belongs to, or empty when no MCP session has bound it. Call under [sessionCreationLock]. */
  private fun rosterContaining(trailblazeDeviceId: TrailblazeDeviceId): List<BoundDeviceRosterMember> =
    deviceRosters.values.firstOrNull { roster -> roster.any { it.trailblazeDeviceId == trailblazeDeviceId } }.orEmpty()

  /**
   * Tracks [trailblazeDeviceId] on [sessionId] unless it is already on a session. Call under
   * [sessionCreationLock]. A device on a different session is running its own work; it is not
   * moved, and the log line is the only trace, so the report of a cast missing a member has a cause.
   */
  private fun joinSessionLocked(trailblazeDeviceId: TrailblazeDeviceId, sessionId: SessionId) {
    val current = _activeDeviceSessionsFlow.value[trailblazeDeviceId]
    when {
      current == sessionId -> Unit
      current == null -> {
        _activeDeviceSessionsFlow.value += (trailblazeDeviceId to sessionId)
        joinedThroughCast[trailblazeDeviceId] = sessionId
      }
      else -> Console.log(
        "[TrailblazeDeviceManager] ${trailblazeDeviceId.instanceId} is running session $current, " +
          "so it is not joining the cast's session $sessionId",
      )
    }
  }

  /**
   * Takes the cast off [replacedSessionId] when [opener] forces a new session, so the join that
   * follows moves every member onto the new one and the replaced session, left with no device, is
   * released. Call under [sessionCreationLock], after [opener] is tracked on its new session.
   *
   * A cast shares one session: left on the replaced one, the other members would keep it (and its
   * recordings) running until the MCP session closes. The device a session the cast adopted runs
   * on stays: that session is another client's, and still has its device. A member with a run
   * executing (or dispatched, see [beginRun]) stays until its last run ends, then follows the cast
   * (see [pendingCastMoves]).
   *
   * An [opener] that was itself waiting to follow the cast takes the cast off the session it was
   * waiting to move to as well: that is where the rest of the cast went, so leaving them there
   * would split the cast across two new sessions.
   *
   * A session the cast left that still has a device (another client's, or one a busy member is
   * on) is not released, so each member that left it must stop being recorded there instead.
   */
  private fun moveCastOffReplacedSessionLocked(
    opener: TrailblazeDeviceId,
    roster: List<BoundDeviceRosterMember>,
    replacedSessionId: SessionId,
    newSessionId: SessionId,
  ): CastMove {
    // The opener is on a session it opened now, not one the cast brought it onto.
    if (joinedThroughCast[opener] == replacedSessionId) joinedThroughCast.remove(opener)
    val castSessions = listOfNotNull(replacedSessionId, pendingCastMoves.remove(opener)?.second).distinct()
    val left = castSessions.flatMap { from -> moveCastOffLocked(opener, roster, from, newSessionId).map { from to it } }
    val openerLeft = roster.filter { it.trailblazeDeviceId == opener }.map { replacedSessionId to it }
    fun goingOn(moves: List<Pair<SessionId, BoundDeviceRosterMember>>) = moves
      .filter { (from, _) -> from in _activeDeviceSessionsFlow.value.values }
      .map { (from, member) -> from to member.name }
    return CastMove(
      alsoLeft = castSessions - replacedSessionId,
      stillRecording = goingOn(left),
      openerStillRecording = goingOn(openerLeft),
    )
  }

  /**
   * What a forced session took the cast off: other sessions to release, and recordings to stop in
   * sessions that go on — the opener's apart, since another call on its device may still be logging
   * into the session it left (a moved member had none, or it would not have moved).
   */
  private class CastMove(
    val alsoLeft: List<SessionId>,
    val stillRecording: List<Pair<SessionId, String>>,
    val openerStillRecording: List<Pair<SessionId, String>>,
  ) {
    companion object {
      val NONE = CastMove(emptyList(), emptyList(), emptyList())
    }
  }

  /**
   * Stops recording [trailblazeDeviceId] as each name in [left], or, while a run other than
   * [callerRun] is on the device and may still be logging there, once its last run ends (see [endRun]).
   */
  private fun stopRecordingLeftBehindAfterRuns(
    trailblazeDeviceId: TrailblazeDeviceId,
    left: List<Pair<SessionId, String>>,
    callerRun: RunInFlight?,
  ) {
    if (left.isEmpty()) return
    // Queued before the sibling check, so a sibling ending in between still finds them.
    synchronized(sessionCreationLock) { pendingUnbinds.getOrPut(trailblazeDeviceId, ::mutableSetOf) += left }
    if (runsInFlight.any { it.trailblazeDeviceId == trailblazeDeviceId && it !== callerRun }) return
    val taken = synchronized(sessionCreationLock) { left.filter { pendingUnbinds[trailblazeDeviceId]?.remove(it) == true } }
    stopRecordingLeftBehind(taken)
  }

  /** Performs the stops [stopRecordingLeftBehindAfterRuns] held back, once [trailblazeDeviceId] has no run left. */
  private fun unbindPendingAfterRuns(trailblazeDeviceId: TrailblazeDeviceId) {
    val due = synchronized(sessionCreationLock) {
      // A session the device has been put back on records it again.
      pendingUnbinds.remove(trailblazeDeviceId).orEmpty()
        .filter { (sessionId, _) -> _activeDeviceSessionsFlow.value[trailblazeDeviceId] != sessionId }
    }
    stopRecordingLeftBehind(due)
  }

  /** Stops recording each member named in a session it left that goes on without it. Outside [sessionCreationLock]. */
  private fun stopRecordingLeftBehind(left: List<Pair<SessionId, String>>) {
    left.forEach { (sessionId, name) ->
      // A companion still starting is recorded by its opener once it has; stopping before then finds nothing.
      synchronized(sessionCreationLock) { captureStarting[sessionId] }?.let { awaitCaptureStart(it, sessionId, name) }
      sessionCaptureCoordinator.unbindDevice(sessionId, name)
    }
  }

  /** One session's share of [moveCastOffReplacedSessionLocked]; returns the members it moved. Call under [sessionCreationLock]. */
  private fun moveCastOffLocked(
    opener: TrailblazeDeviceId,
    roster: List<BoundDeviceRosterMember>,
    from: SessionId,
    newSessionId: SessionId,
  ): List<BoundDeviceRosterMember> {
    // A member already waiting to follow the cast follows it here instead.
    pendingCastMoves.entries.forEach { entry ->
      if (entry.value.second == from) entry.setValue(entry.value.first to newSessionId)
    }
    val castOwned = from in openedByCast
    val (busy, moved) = roster
      .filter { it.trailblazeDeviceId != opener && _activeDeviceSessionsFlow.value[it.trailblazeDeviceId] == from }
      .filter { castOwned || joinedThroughCast[it.trailblazeDeviceId] == from }
      .partition { member -> runsInFlight.any { it.trailblazeDeviceId == member.trailblazeDeviceId } }
    busy.forEach { pendingCastMoves[it.trailblazeDeviceId] = from to newSessionId }
    moved.forEach {
      _activeDeviceSessionsFlow.value -= it.trailblazeDeviceId
      joinedThroughCast.remove(it.trailblazeDeviceId)
    }
    if (_activeDeviceSessionsFlow.value.values.none { it == from }) openedByCast -= from
    return moved
  }

  /**
   * Moves [trailblazeDeviceId] onto its cast's session once its last run has ended, if a new
   * session left it behind (see [moveCastOffReplacedSessionLocked]), and releases the session it
   * leaves. Nothing moves if the device, the cast or either session changed meanwhile.
   */
  private fun followCastAfterRun(trailblazeDeviceId: TrailblazeDeviceId) {
    var stillRecording: List<Pair<SessionId, String>> = emptyList()
    // Held from the join until the device is recorded: a call resolving [to] meanwhile waits on it
    // the way it waits on a session still opening (see [captureStarting]), so it cannot act unrecorded.
    var recordingGate: CompletableFuture<Unit>? = null
    val (from, to, captureStillStarting) = synchronized(sessionCreationLock) {
      if (runsInFlight.any { it.trailblazeDeviceId == trailblazeDeviceId }) return
      val (from, to) = pendingCastMoves.remove(trailblazeDeviceId) ?: return
      val sessions = _activeDeviceSessionsFlow.value
      val stillOwed = sessions[trailblazeDeviceId] == from &&
        (from in openedByCast || joinedThroughCast[trailblazeDeviceId] == from) &&
        rosterContaining(trailblazeDeviceId).any { it.trailblazeDeviceId != trailblazeDeviceId && sessions[it.trailblazeDeviceId] == to }
      if (!stillOwed) return
      _activeDeviceSessionsFlow.value -= trailblazeDeviceId
      joinedThroughCast.remove(trailblazeDeviceId)
      joinSessionLocked(trailblazeDeviceId, to)
      if (_activeDeviceSessionsFlow.value.values.none { it == from }) {
        openedByCast -= from
      } else {
        // Not released, so it would go on recording the device under its cast name as well.
        stillRecording = rosterContaining(trailblazeDeviceId).filter { it.trailblazeDeviceId == trailblazeDeviceId }.map { from to it.name }
      }
      // A session still opening records every member on it once its capture is up, this one included.
      val stillStarting = captureStarting[to]
      if (stillStarting == null) recordingGate = CompletableFuture<Unit>().also { captureStarting[to] = it }
      Triple(from, to, stillStarting)
    }
    try {
      // Released (or unrecorded) before the new session records the device, so it never has two recorders at once.
      releaseReplacedSession(trailblazeDeviceId, from, to)
      stopRecordingLeftBehind(stillRecording)
      captureStillStarting?.let { awaitCaptureStart(it, to, trailblazeDeviceId.instanceId) }
      val onSession = synchronized(sessionCreationLock) { membersOnSessionLocked(rosterContaining(trailblazeDeviceId), to) }
      if (onSession.isNotEmpty()) recordRoster(to, onSession)
    } finally {
      recordingGate?.let { gate ->
        synchronized(sessionCreationLock) { captureStarting.remove(to, gate) }
        gate.complete(Unit)
      }
    }
  }

  /**
   * Stops tracking [trailblazeDeviceId] on [sessionId], as long as another device still holds the
   * session — the last holder stays, so the session still has a device to be ended from. Call
   * under [sessionCreationLock].
   */
  private fun leaveSessionLocked(trailblazeDeviceId: TrailblazeDeviceId, sessionId: SessionId) {
    val sessions = _activeDeviceSessionsFlow.value
    if (sessions[trailblazeDeviceId] != sessionId) return
    if (sessions.count { it.value == sessionId } < 2) return
    _activeDeviceSessionsFlow.value = sessions - trailblazeDeviceId
    joinedThroughCast.remove(trailblazeDeviceId)
  }

  /** Drops every device tracked on [sessionId] and returns them. Call under [sessionCreationLock]. */
  private fun forgetSessionLocked(sessionId: SessionId): Set<TrailblazeDeviceId> {
    val (ended, kept) = _activeDeviceSessionsFlow.value.entries.partition { it.value == sessionId }
    _activeDeviceSessionsFlow.value = kept.associate { it.key to it.value }
    ended.forEach { joinedThroughCast.remove(it.key) }
    openedByCast -= sessionId
    return ended.mapTo(mutableSetOf()) { it.key }
  }

  /** Closes the drivers a device holds between runs. The browser is the device, so it stays. */
  private fun releaseDriversForDevice(trailblazeDeviceId: TrailblazeDeviceId) {
    closeAndRemoveMaestroDriverForDevice(trailblazeDeviceId)
    closeAndRemovePlaywrightNativeTestForDevice(trailblazeDeviceId)
    closeAndRemovePlaywrightElectronTestForDevice(trailblazeDeviceId)
  }

  /**
   * Hands the cast to the session's capture so each display is recorded. The whole roster, start
   * device included — the coordinator names the recording it already runs from the member whose
   * device matches and records the rest; a member already recording is left alone.
   */
  private fun recordRoster(sessionId: SessionId, members: List<BoundDeviceRosterMember>) {
    sessionCaptureCoordinator.bindDevices(
      sessionId = sessionId,
      devices = members.map { member ->
        val platform = member.trailblazeDeviceId.trailblazeDevicePlatform
        val targetId = member.targetId ?: getCurrentSelectedTargetApp()?.id
        SessionCaptureCoordinator.BoundDevice(
          name = member.name,
          deviceId = member.trailblazeDeviceId,
          appId = availableAppTargets.find { it.id == targetId }?.getPossibleAppIdsForPlatform(platform)?.firstOrNull(),
        )
      },
    )
  }

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
    // Only clear if this is the current session for the device. Every device on the session goes
    // with it: a named cast shares one session, and a companion left pointing at an ended id would
    // log its next tool call into a session that is already closed.
    synchronized(sessionCreationLock) {
      if (_activeDeviceSessionsFlow.value[trailblazeDeviceId] == endedSessionId) {
        forgetSessionLocked(endedSessionId)
      }
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

  // Store running test instances per device - allows forceful driver shutdown
  private val maestroDriverByDeviceMap: MutableMap<TrailblazeDeviceId, Driver> =
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
    var otherMembers: Set<TrailblazeDeviceId> = emptySet()
    val cancelledSessionId = synchronized(sessionCreationLock) {
      val sid = _activeDeviceSessionsFlow.value[trailblazeDeviceId]
      if (sid != null) {
        otherMembers = forgetSessionLocked(sid) - trailblazeDeviceId
        sessionTargetRegistry.clear(sid)
      }
      sid
    }
    // A named cast's other members leave the session too; their drivers go with it.
    otherMembers.forEach(::releaseDriversForDevice)
    // Best-effort stop of capture for the cancelled session — running outside the
    // sessionCreationLock so a slow ffmpeg finalize on stopAll can't deadlock a concurrent
    // device-management call (the mux drain is time-bounded, but seconds is enough to be
    // felt). Idempotent if endSessionForDevice already ran.
    // The caller-supplied id covers the mapping-already-cleared case (see kdoc).
    // Genuinely best-effort: a cancelled run is already reported as cancelled/failed, and
    // several callers invoke this from their own error paths, so a cleanup failure here is
    // logged rather than thrown (it must not mask the original failure or break the stop route).
    runCatching {
      finalizeHostSessionResources(
        setOfNotNull(cancelledSessionId, knownSessionId),
        sessionCaptureCoordinator::stopForSession,
      )
    }.onFailure {
      Console.log("Session cleanup after cancellation failed: ${it.message}")
    }
    return scopeCancelled || cancelledSessionId != null
  }

  /**
   * Releases the host resources of [replacedSessionId], the session [trailblazeDeviceId] was on
   * before [newSessionId] took the device over without ending it (an interactive CLI session
   * followed by a `run`). Nothing ends a replaced session, so its capture — memory sampling,
   * logcat, network capture — would otherwise keep polling the device for the life of the daemon.
   *
   * Resources only: no status is written and no driver is closed, since [newSessionId] is now using
   * the device. Skipped while the replaced session is still the active session of another device,
   * and while any run other than [callerRun] is executing on this device: runs can share a device,
   * and the session the pointer named may be a sibling run's, still mid-flight. That also covers a
   * sibling whose session exists but whose run has not reached the point of registering it. A
   * release skipped for a sibling is retried when the device's last run ends (see [endRun]).
   * Best-effort — a cleanup failure is logged, never thrown into the new session.
   */
  fun releaseReplacedSession(
    trailblazeDeviceId: TrailblazeDeviceId,
    replacedSessionId: SessionId?,
    newSessionId: SessionId,
    callerRun: RunInFlight? = null,
  ) {
    if (replacedSessionId == null || replacedSessionId == newSessionId) return
    val stillActiveElsewhere = _activeDeviceSessionsFlow.value.any { (deviceId, sessionId) ->
      deviceId != trailblazeDeviceId && sessionId == replacedSessionId
    }
    if (stillActiveElsewhere) return
    // A device that left it with a call still logging there (see pendingUnbinds) holds it until that call ends.
    val loggingThere = synchronized(sessionCreationLock) {
      deviceStillLoggingIntoLocked(replacedSessionId, except = trailblazeDeviceId)
        ?.also { pendingReleases.getOrPut(it, ::mutableSetOf) += replacedSessionId }
    }
    if (loggingThere != null) {
      Console.log(
        "Session $newSessionId replaced $replacedSessionId on ${trailblazeDeviceId.instanceId}, " +
          "but a call on ${loggingThere.instanceId} is still logging there; releasing it once that call ends.",
      )
      return
    }
    // Queued before the sibling check, so a sibling ending in between still finds it (see [endRun]).
    synchronized(sessionCreationLock) { pendingReleases.getOrPut(trailblazeDeviceId, ::mutableSetOf) += replacedSessionId }
    val siblingRunInFlight = runsInFlight.any { it.trailblazeDeviceId == trailblazeDeviceId && it !== callerRun }
    if (siblingRunInFlight) {
      Console.log(
        "Session $newSessionId replaced $replacedSessionId on ${trailblazeDeviceId.instanceId}, " +
          "but another run is executing there; releasing its capture once that run ends.",
      )
      return
    }
    // Whoever takes the queued release performs it, so a sibling ending meanwhile does not release it twice.
    val taken = synchronized(sessionCreationLock) { pendingReleases[trailblazeDeviceId]?.remove(replacedSessionId) == true }
    if (!taken) return
    Console.log(
      "Session $newSessionId replaced $replacedSessionId on ${trailblazeDeviceId.instanceId}; " +
        "releasing the replaced session's capture.",
    )
    releaseSessionResources(replacedSessionId)
  }

  /** Performs the releases [releaseReplacedSession] skipped for a sibling run, once [trailblazeDeviceId] has no run left. */
  private fun releasePendingAfterRuns(trailblazeDeviceId: TrailblazeDeviceId) {
    val due = synchronized(sessionCreationLock) {
      // A session a device has been put back on is live again, not replaced.
      pendingReleases.remove(trailblazeDeviceId).orEmpty()
        .filter { it !in _activeDeviceSessionsFlow.value.values }
        .filter { replaced ->
          // Still logged into from another device: that device's last call releases it instead.
          val holder = deviceStillLoggingIntoLocked(replaced, except = trailblazeDeviceId) ?: return@filter true
          pendingReleases.getOrPut(holder, ::mutableSetOf) += replaced
          false
        }
    }
    due.forEach { replaced ->
      Console.log("The last run on ${trailblazeDeviceId.instanceId} ended; releasing replaced session $replaced's capture.")
      releaseSessionResources(replaced)
    }
  }

  /** A device other than [except] whose calls still log into [sessionId] after leaving it (see [pendingUnbinds]). Call under [sessionCreationLock]. */
  private fun deviceStillLoggingIntoLocked(sessionId: SessionId, except: TrailblazeDeviceId): TrailblazeDeviceId? =
    pendingUnbinds.entries.firstOrNull { (device, left) -> device != except && left.any { it.first == sessionId } }?.key

  private fun releaseSessionResources(sessionId: SessionId) {
    runCatching {
      finalizeHostSessionResources(listOf(sessionId), sessionCaptureCoordinator::stopForSession)
    }.onFailure {
      Console.log("Releasing replaced session $sessionId failed: ${it.message}")
    }
  }

  /**
   * Hands a device back when the run that reserved it never opened a session.
   *
   * Compare-and-clear under [sessionCreationLock]: the reservation is dropped only while
   * [sessionId] is still the device's registered holder, so a newer run that has already claimed
   * the device is left alone. Doing the compare inside the lock is the point - a caller that reads
   * the mapping first and then cancels can be overtaken between the two, and would kill the run
   * that overtook it.
   *
   * Deliberately NOT [cancelSessionForDevice]: a run that never started has no driver and no
   * coroutine scope to tear down, and forcing those closed would disconnect a device the UI had
   * already connected for a run that is still perfectly healthy.
   *
   * @return true if this session's reservation was the one released.
   */
  fun releaseUnstartedSession(trailblazeDeviceId: TrailblazeDeviceId, sessionId: SessionId): Boolean {
    synchronized(sessionCreationLock) {
      if (_activeDeviceSessionsFlow.value[trailblazeDeviceId] != sessionId) return false
      forgetSessionLocked(sessionId)
      sessionTargetRegistry.clear(sessionId)
    }
    // Best-effort, outside the lock for the same reason cancelSessionForDevice finalizes outside
    // it: a slow capture teardown must not block concurrent device management.
    runCatching {
      finalizeHostSessionResources(listOf(sessionId), sessionCaptureCoordinator::stopForSession)
    }.onFailure {
      Console.log("Capture cleanup after releasing unstarted session $sessionId failed: ${it.message}")
    }
    return true
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

  /**
   * Registers the driver a run is executing with, closing whatever this device was holding before.
   *
   * The displaced driver always belongs to a run that is already over. Usually its own cleanup
   * closed it and there is nothing here to displace, but an MCP-referrer run deliberately skips
   * [cancelSessionForDevice] to keep its driver alive between tool calls, so its driver is still
   * registered when the next run arrives. Dropping that one from the map without closing it is what
   * would leave a hold on the shared iOS driver with nothing left pointing at it to release.
   */
  fun setActiveDriverForDevice(trailblazeDeviceId: TrailblazeDeviceId, maestroDriver: Driver) {
    val displaced = maestroDriverByDeviceMap.put(trailblazeDeviceId, maestroDriver)
    if (displaced == null || displaced === maestroDriver) return
    try {
      displaced.close()
    } catch (e: Exception) {
      Console.log("Error closing the driver replaced on device ${trailblazeDeviceId.instanceId} (continuing anyway): ${e.message}")
    }
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

  /**
   * Probes [trailblazeDeviceId]'s installed apps now, publishes
   * [installedAppIdsByDeviceFlow] / [appVersionInfoByDeviceFlow], and returns the installed app
   * IDs — or **null** if the probe failed (nothing is published in that case; see
   * [DeviceAppInventory]). Null is not an empty set: a caller that gates on installation must
   * not read a failed probe as "nothing is installed". Always a fresh probe (the caller may
   * have just installed an app); concurrent calls for the same device share one probe.
   *
   * [includeVersionInfo] also probes version info for target-relevant installed apps — on iOS
   * each version probe is its own `simctl listapps` run, so callers that only consume the
   * installed-ID set (the MCP bridge's `getInstalledAppIds`) pass `false` and skip that fan-out;
   * UI that renders version badges keeps the default. Skipping the fan-out never clears version
   * entries already known for still-installed apps.
   */
  suspend fun refreshAppInventory(
    trailblazeDeviceId: TrailblazeDeviceId,
    includeVersionInfo: Boolean = true,
  ): Set<String>? = appInventory.refresh(
    deviceId = trailblazeDeviceId,
    relevantAppIds = if (includeVersionInfo) relevantAppIdsForVersionInfo() else emptySet(),
  )

  /**
   * Fire-and-forget [refreshAppInventory] for each mobile device in [deviceIds] — the reactive
   * trigger for UI that renders from the inventory flows (Run Configuration dialog, debug tab).
   * Virtual devices (web, Compose desktop) have no installable apps and are skipped, and
   * duplicate ids collapse (two driver rows can share one [TrailblazeDeviceId]).
   *
   * Retries a failed probe once. The UI triggers this from an effect keyed on the device list,
   * so without a retry a single timeout would leave that device rendered as "app not
   * installed" until the user happened to hit Refresh — where discovery used to re-attempt the
   * probe on its next pass.
   */
  fun refreshAppInventoryAsync(deviceIds: Collection<TrailblazeDeviceId>) {
    deviceIds
      .toSet()
      .filter { !it.trailblazeDevicePlatform.usesVirtualDevice }
      .forEach { deviceId ->
        loadDevicesScope.launch {
          if (refreshAppInventory(deviceId) == null) {
            refreshAppInventory(deviceId)
          }
        }
      }
  }

  /**
   * Version info is probed only for apps that belong to available app targets — probing every
   * installed app would be very slow.
   */
  private fun relevantAppIdsForVersionInfo(): Set<String> = availableAppTargets.flatMap { target ->
    target.getPossibleAppIdsForPlatform(TrailblazeDevicePlatform.ANDROID).orEmpty() +
      target.getPossibleAppIdsForPlatform(TrailblazeDevicePlatform.IOS).orEmpty()
  }.toSet()

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
    /** How long a call joining a session waits for that session's recorders to start. */
    private const val CAPTURE_START_WAIT_SECONDS = 30L

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

    /**
     * Runs the whole-set reload against [holder] (the manager's live-target [MutableStateFlow]):
     * re-discovers via [provider] and swaps the result in, replacing the previous set rather than
     * merging with it. Backs [reloadAppTargets]; extracted for the same reason
     * [casAppendNewTarget] is — the manager's ~14 constructor dependencies make constructing one
     * in a test impractical.
     *
     * [lock] serializes the pass: [provider] mutates process-global tool overlays as well as
     * returning a set, so an overlapping pass must not interleave with this one.
     *
     * Returns the new set, or null when [provider] is absent or throws — and in both null cases
     * [holder] is left untouched, so a failed reload degrades to the previous workspace's targets
     * plus the drift nudge rather than to an empty picker.
     */
    internal suspend fun swapAppTargets(
      holder: MutableStateFlow<Set<TrailblazeHostAppTarget>>,
      provider: (() -> Set<TrailblazeHostAppTarget>)?,
      lock: Mutex,
    ): Set<TrailblazeHostAppTarget>? {
      if (provider == null) {
        Console.log(
          "[TrailblazeDeviceManager] App-target reload skipped: no fresh-discovery provider wired " +
            "(falling back to restart-required flow)",
        )
        return null
      }
      return lock.withLock {
        val fresh = try {
          provider()
        } catch (e: Exception) {
          Console.error(
            "[TrailblazeDeviceManager] App-target reload failed during discovery: " +
              "${e::class.simpleName}: ${e.message}\n${e.stackTraceToString()}",
          )
          return@withLock null
        }
        val previousIds = holder.getAndUpdate { fresh }.map { it.id }.toSet()
        val freshIds = fresh.map { it.id }.toSet()
        Console.log(
          "[TrailblazeDeviceManager] Reloaded app targets for the current workspace: " +
            "${fresh.size} available (added: ${(freshIds - previousIds).sorted()}, " +
            "removed: ${(previousIds - freshIds).sorted()})",
        )
        fresh
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

    // Default TTL for [discoveryCache]. Long enough that one CLI command's several sequential
    // discovery passes reuse a single enumeration, short enough that a device plugged/unplugged
    // between two user commands is picked up on the next command (human command cadence >> TTL).
    internal const val DEFAULT_DISCOVERY_CACHE_TTL_MS = 1_500L

    /**
     * Resolves the discovery-cache TTL from the environment (read once at construction, like the
     * other daemon knobs):
     *  - `TRAILBLAZE_DISABLE_DEVICE_DISCOVERY_CACHE=1|true` → `0` (cache disabled; every pass
     *    re-enumerates, restoring the pre-cache behavior as a one-line kill switch).
     *  - `TRAILBLAZE_DEVICE_DISCOVERY_CACHE_TTL_MS=<ms>` → that value (`0` also disables).
     *  - unset / malformed / negative → [DEFAULT_DISCOVERY_CACHE_TTL_MS].
     *
     * [env] is injectable so the parse/precedence logic is unit-testable without real env vars.
     */
    internal fun resolveDiscoveryCacheTtlMs(env: (String) -> String? = { System.getenv(it) }): Long {
      val disabled = env("TRAILBLAZE_DISABLE_DEVICE_DISCOVERY_CACHE")?.trim()?.lowercase()
      if (disabled == "1" || disabled == "true") return 0L
      val parsed = env("TRAILBLAZE_DEVICE_DISCOVERY_CACHE_TTL_MS")?.trim()?.toLongOrNull()
      return when {
        parsed == null -> DEFAULT_DISCOVERY_CACHE_TTL_MS
        parsed < 0L -> DEFAULT_DISCOVERY_CACHE_TTL_MS
        else -> parsed
      }
    }

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
     * Budget for the installed-app probe that scopes capture to one of a target's declared app ids.
     * Matches the inventory's own probe budget — it is the same `pm list packages` / `simctl
     * listapps` call — and bounds session startup, which waits on it.
     */
    private const val CAPTURE_APP_PROBE_TIMEOUT_SECONDS = 10L

    /**
     * Runs a blocking operation with a timeout. Returns null if it times out or fails.
     */
    private fun <T> runWithTimeout(timeoutSeconds: Long, deviceId: String, label: String, block: () -> T): T? =
      HostProbe.withTimeout(timeoutSeconds, deviceId, label, block = block)

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
