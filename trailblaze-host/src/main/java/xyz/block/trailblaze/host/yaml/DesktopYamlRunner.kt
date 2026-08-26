package xyz.block.trailblaze.host.yaml

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import xyz.block.trailblaze.playwright.PlaywrightPageManager
import xyz.block.trailblaze.cli.CliRunDriverResolution
import xyz.block.trailblaze.cli.CliRunDriverResolver
import xyz.block.trailblaze.cli.DeviceClassifierResolver
import xyz.block.trailblaze.devices.TrailblazeConnectedDeviceSummary
import xyz.block.trailblaze.devices.TrailblazeDeviceClassifier
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.exception.TrailblazeSessionCancelledException
import xyz.block.trailblaze.host.TrailblazeHostYamlRunner
import xyz.block.trailblaze.host.animations.SessionAnimationDisabler
import xyz.block.trailblaze.host.networkcapture.AndroidNetworkCaptureRegistry
import xyz.block.trailblaze.host.capture.finalizeHostSessionResources
import xyz.block.trailblaze.host.networkcapture.CompositeAndroidNetworkCaptureActivator
import xyz.block.trailblaze.host.ios.MobileDeviceUtils
import xyz.block.trailblaze.http.DynamicLlmClient
import xyz.block.trailblaze.llm.RunYamlRequest
import xyz.block.trailblaze.llm.RunYamlResponse
import xyz.block.trailblaze.llm.TrailblazeLlmModel
import xyz.block.trailblaze.llm.TrailblazeReferrer
import xyz.block.trailblaze.logs.client.TrailblazeLog
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.logs.model.SessionInfo
import xyz.block.trailblaze.logs.model.SessionStatus
import xyz.block.trailblaze.logs.model.getSessionStatus
import xyz.block.trailblaze.mcp.AgentImplementation
import xyz.block.trailblaze.mcp.android.ondevice.rpc.OnDeviceRpcClient
import xyz.block.trailblaze.mcp.android.ondevice.rpc.RpcResult
import xyz.block.trailblaze.model.DesktopAppRunYamlParams
import xyz.block.trailblaze.model.DeviceConnectionStatus
import xyz.block.trailblaze.model.TrailExecutionResult
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult
import xyz.block.trailblaze.model.TrailblazeHostAppTarget
import xyz.block.trailblaze.model.TrailblazeOnDeviceInstrumentationTarget
import xyz.block.trailblaze.ui.TrailblazeAnalytics
import xyz.block.trailblaze.ui.TrailblazeDeviceManager
import xyz.block.trailblaze.util.AccessibilityServiceSetupUtils
import xyz.block.trailblaze.util.HostAndroidDeviceConnectUtils
import xyz.block.trailblaze.util.Console
import xyz.block.trailblaze.util.UiAutomationHandleErrors
import xyz.block.trailblaze.devices.TrailblazeClassifierLineage
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.exception.TrailblazeException
import xyz.block.trailblaze.recordings.TrailRecordings
import xyz.block.trailblaze.yaml.TrailblazeYaml
import xyz.block.trailblaze.yaml.createTrailblazeYaml
import xyz.block.trailblaze.yaml.unified.UnknownDriverException
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

class DesktopYamlRunner(
  private val trailblazeDeviceManager: TrailblazeDeviceManager,
  private val trailblazeAnalytics: TrailblazeAnalytics,
  private val trailblazeHostAppTargetProvider: () -> TrailblazeHostAppTarget,
  private val dynamicLlmClientProvider: (TrailblazeLlmModel) -> DynamicLlmClient,
) {
  companion object {
    /**
     * How long a WEB companion's browser gets to come up. Generous because a first run on a
     * fresh machine downloads and installs Chromium before the browser can launch.
     */
    private const val WEB_COMPANION_LAUNCH_TIMEOUT_MS = 180_000L

    // Force early class loading of nested DeviceConnectionStatus classes
    // This prevents ClassNotFoundException in catch blocks which reference these types
    @Suppress("unused")
    private val connectionFailureClass = DeviceConnectionStatus.DeviceConnectionError.ConnectionFailure::class
    @Suppress("unused")
    private val startingConnectionClass = DeviceConnectionStatus.WithTargetDevice.StartingConnection::class
    @Suppress("unused")
    private val instrumentationRunningClass = DeviceConnectionStatus.WithTargetDevice.TrailblazeInstrumentationRunning::class

    /**
     * Pure decision: did [status] end in the on-device UiAutomation wedge that only a server
     * relaunch can clear? Kept pure (no device, no RPC) so the relaunch decision is unit-testable.
     *
     * Matches strictly `Ended.Failed` carrying the non-recoverable stale-handle signature. Any
     * other failure (assertion, element-not-found) and any non-failed terminal status return false:
     * a relaunch only gives the NEXT trail a clean server, it never re-runs this trail, so an
     * over-broad match would waste a reinstall+`am instrument` per failing trail and could mask a
     * real on-device crash without ever turning a genuine failure green.
     *
     * This terminal-status check alone is NOT a reliable detector: a mid-trail wedge is usually
     * absorbed by the agent loop (tool errors feed the LLM / self-heal), so the session keeps
     * running and its terminal message is whatever the LAST step failed with — or the session
     * ends as `MaxCallsLimitReached` with no message at all. Callers should prefer the
     * log-scanning overload below, which also inspects per-tool failures.
     */
    internal fun shouldRelaunchOnDeviceServer(status: SessionStatus?): Boolean =
      status is SessionStatus.Ended.Failed &&
        UiAutomationHandleErrors.isNonRecoverableStaleHandleSignature(status.exceptionMessage)

    /**
     * Log-scanning variant: true when the session's terminal status matches (above), OR any
     * failed [TrailblazeLog.TrailblazeToolLog] in [logs] carries the non-recoverable wedge
     * signature. The per-tool scan is what makes detection reliable — the wedge message is only
     * ever produced after the on-device in-process reconnect retry has already failed, so a
     * single matching tool log proves the server was in the kill-and-relaunch-only state,
     * regardless of how the session later ended (a different last-step failure, an LLM
     * call-budget exhaustion, even a nominal success if the remaining steps never touched the
     * device). Still keyed to terminal UiAutomation recovery failures, so the strictness
     * rationale of the status overload — never arm on ordinary failures — is preserved.
     */
    internal fun shouldRelaunchOnDeviceServer(logs: List<TrailblazeLog>): Boolean {
      if (shouldRelaunchOnDeviceServer(logs.getSessionStatus())) return true
      return logs.any { log ->
        log is TrailblazeLog.TrailblazeToolLog &&
          !log.successful &&
          UiAutomationHandleErrors.isNonRecoverableStaleHandleSignature(log.exceptionMessage)
      }
    }

    /**
     * The driver [trailYaml] pins for the device described by [deviceClassifiers]. Covers both
     * formats: a v1 `driver:` scalar (classifier-independent) and a unified per-classifier
     * `devices:` map (closest-wins). Three outcomes:
     * - [CliRunDriverResolution.Resolved] with a driver type — the pin names a known driver.
     * - [CliRunDriverResolution.Resolved] with null — no pin reachable from this device's
     *   classifier chain, or the YAML fails to parse (the trail decode inside the run surfaces
     *   the real error).
     * - [CliRunDriverResolution.Unrecognized] — the pin names NO known driver. Callers must fail
     *   loud (never silently fall back to the default driver): a typo'd pin used to keep a CI
     *   step green while replaying on a different driver entirely.
     *
     * This is the runner's own read of the pin because upstream request builders (the daemon's
     * `/cli/run` handler, the desktop Run path) extract trail config without a device and so
     * send `RunYamlRequest.driverType = null` for every unified trail; the connected device is
     * only concrete here.
     */
    internal fun trailPinnedDriverResolution(
      trailYaml: String,
      deviceClassifiers: List<TrailblazeDeviceClassifier>,
    ): CliRunDriverResolution {
      val pinnedDriver = try {
        createTrailblazeYaml().extractTrailConfig(trailYaml, deviceClassifiers)?.driver
      } catch (e: Exception) {
        // A typo'd devices: pin throws UnknownDriverException inside the trail decode (kaml wraps
        // it, so walk the cause chain). The exception carries the WHOLE devices map — every entry
        // that decoded cleanly plus every bad one — so the same closest-wins walk the adapter
        // uses can decide what this device sees: its winning entry is valid → that pin; its
        // winning entry is the typo → Unrecognized (never silently the default driver); nothing
        // reachable → no pin, another platform's typo stays that platform's problem.
        val unknownDriver = generateSequence<Throwable>(e) { it.cause }
          .filterIsInstance<UnknownDriverException>()
          .firstOrNull()
          ?: return CliRunDriverResolution.Resolved(null)
        if (unknownDriver.classifier == null) {
          // No entry context — fail loud rather than guess it is someone else's pin.
          return CliRunDriverResolver.resolve(unknownDriver.driverName)
        }
        val winner = TrailblazeClassifierLineage.resolutionChain(deviceClassifiers)
          .map { it.classifier }
          .firstOrNull { it in unknownDriver.decodedDevices || it in unknownDriver.unknownDrivers }
          ?: return CliRunDriverResolution.Resolved(null)
        unknownDriver.decodedDevices[winner]?.let {
          return CliRunDriverResolution.Resolved(it.driver)
        }
        return CliRunDriverResolver.resolve(unknownDriver.unknownDrivers.getValue(winner))
      }
      return CliRunDriverResolver.resolve(pinnedDriver)
    }

    /**
     * True when [sessionInfo]'s session-start record names [deviceInstanceId] — the cancellation
     * fallback's "does this new session belong to this device?" probe.
     *
     * Decides on PARSED log content, never on filenames: local CLI runs, farm pulls, and the CI
     * log reshaper each name log files differently, and the session-start advisory drain or a
     * mid-session daemon restart shuffles which log holds the `001_` slot. Callers pass
     * [LogsRepo.getSessionInfoSummary], which derives these fields from the session's Started
     * status — status logs only, so this stays off the full-session parse that
     * [LogsRepo.saveLogToDisk] documents as having exhausted a trail-driver heap at session end,
     * which is exactly when this runs.
     */
    internal fun sessionBelongsToDevice(sessionInfo: SessionInfo?, deviceInstanceId: String): Boolean {
      if (sessionInfo == null) return false
      return sessionInfo.trailblazeDeviceId?.instanceId == deviceInstanceId ||
        sessionInfo.trailblazeDeviceInfo?.trailblazeDeviceId?.instanceId == deviceInstanceId
    }
  }

  /**
   * Devices whose shared on-device server is known-wedged in the non-recoverable UiAutomation
   * state (see [shouldRelaunchOnDeviceServer]). Armed per device by [armWedgedDevice]; consumed
   * by [connectAndEnsureReady] to force-restart THAT device's server before its next trail, then
   * cleared. This runner instance is the daemon-scoped singleton every trail in a CI job routes
   * through, so an entry survives from the wedged trail to its successor — and keying by device
   * id keeps one device's wedge from misrouting on a multi-device daemon (the desktop app):
   * without it, whichever device connected next consumed the arm, giving a healthy device a
   * needless force-restart while the wedged one stayed poisoned.
   */
  private val wedgedDeviceIds: MutableSet<TrailblazeDeviceId> = ConcurrentHashMap.newKeySet()

  /**
   * Marks [trailblazeDeviceId]'s shared on-device server as wedged (see [wedgedDeviceIds]).
   * `Console.info` (not `.log`) so the arm survives the CLI's default quiet mode: for
   * breaker-armed wedges (typed `nonRecoverableWedge` field, RPC string match, GetScreenState
   * circuit breaker) this line and the consumption announcement in [connectAndEnsureReady] are
   * the only operator-visible attribution for the force-restart the next trail performs.
   */
  private fun armWedgedDevice(trailblazeDeviceId: TrailblazeDeviceId) {
    wedgedDeviceIds += trailblazeDeviceId
    Console.info(
      "[DesktopYamlRunner] Non-recoverable UiAutomation wedge armed for " +
        "${trailblazeDeviceId.instanceId} — its on-device server will be force-restarted " +
        "before its next trail.",
    )
  }

  /**
   * Shortens device description by removing UUID identifiers.
   * Example: "iPhone 16 Pro - iOS 18.4 - 55B5483E-EE63-4605-91DE-B061F19B9D1E" -> "iPhone 16 Pro - iOS 18.4"
   */
  private fun shortenDeviceDescription(description: String): String {
    // Match and remove UUID pattern (8-4-4-4-12 hex digits)
    val uuidPattern =
      Regex(" - [0-9A-Fa-f]{8}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{12}$")
    return description.replace(uuidPattern, "")
  }

  /**
   * Executes a YAML test on the specified device, automatically choosing between
   * on-device instrumentation or host-based execution.
   *
   * This is a non-composable suspend function that can be called directly if you
   * don't need the composable wrapper.
   */
  fun runYaml(desktopAppRunYamlParams: DesktopAppRunYamlParams) {
    val targetTestApp = desktopAppRunYamlParams.targetTestApp
    val trailblazeDeviceId = desktopAppRunYamlParams.runYamlRequest.trailblazeDeviceId
    val forceStopTargetApp = desktopAppRunYamlParams.forceStopTargetApp
    val runYamlRequest = desktopAppRunYamlParams.runYamlRequest
    val onProgressMessage = desktopAppRunYamlParams.onProgressMessage
    val onConnectionStatus = desktopAppRunYamlParams.onConnectionStatus
    val additionalInstrumentationArgs = desktopAppRunYamlParams.additionalInstrumentationArgs
    val onComplete = desktopAppRunYamlParams.onComplete

    // MCP and CLI requests reuse the existing scope. The "create a new scope (which cancels
    // any existing one)" pattern is a UI-only ergonomic — when the user clicks Run while a
    // prior trail is mid-flight in the desktop app, replace it. For background flows that
    // come in concurrently (the benchmark fan-out runs three CLI invocations in parallel
    // against the same daemon, all sharing one device), creating-and-cancelling means each
    // new arrival cancels the still-running predecessor. A reproduction surfaced this: 2 of 3
    // trails passed and the 3rd reported "FAILED: Cancelled" with no Initializing log line.
    // Coroutines launched into a shared scope are independent — `scope.launch { … }` does
    // not block other launches in the same scope, so reuse is safe for parallel runs.
    val sharedScopeReferrers = setOf(TrailblazeReferrer.MCP.id, "cli")
    val coroutineScope = if (runYamlRequest.referrer.id in sharedScopeReferrers) {
      trailblazeDeviceManager.getOrCreateCoroutineScopeForDevice(trailblazeDeviceId)
    } else {
      trailblazeDeviceManager.createNewCoroutineScopeForDevice(trailblazeDeviceId)
    }

    coroutineScope.launch {
      Console.log("🚀 COROUTINE STARTED for device: ${trailblazeDeviceId.instanceId}")
      
      // Track the execution result to report in finally block
      var executionResult: TrailExecutionResult = TrailExecutionResult.Success()

      // The last successful tool's result from a host/Maestro run (null otherwise). Folded into
      // the terminal TrailExecutionResult.Success below so the `trailblaze tool <read-tool>` HOST
      // path can surface the tool's real return value instead of a generic acknowledgement.
      var lastToolResult: TrailblazeToolResult.Success? = null

      // Try filtered first (correct driver selection for Android which has 3 driver variants
      // sharing the same device ID). Fall back to unfiltered for Compose/Playwright which are
      // only visible when testingEnvironment=WEB but should always be reachable via CLI.
      val connectedTrailblazeDevice = trailblazeDeviceManager.getDeviceState(trailblazeDeviceId)?.device
        ?: trailblazeDeviceManager.loadDevicesSuspend(applyDriverFilter = true).firstOrNull { it.trailblazeDeviceId == trailblazeDeviceId }
        ?: trailblazeDeviceManager.loadDevicesSuspend(applyDriverFilter = false).firstOrNull { it.trailblazeDeviceId == trailblazeDeviceId }

      if (connectedTrailblazeDevice == null) {
        onProgressMessage("Device with ID $trailblazeDeviceId not found")
        Console.log("❌ COROUTINE ENDING (device not found) for device: ${trailblazeDeviceId.instanceId}")
        executionResult = TrailExecutionResult.Failed("Device not found")
        onComplete?.invoke(executionResult)
        return@launch
      }

      // Wrap progress message callback to add device prefix
      val shortenedDescription = shortenDeviceDescription(trailblazeDeviceId.instanceId)
      val devicePrefix = "[$shortenedDescription]"
      val prefixedProgressMessage: (String) -> Unit = { message ->
        onProgressMessage("$devicePrefix $message")
      }

      if (forceStopTargetApp) {
        // The app to stop is the one this device will actually run. On a multi-device trail whose
        // START device overrides `target:`, that is the override, not the session default —
        // stopping the session default would leave the app under test running and kill an
        // unrelated one, defeating the whole point of the clean-start option.
        //
        // Lookup failures are ignored here: `resolveMemberTargets` runs the same lookup a moment
        // later and fails the run loud with a message about the trail's `target:`.
        val startDeviceTarget = MultiDeviceConfigurationResolver
          .startDeviceTargetId(runYamlRequest.yaml)
          ?.let { desktopAppRunYamlParams.findTargetById?.invoke(it) }
          ?: targetTestApp
        // Convert the YAML-ordered List to a Set for ensureAppsAreForceStopped, which takes
        // membership-style Set<String>.
        val possibleAppIds = startDeviceTarget
          ?.getPossibleAppIdsForPlatform(trailblazeDeviceId.trailblazeDevicePlatform)
          ?.toSet()
          ?: emptySet()
        MobileDeviceUtils.ensureAppsAreForceStopped(possibleAppIds, trailblazeDeviceId)
      }

      // Resolve driver type: request (CLI --driver / trail config) > the trail's own driver pin
      // resolved against THIS device > app setting > connected device default. The trail-pin rung
      // matches the CLI in-process precedence (trail config over app setting) — see
      // [trailPinnedDriverResolution] for why unified pins can only resolve here. A pin naming an
      // unknown driver fails the run loud instead of falling through to the default driver.
      val appConfig = trailblazeDeviceManager.settingsRepo.serverStateFlow.value.appConfig
      val appSettingDriverType = appConfig.selectedTrailblazeDriverTypes[
        trailblazeDeviceId.trailblazeDevicePlatform
      ]
      val trailblazeDriverType = runYamlRequest.driverType ?: run {
        val pinResolution = trailPinnedDriverResolution(
          trailYaml = runYamlRequest.yaml,
          deviceClassifiers = DeviceClassifierResolver.classifiersFor(
            platform = connectedTrailblazeDevice.platform,
            instanceId = connectedTrailblazeDevice.instanceId,
          ),
        )
        when (pinResolution) {
          is CliRunDriverResolution.Unrecognized -> {
            prefixedProgressMessage("Trail driver pin rejected: ${pinResolution.message}")
            Console.log("❌ COROUTINE ENDING (unrecognized trail driver pin) for device: ${trailblazeDeviceId.instanceId}")
            executionResult = TrailExecutionResult.Failed(pinResolution.message, misuse = true)
            onComplete?.invoke(executionResult)
            return@launch
          }
          is CliRunDriverResolution.Resolved -> pinResolution.driverType
        }
      }
        ?: appSettingDriverType
        ?: connectedTrailblazeDevice.trailblazeDriverType

      // The host runner selects its driver from `RunOnHostParams.trailblazeDriverType`, which
      // derives from the device summary — so a host-side pin (e.g. a unified trail pinning iOS
      // Axe over the simulator's default IOS_HOST) is only honored if the device carries the
      // resolved driver. Tag it here; the on-device branches propagate the same value via
      // `runYamlRequest.copy(driverType = …)`. The swap is same-platform (resolution is
      // platform-scoped), so instanceId / platform / deviceId are unchanged.
      val hostRunDevice = connectedTrailblazeDevice.copy(trailblazeDriverType = trailblazeDriverType)

      // Multi-device configurations are wired into exactly one dispatch branch below: the host
      // agent driving Android on-device drivers over RPC. Every other branch (V3, the on-device
      // agent, iOS/web/Compose host runs) has no companion connect, no device bindings, and no
      // per-device routing — it would run a multi-device trail against the launch device alone and
      // report success, with the configuration-keyed steps quietly falling through to the LLM.
      // Reject up front, naming the toggle that would make this run dispatchable.
      val multiDeviceDispatchSupported = trailblazeDriverType in TrailblazeDriverType.ANDROID_ON_DEVICE_DRIVER_TYPES &&
        runYamlRequest.agentImplementation != AgentImplementation.MULTI_AGENT_V3 &&
        runYamlRequest.config.preferHostAgent
      if (!multiDeviceDispatchSupported) {
        val declaredConfigurations = try {
          MultiDeviceConfigurationResolver.declaredConfigurationNames(runYamlRequest.yaml)
        } catch (e: TrailblazeException) {
          prefixedProgressMessage(e.message ?: "Failed to read this trail's device configuration")
          executionResult = TrailExecutionResult.Failed(
            e.message ?: "Failed to read this trail's device configuration",
            misuse = true,
          )
          onComplete?.invoke(executionResult)
          return@launch
        }
        if (declaredConfigurations.isNotEmpty()) {
          val message = "This trail declares the multi-device configuration(s) " +
            "$declaredConfigurations, which only run on the host agent over the Android " +
            "on-device RPC path. This run resolved driver $trailblazeDriverType, agent " +
            "${runYamlRequest.agentImplementation}, preferHostAgent=" +
            "${runYamlRequest.config.preferHostAgent} — running it here would silently use the " +
            "launch device only. Run it on an Android device with an on-device driver and " +
            "`preferHostAgent` enabled."
          prefixedProgressMessage(message)
          Console.log("❌ COROUTINE ENDING (multi-device trail on an unsupported dispatch path)")
          executionResult = TrailExecutionResult.Failed(message, misuse = true)
          onComplete?.invoke(executionResult)
          return@launch
        }
      }

      // Per-session video / sprite / logcat capture used to be started here against a
      // temp dir and moved into the session log dir in the finally block. That worked
      // for the CLI/daemon path but bypassed every MCP-driven session — the `step`,
      // `ask`, `verify`, and individual-tool entry points create sessions through
      // `TrailblazeDeviceManager.getOrCreateSessionResolution` and never go through
      // this runner. Capture is now owned by [SessionCaptureCoordinator], which both
      // paths route through (CLI's `onSessionStarted` callback below starts it; MCP
      // starts it at session-resolution time). The coordinator writes artifacts
      // directly into the session log dir — no temp-dir + move dance.
      // ALL app ids the target may run under on this platform — capture-peer identity checks
      // must accept any of them (which declared flavor is installed varies by lane, and it is
      // not always the first-declared one). The single-app-id consumers below keep the first
      // entry as before.
      val appIdsForCapture = targetTestApp
        ?.getPossibleAppIdsForPlatform(trailblazeDeviceId.trailblazeDevicePlatform)
        .orEmpty()
      val appIdForCapture = appIdsForCapture.firstOrNull()
      // Resolve per-run capture toggles in the same order the pre-coordinator flow did:
      // request-level overrides (CLI `--no-capture-video` / `--capture-logcat`) > daemon
      // appConfig toggles > built-in defaults. Passed to `coordinator.startForSession`
      // below so the user-visible CLI flag actually takes effect — without this, every
      // CLI run would record video even when the user opted out.
      val captureOptionsForRun = xyz.block.trailblaze.capture.CaptureOptions.hostCaptureOptions(
        captureVideo = desktopAppRunYamlParams.captureVideo ?: true,
        captureLogcat = desktopAppRunYamlParams.captureLogcat ?: appConfig.captureLogcat,
        captureIosLogs = desktopAppRunYamlParams.captureIosLogs ?: appConfig.captureIosLogs,
      )

      var sessionId: SessionId? = null
      // Every device a multi-device configuration bound, populated by the multi-device branch
      // BEFORE the runner starts the session so `captureSessionStarted` can arm one capture bridge
      // per device instead of only the launch device's. Empty for a single-device run.
      var captureDeviceBindings: List<CaptureDeviceBinding> = emptyList()
      // Snapshot existing session IDs so we can find newly created ones on cancellation
      val preExistingSessionIds = trailblazeDeviceManager.logsRepo.getSessionIds().toSet()

      // Advisories raised while this run was being assembled (see
      // [DesktopAppRunYamlParams.sessionStartAdvisories]) attach to the session log the moment
      // the session id is known. Declared outside the try so the finally-block backstop below
      // can drain them on paths that throw before their branch returns a session id. Skipped
      // under --no-logging, which writes no session files.
      val pendingAdvisories = PendingSessionStartAdvisories(
        advisories = if (desktopAppRunYamlParams.noLogging) {
          emptyList()
        } else {
          desktopAppRunYamlParams.sessionStartAdvisories
        },
        saveLog = trailblazeDeviceManager.logsRepo::saveLogToDisk,
      )

      try {
        trailblazeAnalytics.runTest(trailblazeDriverType, desktopAppRunYamlParams)
        prefixedProgressMessage(
          "Starting ${trailblazeDeviceId.trailblazeDevicePlatform.displayName} test on device ${trailblazeDeviceId.instanceId} with driver type $trailblazeDriverType",
        )

        val trailblazeHostAppTarget = trailblazeHostAppTargetProvider()

        // Capture-aware onSessionStarted callback shared across the three Android dispatch
        // branches (V3 / preferHostAgent / on-device YAML). Each branch knows the resolved
        // session id at a slightly different point in its flow; this lambda lets all three
        // converge on the same activator wiring without duplicating the `runCatching` /
        // `maybeStartAndroidNetworkCapture` plumbing.
        val captureSessionStarted: (SessionId) -> Unit = { sid ->
          pendingAdvisories.logTo(sid)
          // Idempotent — MCP path may have started this already via
          // getOrCreateSessionResolution with appConfig-derived options. The
          // coordinator's reserve-then-start makes the second call a no-op so we
          // don't double-spawn screenrecord. For runs that originated from the CLI,
          // `captureOptionsForRun` honors per-flag overrides (--no-capture-video,
          // --capture-logcat, --capture-ios-logs).
          trailblazeDeviceManager.sessionCaptureCoordinator.startForSession(
            sessionId = sid,
            deviceId = trailblazeDeviceId.instanceId,
            platform = trailblazeDeviceId.trailblazeDevicePlatform,
            options = captureOptionsForRun,
            appId = appIdForCapture,
          )
          // Experimental opt-in (gated internally, idempotent — the MCP path may have already
          // fired it at session-resolution time).
          SessionAnimationDisabler.startForSession(sid, trailblazeDeviceId)
          maybeStartAndroidNetworkCapture(
            runYamlRequest = runYamlRequest,
            deviceId = trailblazeDeviceId,
            sessionIdOverride = sid,
            targetAppIds = appIdsForCapture,
            onProgressMessage = prefixedProgressMessage,
            deviceBindings = captureDeviceBindings,
          )
        }

        sessionId = when {
          // Opt-in Koog strategy-graph agent. Top priority so it short-circuits the driver-based
          // routing below for every platform/driver when the run explicitly asks for it.
          //
          // The agent now drives the device IN-PROCESS for the WEB (Playwright-native) path:
          // [TrailblazeHostYamlRunner.runHostYaml] → `runPlaywrightNativeYaml` →
          // `BasePlaywrightNativeTest.runTrailblazeYamlSuspend` branches on
          // `runYamlRequest.agentImplementation` and, for KOOG_STRATEGY_GRAPH, runs prompt steps
          // through [KoogStrategyGraphAgent.createInProcess] against a Trailblaze-owned
          // `ToolRegistry`. Tool calls flow through the same `PlaywrightTrailblazeAgent` executor
          // (and therefore the same logging/session) the legacy runner uses — no MCP
          // self-connection, so none of the re-entrancy deadlock that pattern caused.
          //
          // This in-process host seam covers web (Playwright), Revyl (Android + iOS), Electron, and
          // the local-device Maestro iOS path via `runHostYaml`. Android ON-DEVICE drivers
          // (instrumentation/accessibility) are EXCLUDED from this branch on purpose: those need
          // the device attached via the on-device RPC server, which `runHostYaml`'s Maestro path
          // can't provide (it can't see the emulator the on-device setup registers). They instead
          // run the Koog agent ON THE DEVICE by default — the Koog agent now ships in
          // trailblaze-common, so the on-device RunYamlRequestHandler runs it in-process (see the
          // on-device branch below). `preferHostAgent` opts back into running the loop host-side and
          // dispatching each tool over RPC (`runHostTrailblazeRunnerWithOnDeviceRpc`).
          // Compose (the RPC driver) also rides this host seam now that ComposeRpcTrailblazeAgent is
          // a BaseTrailblazeAgent — `runComposeYaml` builds a KoogTestAgentRunner when KOOG is
          // selected. The earlier MCP-self-connection approach (and the deadlock it hit) is the
          // reason the in-process executor route exists; see [KoogStrategyGraphAgent].
          runYamlRequest.agentImplementation == AgentImplementation.KOOG_STRATEGY_GRAPH &&
            trailblazeDriverType !in TrailblazeDriverType.ANDROID_ON_DEVICE_DRIVER_TYPES -> {
            prefixedProgressMessage(
              "KOOG_STRATEGY_GRAPH selected — running the in-process Koog strategy-graph agent " +
                "(web, Revyl, Electron, and local Maestro iOS paths via runHostYaml).",
            )

            val hostResult = TrailblazeHostYamlRunner.runHostYaml(
              dynamicLlmClient = dynamicLlmClientProvider(runYamlRequest.trailblazeLlmModel),
              runOnHostParams = RunOnHostParams(
                runYamlRequest = runYamlRequest,
                device = hostRunDevice,
                onProgressMessage = prefixedProgressMessage,
                forceStopTargetApp = forceStopTargetApp,
                targetTestApp = targetTestApp,
                additionalInstrumentationArgs = { emptyMap() },
                // Start capture (iOS log stream / Android logcat) the moment the Maestro
                // session is created, BEFORE the synchronous trail run — without this the
                // post-run activation below starts too late to record anything. Idempotent
                // with the post-run call, so non-Maestro host paths are unaffected.
                onSessionStarted = captureSessionStarted,
                composeRpcPort = desktopAppRunYamlParams.composeRpcPort,
                referrer = desktopAppRunYamlParams.runYamlRequest.referrer,
                noLogging = desktopAppRunYamlParams.noLogging,
                // Thread the resolved video toggle to the web / Electron rules, which
                // self-instrument capture (the coordinator skips WEB) and would otherwise
                // ignore `--no-capture-video`.
                captureVideo = captureOptionsForRun.captureVideo,
              ),
              deviceManager = trailblazeDeviceManager,
            )
            lastToolResult = hostResult.lastToolResult

            // Mirror the neighboring branches' session/connection bookkeeping: fire the
            // capture activator for the resolved session and report instrumentation-running.
            hostResult.sessionId?.let { captureSessionStarted(it) }
            onConnectionStatus(
              DeviceConnectionStatus.WithTargetDevice.TrailblazeInstrumentationRunning(
                trailblazeDeviceId = connectedTrailblazeDevice.trailblazeDeviceId,
              ),
            )
            hostResult.sessionId
          }

          // V3 on host with accessibility driver: run planner/analyzer on the host JVM,
          // send individual tool calls to the on-device accessibility server via RPC.
          trailblazeDriverType == TrailblazeDriverType.ANDROID_ONDEVICE_ACCESSIBILITY &&
            runYamlRequest.agentImplementation == AgentImplementation.MULTI_AGENT_V3 -> {
            val trailblazeOnDeviceInstrumentationTarget = targetTestApp?.getTrailblazeOnDeviceInstrumentationTarget()
              ?: trailblazeHostAppTarget.getTrailblazeOnDeviceInstrumentationTarget()

            val onDeviceRpc = OnDeviceRpcClient(
              trailblazeDeviceId = trailblazeDeviceId,
              sendProgressMessage = prefixedProgressMessage,
              // Arm at the single chokepoint every synchronous on-device RPC flows through, so a
              // wedge surfacing on ANY path (including the `launchApp` pre-action, which the
              // session-status detection can't see) force-restarts the shared server next trail.
              onNonRecoverableWedge = { armWedgedDevice(trailblazeDeviceId) },
            )

            runV3WithAccessibilityOnHost(
              onDeviceRpc = onDeviceRpc,
              dynamicLlmClient = dynamicLlmClientProvider(runYamlRequest.trailblazeLlmModel),
              runYamlRequest = runYamlRequest.copy(driverType = trailblazeDriverType),
              connectedTrailblazeDevice = connectedTrailblazeDevice,
              trailblazeOnDeviceInstrumentationTarget = trailblazeOnDeviceInstrumentationTarget,
              onProgressMessage = prefixedProgressMessage,
              onConnectionStatus = onConnectionStatus,
              additionalInstrumentationArgs = additionalInstrumentationArgs,
              targetTestApp = targetTestApp,
              onSessionStarted = captureSessionStarted,
            )
          }

          // Host agent with on-device driver (accessibility or instrumentation): run the
          // agent loop on the host JVM, send individual tool calls to the device via RPC.
          // The device executes each tool using whichever driver is selected.
          //
          // This is the opt-in `preferHostAgent` path. KOOG_STRATEGY_GRAPH no longer forces it:
          // the Koog agent now ships in trailblaze-common and runs ON-DEVICE by default (next
          // branch). Set `preferHostAgent` to keep the Koog reasoning loop (and its growing
          // history) on the host instead of the device — useful when device memory pressure is a
          // concern; runHostTrailblazeRunnerWithOnDeviceRpc still runs the Koog graph host-side
          // when this path is taken.
          trailblazeDriverType in TrailblazeDriverType.ANDROID_ON_DEVICE_DRIVER_TYPES &&
            runYamlRequest.agentImplementation != AgentImplementation.MULTI_AGENT_V3 &&
            runYamlRequest.config.preferHostAgent -> {
            val trailblazeOnDeviceInstrumentationTarget = targetTestApp?.getTrailblazeOnDeviceInstrumentationTarget()
              ?: trailblazeHostAppTarget.getTrailblazeOnDeviceInstrumentationTarget()

            val onDeviceRpc = OnDeviceRpcClient(
              trailblazeDeviceId = trailblazeDeviceId,
              sendProgressMessage = prefixedProgressMessage,
              // Arm at the single chokepoint every synchronous on-device RPC flows through, so a
              // wedge surfacing on ANY path (including the `launchApp` pre-action, which the
              // session-status detection can't see) force-restarts the shared server next trail.
              onNonRecoverableWedge = { armWedgedDevice(trailblazeDeviceId) },
            )

            runHostAgentWithOnDeviceRpc(
              onDeviceRpc = onDeviceRpc,
              dynamicLlmClient = dynamicLlmClientProvider(runYamlRequest.trailblazeLlmModel),
              runYamlRequest = runYamlRequest.copy(driverType = trailblazeDriverType),
              connectedTrailblazeDevice = connectedTrailblazeDevice,
              trailblazeOnDeviceInstrumentationTarget = trailblazeOnDeviceInstrumentationTarget,
              onProgressMessage = prefixedProgressMessage,
              onConnectionStatus = onConnectionStatus,
              additionalInstrumentationArgs = additionalInstrumentationArgs,
              targetTestApp = targetTestApp,
              onSessionStarted = captureSessionStarted,
              findTargetById = desktopAppRunYamlParams.findTargetById,
              onCaptureDeviceBindingsResolved = { captureDeviceBindings = it },
            )
          }

          // On-device agent: send entire YAML to device, agent loop runs on-device.
          // Used when preferHostAgent=false or for instrumentation driver fallback. This is the
          // default for KOOG_STRATEGY_GRAPH on Android on-device drivers: the request (carrying
          // agentImplementation) goes to the device's RunYamlRequestHandler, which runs the Koog
          // strategy-graph agent in-process via AndroidTrailblazeRule.
          trailblazeDriverType in TrailblazeDriverType.ANDROID_ON_DEVICE_DRIVER_TYPES -> {
            val trailblazeOnDeviceInstrumentationTarget = targetTestApp?.getTrailblazeOnDeviceInstrumentationTarget()
              ?: trailblazeHostAppTarget.getTrailblazeOnDeviceInstrumentationTarget()

            val onDeviceRpc = OnDeviceRpcClient(
              trailblazeDeviceId = trailblazeDeviceId,
              sendProgressMessage = prefixedProgressMessage,
              // Arm at the single chokepoint every synchronous on-device RPC flows through, so a
              // wedge surfacing on ANY path (including the `launchApp` pre-action, which the
              // session-status detection can't see) force-restarts the shared server next trail.
              onNonRecoverableWedge = { armWedgedDevice(trailblazeDeviceId) },
            )

            // Set driver type on request so the on-device server knows which driver to use
            val requestWithDriverType = runYamlRequest.copy(driverType = trailblazeDriverType)

            runYamlOnDevice(
              onDeviceRpc = onDeviceRpc,
              trailblazeConnectedDevice = connectedTrailblazeDevice,
              trailblazeOnDeviceInstrumentationTarget = trailblazeOnDeviceInstrumentationTarget,
              runYamlRequest = requestWithDriverType,
              onProgressMessage = prefixedProgressMessage,
              onConnectionStatus = onConnectionStatus,
              additionalInstrumentationArgs = additionalInstrumentationArgs,
              onSessionStarted = captureSessionStarted,
            )
          }

          else -> {
            val hostResult = TrailblazeHostYamlRunner.runHostYaml(
              dynamicLlmClient = dynamicLlmClientProvider(desktopAppRunYamlParams.runYamlRequest.trailblazeLlmModel),
              runOnHostParams = RunOnHostParams(
                runYamlRequest = runYamlRequest,
                device = hostRunDevice,
                onProgressMessage = prefixedProgressMessage,
                forceStopTargetApp = forceStopTargetApp,
                targetTestApp = targetTestApp,
                additionalInstrumentationArgs = {
                  // Not required since this is "host", but is required "on-device"
                  emptyMap()
                },
                // Start session-scoped capture (iOS Simulator log stream → device.log, Android
                // logcat) the moment the Maestro session is created, BEFORE the synchronous trail
                // run. This default-agent branch previously started no capture at all for the
                // local Maestro paths — the finally block's stopForSession had nothing to stop —
                // so iOS logs never landed in the report. Coordinator skips WEB and is idempotent.
                onSessionStarted = captureSessionStarted,
                composeRpcPort = desktopAppRunYamlParams.composeRpcPort,
                referrer = desktopAppRunYamlParams.runYamlRequest.referrer,
                noLogging = desktopAppRunYamlParams.noLogging,
                // Thread the resolved video toggle to the web / Electron rules, which
                // self-instrument capture (the coordinator skips WEB) and would otherwise
                // ignore `--no-capture-video`.
                captureVideo = captureOptionsForRun.captureVideo,
              ),
              deviceManager = trailblazeDeviceManager,
            )
            lastToolResult = hostResult.lastToolResult

            onConnectionStatus(
              DeviceConnectionStatus.WithTargetDevice.TrailblazeInstrumentationRunning(
                trailblazeDeviceId = connectedTrailblazeDevice.trailblazeDeviceId,
              ),
            )
            hostResult.sessionId
          }
        }

        // Defensive: any branch that returns null without throwing (e.g.
        // runYamlOnDevice on RpcResult.Failure) still indicates the test did NOT
        // succeed. Without this, executionResult would stay at its Success default
        // and onComplete would lie to the caller — which is exactly the silent-
        // failure mode that hid the cached-LLM-model bug for so long.
        if (sessionId == null && executionResult is TrailExecutionResult.Success) {
          executionResult = TrailExecutionResult.Failed(
            "Test execution did not produce a session id (see daemon log for details)",
          )
        }

        // A successful host/Maestro run whose last tool produced a payload carries it on the
        // terminal Success so the `trailblaze tool <read-tool>` blocking path can render the
        // tool's real return value. No-op for every other branch: lastToolResult stays null,
        // and a run that already failed above keeps its Failed result.
        val resolvedToolResult = lastToolResult
        if (resolvedToolResult != null && executionResult is TrailExecutionResult.Success) {
          executionResult = TrailExecutionResult.Success(
            toolMessage = resolvedToolResult.message,
            toolStructuredContent = resolvedToolResult.structuredContent,
          )
        }
      } catch (e: CancellationException) {
        Console.log("⚠️ COROUTINE CANCELLED for device ${trailblazeDeviceId.instanceId}")
        executionResult = TrailExecutionResult.Cancelled
        // Don't re-throw yet — let the finally block save capture artifacts first.
        // CancellationException is re-thrown after cleanup below.
      } catch (e: TrailblazeSessionCancelledException) {
        // User-initiated session cancel. Distinct from coroutine cancellation
        // (TSCE extends Exception, not CancellationException) so we have to
        // catch it before the generic Exception branch — otherwise it would
        // be reported as Failed, not Cancelled.
        Console.log("🚫 Session cancelled by user for device ${trailblazeDeviceId.instanceId}")
        prefixedProgressMessage("Test session cancelled")
        executionResult = TrailExecutionResult.Cancelled
      } catch (e: Exception) {
        Console.log("⚠️ EXCEPTION in coroutine for device ${trailblazeDeviceId.instanceId}: ${e::class.simpleName} - ${e.message}")
        // Full stack trace to the daemon log so the throw site is diagnosable — the one-line
        // message alone hid which internal call actually failed (e.g. a decodeTrail deep in the
        // dispatch path vs. a device-connect IOException).
        Console.log(e.stackTraceToString())
        prefixedProgressMessage("Error: ${e.message}")
        executionResult = TrailExecutionResult.Failed(e.message)
        try {
          onConnectionStatus(
            DeviceConnectionStatus.DeviceConnectionError.ConnectionFailure(
              errorMessage = e.message ?: "Unknown error",
            ),
          )
        } catch (classLoadError: Throwable) {
          // Fallback if ConnectionFailure class fails to load (Kotlin multiplatform classloading issue)
          Console.log("⚠️ Failed to create ConnectionFailure instance: ${classLoadError::class.simpleName} - ${classLoadError.message}")
        }
      } finally {
        // Always stop capture and save artifacts — even on cancel/error, the video
        // recorded up to this point is valuable for debugging.
        // Clear the thread interrupt flag so capture stop methods (which use
        // Process.waitFor and Thread.sleep) don't throw InterruptedException.
        // Without this, xcrun/screenrecord get killed before finalizing the video.
        Thread.interrupted()
        // On cancellation, sessionId may not have been set yet. Find the session
        // that was created during this test run by matching the device ID in the
        // session's first log file. This handles concurrent multi-device runs.
        var deviceMatched = false
        val resolvedSessionId = sessionId
          ?: run {
            val newSessions = trailblazeDeviceManager.logsRepo.getSessionIds()
              .filter { it !in preExistingSessionIds }
            val deviceInstanceId = trailblazeDeviceId.instanceId
            // Match by parsing the session's logs and checking its Started record for this
            // device's instance ID (see [sessionBelongsToDevice] for why never by filename).
            val matched = newSessions.firstOrNull { sid ->
              sessionBelongsToDevice(
                sessionInfo = trailblazeDeviceManager.logsRepo.getSessionInfoSummary(sid),
                deviceInstanceId = deviceInstanceId,
              )
            }
            deviceMatched = matched != null
            // The bare fallback may be a concurrent run's session on another device: it only
            // ever gets the idempotent legacy stopForSession below, never the destructive
            // finalization barrier (which tombstones the session's capture registries).
            matched ?: newSessions.firstOrNull()
          }
        // Land pre-run advisories on branches that never fire captureSessionStarted or that threw
        // before returning their session id: the Playwright-native web/Electron/Compose/Revyl
        // paths only surface the id after the trail completes, so a mid-run throw would otherwise
        // lose the advisory on exactly the failed session that needs it. Drain-once makes this a
        // no-op when captureSessionStarted already ran; the ownership guard keeps the bare
        // newSessions fallback from writing advisories into a concurrent run's session.
        if (resolvedSessionId != null && (sessionId != null || deviceMatched)) {
          pendingAdvisories.logTo(resolvedSessionId)
        }
        // Stop capture for the session if we own it (i.e. the runner's
        // captureSessionStarted callback fired and started it). Idempotent — if the
        // MCP path or another caller already stopped it via endSessionForDevice,
        // the coordinator no-ops. Artifacts are already in the session log dir;
        // no temp-dir move needed.
        if (resolvedSessionId != null) {
          // Finalize downstream evidence (plugin-capture sessions, network capture) only when
          // this run owns the session end. Interactive/MCP sessions span many runYaml calls with
          // sendSessionEndLog=false; finalizing them here would tombstone the live session's
          // capture registries mid-conversation. Those sessions are finalized exactly once, by
          // endSessionForDevice / cancelSessionForDevice.
          val ownsSessionEnd = sessionId != null || deviceMatched
          val finalizerFailure =
            if (runYamlRequest.config.sendSessionEndLog && ownsSessionEnd) {
              runCatching {
                finalizeHostSessionResources(
                  listOf(resolvedSessionId),
                  trailblazeDeviceManager.sessionCaptureCoordinator::stopForSession,
                )
              }.exceptionOrNull()
            } else {
              trailblazeDeviceManager.sessionCaptureCoordinator.stopForSession(resolvedSessionId)
              null
            }
          if (finalizerFailure != null && executionResult is TrailExecutionResult.Success) {
            executionResult =
              TrailExecutionResult.Failed(
                finalizerFailure.message ?: "Host session finalization failed; artifacts may be incomplete."
              )
          }
        }
        Console.log("🏁 COROUTINE FINISHED (finally block) for device: ${trailblazeDeviceId.instanceId}")
        onComplete?.invoke(executionResult)
        // Re-throw CancellationException to properly propagate cancellation
        if (executionResult is TrailExecutionResult.Cancelled) {
          throw CancellationException("Test cancelled for device ${trailblazeDeviceId.instanceId}")
        }
      }
    }
  }

  /**
   * Connects on-device instrumentation, optionally enables the accessibility service, and
   * polls readiness. Single path for every host→device trail dispatcher in this file.
   *
   * ### Zombie-instrumentation recovery
   *
   * `connectToInstrumentationAndInstallAppIfNotAvailable` decides "already running" by checking
   * `isAppRunning(testAppId)` via ADB — that only confirms the OS process exists, not that the
   * HTTP server inside it is accepting connections. A process that's been killed gracelessly
   * (app crash, emulator hiccup, accessibility service rebind) can linger as a zombie: the PID
   * is alive, ADB reports it's running, but every probe times out because the server never
   * started or died. When that happens, `waitForReady` throws [IOException] after its budget
   * expires. Here we catch it and retry the whole setup with `forceRestart = true`, which
   * force-stops the process, reinstalls the test APK, and relaunches instrumentation — the
   * only thing that actually recovers a zombie. The common flow pays nothing for this fallback
   * (the first `waitForReady` succeeds), so the retry only fires on genuinely stuck devices.
   */
  private suspend fun connectAndEnsureReady(
    onDeviceRpc: OnDeviceRpcClient,
    trailblazeDeviceId: TrailblazeDeviceId,
    trailblazeOnDeviceInstrumentationTarget: TrailblazeOnDeviceInstrumentationTarget,
    additionalInstrumentationArgs: Map<String, String>,
    onProgressMessage: (String) -> Unit,
    enableAccessibility: Boolean,
    requireAndroidAccessibilityService: Boolean,
  ): DeviceConnectionStatus {
    // Step 1: connect (install/reuse) and enable accessibility if needed. Any IOException in
    // here is an infrastructure-level failure (ADB, instrumentation launch, APK install) that
    // a `forceRestart` retry would just repeat — so we let it propagate rather than hiding it
    // behind a misleading "readiness probe failed" log.
    suspend fun doConnectAndEnable(forceRestart: Boolean): DeviceConnectionStatus {
      val status = HostAndroidDeviceConnectUtils.connectToInstrumentationAndInstallAppIfNotAvailable(
        sendProgressMessage = onProgressMessage,
        deviceId = trailblazeDeviceId,
        trailblazeOnDeviceInstrumentationTarget = trailblazeOnDeviceInstrumentationTarget,
        additionalInstrumentationArgs = additionalInstrumentationArgs,
        forceRestart = forceRestart,
      )
      if (enableAccessibility) {
        // The on-device GetScreenState handler (triggered below by waitForReady) uses the
        // reliable in-process TrailblazeAccessibilityService singleton — dumpsys parsing is
        // unreliable on API 35+.
        AccessibilityServiceSetupUtils.enableAccessibilityService(
          deviceId = trailblazeDeviceId,
          hostPackage = trailblazeOnDeviceInstrumentationTarget.testAppId,
          sendProgressMessage = onProgressMessage,
        )
      }
      return status
    }

    // Consume a wedge entry set by this device's prior trail (see [wedgedDeviceIds]): the shared
    // on-device server is poisoned in a way the readiness probe can't see, so force-restart up
    // front to hand this trail a clean server. Cleared only after the relaunch + readiness probe
    // both succeed, so a failed relaunch keeps the device armed for the next attempt.
    val recoverFromPriorWedge = trailblazeDeviceId in wedgedDeviceIds
    if (recoverFromPriorWedge) {
      // Every arm source (typed field, RPC string match, GetScreenState breaker, log scan)
      // funnels into this consumption, so this one progress line guarantees the relaunch is
      // operator-visible in the CLI stream no matter which detector armed it.
      onProgressMessage(
        "Recovering from a prior non-recoverable UiAutomation wedge on " +
          "${trailblazeDeviceId.instanceId}: force-restarting the on-device server before " +
          "this trail.",
      )
    }
    val initialStatus = doConnectAndEnable(forceRestart = recoverFromPriorWedge)
    if (recoverFromPriorWedge) {
      onDeviceRpc.waitForReady(
        requireAndroidAccessibilityService = requireAndroidAccessibilityService,
      )
      wedgedDeviceIds -= trailblazeDeviceId
      return initialStatus
    }

    // Step 2: readiness probe. This is the specific failure mode we retry — the instrumentation
    // process is alive (so `isAppRunning` returned true and `forceRestart=false` reused it) but
    // the HTTP server inside it is stuck or dead. Force-restart reinstalls the APK and relaunches
    // instrumentation, which is the only thing that actually recovers a zombie. The common path
    // pays nothing for this fallback: the first `waitForReady` succeeds in ms on a warm device.
    return try {
      onDeviceRpc.waitForReady(
        requireAndroidAccessibilityService = requireAndroidAccessibilityService,
      )
      initialStatus
    } catch (e: IOException) {
      onProgressMessage(
        "Device readiness probe failed (${e.message}); force-restarting instrumentation and retrying once.",
      )
      val restartedStatus = doConnectAndEnable(forceRestart = true)
      onDeviceRpc.waitForReady(
        requireAndroidAccessibilityService = requireAndroidAccessibilityService,
      )
      restartedStatus
    }
  }

  /**
   * Connects instrumentation on-device and runs MULTI_AGENT_V3 on the host, using the
   * on-device accessibility driver for individual tool execution.
   *
   * Handles the same instrumentation setup as [runYamlOnDevice] but delegates execution
   * to [TrailblazeHostYamlRunner.runHostV3WithAccessibilityYaml] instead of forwarding
   * the full trail YAML to the device.
   */
  private suspend fun runV3WithAccessibilityOnHost(
    onDeviceRpc: OnDeviceRpcClient,
    dynamicLlmClient: DynamicLlmClient,
    runYamlRequest: RunYamlRequest,
    connectedTrailblazeDevice: TrailblazeConnectedDeviceSummary,
    trailblazeOnDeviceInstrumentationTarget: TrailblazeOnDeviceInstrumentationTarget,
    onProgressMessage: (String) -> Unit,
    onConnectionStatus: (DeviceConnectionStatus) -> Unit,
    additionalInstrumentationArgs: Map<String, String>,
    targetTestApp: TrailblazeHostAppTarget?,
    onSessionStarted: (SessionId) -> Unit = {},
  ): SessionId? {
    return withContext(Dispatchers.IO) {
      // V3 + on-host path always uses the accessibility driver on-device.
      val status = connectAndEnsureReady(
        onDeviceRpc = onDeviceRpc,
        trailblazeDeviceId = connectedTrailblazeDevice.trailblazeDeviceId,
        trailblazeOnDeviceInstrumentationTarget = trailblazeOnDeviceInstrumentationTarget,
        additionalInstrumentationArgs = additionalInstrumentationArgs,
        onProgressMessage = onProgressMessage,
        enableAccessibility = true,
        requireAndroidAccessibilityService = true,
      )

      withContext(Dispatchers.Default) {
        onConnectionStatus(status)

        // Same wedge recovery as the host-agent path: a mid-trail wedge is re-thrown (so the runner
        // never returns the session id), but the terminal Ended.Failed status is written to disk
        // first. Capture the live session id and arm the relaunch in a finally so the NEXT trail
        // force-restarts the shared on-device server whether this run returns or propagates.
        var v3SessionId: SessionId? = null
        try {
          TrailblazeHostYamlRunner.runHostV3WithAccessibilityYaml(
            dynamicLlmClient = dynamicLlmClient,
            onDeviceRpc = onDeviceRpc,
            runYamlRequest = runYamlRequest,
            trailblazeDeviceId = connectedTrailblazeDevice.trailblazeDeviceId,
            onProgressMessage = onProgressMessage,
            targetTestApp = targetTestApp,
            onSessionStarted = { sessionId ->
              v3SessionId = sessionId
              onSessionStarted(sessionId)
            },
          )
        } finally {
          armIfWedged(v3SessionId, listOf(connectedTrailblazeDevice.trailblazeDeviceId), onProgressMessage)
        }
      }
    }
  }

  /**
   * Runs the legacy TrailblazeRunner agent on the host with tool execution delegated to
   * an on-device driver (accessibility or instrumentation) via RPC.
   */
  private suspend fun runHostAgentWithOnDeviceRpc(
    onDeviceRpc: OnDeviceRpcClient,
    dynamicLlmClient: DynamicLlmClient,
    runYamlRequest: RunYamlRequest,
    connectedTrailblazeDevice: TrailblazeConnectedDeviceSummary,
    trailblazeOnDeviceInstrumentationTarget: TrailblazeOnDeviceInstrumentationTarget,
    onProgressMessage: (String) -> Unit,
    onConnectionStatus: (DeviceConnectionStatus) -> Unit,
    additionalInstrumentationArgs: Map<String, String>,
    targetTestApp: TrailblazeHostAppTarget?,
    onSessionStarted: (SessionId) -> Unit = {},
    /**
     * Resolves a per-device `target:` declared by a multi-device configuration member — see
     * [DesktopAppRunYamlParams.findTargetById]. Only consulted when a member declares one.
     */
    findTargetById: ((String) -> TrailblazeHostAppTarget?)? = null,
    /**
     * Reports every device a multi-device configuration bound, called before the session starts so
     * per-session capture can arm one bridge per device. Not called for a single-device run.
     */
    onCaptureDeviceBindingsResolved: (List<CaptureDeviceBinding>) -> Unit = {},
  ): SessionId? {
    return withContext(Dispatchers.IO) {
      val needsAccessibility =
        runYamlRequest.driverType == TrailblazeDriverType.ANDROID_ONDEVICE_ACCESSIBILITY
      val status = connectAndEnsureReady(
        onDeviceRpc = onDeviceRpc,
        trailblazeDeviceId = connectedTrailblazeDevice.trailblazeDeviceId,
        trailblazeOnDeviceInstrumentationTarget = trailblazeOnDeviceInstrumentationTarget,
        additionalInstrumentationArgs = additionalInstrumentationArgs,
        onProgressMessage = onProgressMessage,
        enableAccessibility = needsAccessibility,
        requireAndroidAccessibilityService = needsAccessibility,
      )

      // Multi-device: a unified trail may declare a `config.devices:` configuration entry (an
      // entry with an inner `devices:` map of named devices). Resolve it to concrete devices —
      // the launch device IS the first declared name — and run the same connect/readiness flow
      // the launch device just got, so the runner receives already-warmed RPC clients for every
      // remaining named device.
      val resolvedConfiguration = resolveMultiDeviceConfiguration(
        runYamlRequest = runYamlRequest,
        primaryDeviceId = connectedTrailblazeDevice.trailblazeDeviceId,
      )
      // Companion clients are closed in this method's own finally (and immediately if a later
      // companion fails to connect) — one leaked RPC connection per multi-device run would
      // otherwise accumulate for the daemon's lifetime.
      val companionRpcClients = mutableListOf<OnDeviceRpcClient>()
      val multiDeviceSession = resolvedConfiguration?.let { resolved ->
        onProgressMessage(
          "Multi-device configuration '${resolved.configurationName}': start device " +
            "'${resolved.startDeviceName}' is ${connectedTrailblazeDevice.trailblazeDeviceId.instanceId}",
        )
        // Each device runs its own `target:` when the configuration declares one, else the session
        // target. Resolved BEFORE any companion connects so an unknown target id fails the run
        // before it boots devices for a session that could never have been correct.
        val memberTargets = MultiDeviceConfigurationResolver.resolveMemberTargets(
          configurationName = resolved.configurationName,
          memberTargetIds = resolved.memberTargetIds,
          sessionTarget = targetTestApp,
          findTargetById = findTargetById,
        )
        // Android companions connect over the on-device RPC transport; WEB companions are
        // host-owned Playwright browsers with no device and no RPC. Any other platform (an iOS
        // simulator) has no companion transport yet — reject it by name here rather than
        // surfacing an inscrutable adb failure.
        val unsupportedCompanions = resolved.companionDeviceIds.filterValues {
          it.trailblazeDevicePlatform != TrailblazeDevicePlatform.ANDROID &&
            it.trailblazeDevicePlatform != TrailblazeDevicePlatform.WEB
        }
        if (unsupportedCompanions.isNotEmpty()) {
          throw TrailblazeException(
            "Configuration '${resolved.configurationName}' casts companion devices on platforms " +
              "with no companion transport: " +
              unsupportedCompanions.entries.joinToString { (name, id) ->
                "'$name' (${id.trailblazeDevicePlatform})"
              } +
              ". Companions run either over the Android on-device RPC transport or as a host-owned " +
              "web browser, so only ANDROID and WEB companions are supported yet.",
          )
        }
        try {
          TrailblazeHostYamlRunner.MultiDeviceSessionRpc(
            configurationName = resolved.configurationName,
            startDeviceName = resolved.startDeviceName,
            startDeviceTarget = memberTargets[resolved.startDeviceName],
            companions = resolved.companionDeviceIds.mapValues { (name, companionDeviceId) ->
              val companionTarget = memberTargets[name]
              onProgressMessage(
                "Binding device '$name' to ${companionDeviceId.instanceId}" +
                  (companionTarget?.id?.let { " (target '$it')" } ?: ""),
              )
              if (companionDeviceId.trailblazeDevicePlatform == TrailblazeDevicePlatform.WEB) {
                TrailblazeHostYamlRunner.CompanionDeviceConnection.WebBrowser(
                  trailblazeDeviceId = companionDeviceId,
                  pageManager = bindWebCompanionBrowser(companionDeviceId, name, onProgressMessage),
                  // A browser installs no app, so this only scopes the tools its agent resolves.
                  targetTestApp = companionTarget,
                )
              } else {
                val companionRpc = OnDeviceRpcClient(
                  trailblazeDeviceId = companionDeviceId,
                  sendProgressMessage = onProgressMessage,
                  onNonRecoverableWedge = { armWedgedDevice(companionDeviceId) },
                )
                companionRpcClients += companionRpc
                connectAndEnsureReady(
                  onDeviceRpc = companionRpc,
                  trailblazeDeviceId = companionDeviceId,
                  // This device's own target decides which instrumentation runner warms up on it —
                  // a companion running a different app than the launch device needs that app's
                  // runner, not the launch device's.
                  trailblazeOnDeviceInstrumentationTarget =
                    companionTarget?.getTrailblazeOnDeviceInstrumentationTarget()
                      ?: trailblazeOnDeviceInstrumentationTarget,
                  additionalInstrumentationArgs = additionalInstrumentationArgs,
                  onProgressMessage = onProgressMessage,
                  enableAccessibility = needsAccessibility,
                  requireAndroidAccessibilityService = needsAccessibility,
                )
                TrailblazeHostYamlRunner.CompanionDeviceConnection.AndroidRpc(
                  trailblazeDeviceId = companionDeviceId,
                  rpcClient = companionRpc,
                  targetTestApp = companionTarget,
                )
              }
            },
          )
        } catch (t: Throwable) {
          closeCompanionRpcClients(companionRpcClients)
          throw t
        }
      }

      // Announced before the runner starts the session (which is what fires `onSessionStarted`), so
      // capture arms every display's bridge rather than only the launch device's. The launch device
      // is named too: in a session with two displays there is no unlabeled "the" network stream.
      multiDeviceSession?.let { session ->
        onCaptureDeviceBindingsResolved(
          listOf(
            CaptureDeviceBinding(
              name = session.startDeviceName,
              deviceId = connectedTrailblazeDevice.trailblazeDeviceId,
              targetAppIds = (session.startDeviceTarget ?: targetTestApp)
                ?.getPossibleAppIdsForPlatform(
                  connectedTrailblazeDevice.trailblazeDeviceId.trailblazeDevicePlatform,
                )
                .orEmpty(),
            ),
          ) +
            session.companions.map { (name, companion) ->
              CaptureDeviceBinding(
                name = name,
                deviceId = companion.trailblazeDeviceId,
                // This device's OWN target: capture verifies the app identity of the client that
                // dials in, so the launch device's app ids would make it reject the right client.
                targetAppIds = (companion.targetTestApp ?: targetTestApp)
                  ?.getPossibleAppIdsForPlatform(
                    companion.trailblazeDeviceId.trailblazeDevicePlatform,
                  )
                  .orEmpty(),
              )
            },
        )
      }

      try {
        withContext(Dispatchers.Default) {
          onConnectionStatus(status)

          // A mid-trail wedge on this path is re-thrown by `executeTrailSession`, so the runner
          // never returns the wedged session's id — but the terminal `Ended.Failed` status (with
          // the non-recoverable signature) is written to disk before the re-throw. Capture the live
          // session id from `onSessionStarted` and arm the relaunch in a finally so detection fires
          // whether the runner returns normally or propagates the wedge as an exception.
          var hostAgentSessionId: SessionId? = null
          try {
            TrailblazeHostYamlRunner.runHostTrailblazeRunnerWithOnDeviceRpc(
              dynamicLlmClient = dynamicLlmClient,
              onDeviceRpc = onDeviceRpc,
              runYamlRequest = runYamlRequest,
              trailblazeDeviceId = connectedTrailblazeDevice.trailblazeDeviceId,
              onProgressMessage = onProgressMessage,
              targetTestApp = targetTestApp,
              onSessionStarted = { sessionId ->
                hostAgentSessionId = sessionId
                onSessionStarted(sessionId)
              },
              multiDeviceSession = multiDeviceSession,
            )
          } finally {
            armIfWedged(
              hostAgentSessionId,
              // Android companions only — arming relaunches an on-device server, which a
              // host-owned web browser doesn't have.
              listOf(connectedTrailblazeDevice.trailblazeDeviceId) +
                multiDeviceSession?.companions?.values.orEmpty()
                  .filterIsInstance<TrailblazeHostYamlRunner.CompanionDeviceConnection.AndroidRpc>()
                  .map { it.trailblazeDeviceId },
              onProgressMessage,
            )
          }
        }
      } finally {
        closeCompanionRpcClients(companionRpcClients)
      }
    }
  }

  /**
   * Binds a WEB companion to a host-owned Playwright browser, reusing the daemon slot named by
   * [companionDeviceId] when one is already running (the desktop "Launch Browser" button, a
   * `device create web`, or a previous trail in the same daemon) and launching it otherwise.
   *
   * A web device is virtual: unlike an Android companion there is nothing to boot, connect, or
   * warm up, so a CI lane binds one with `dashboard=playwright-native` and no boot step.
   *
   * Deliberately does NOT reset the browser session — same rule the single-device web runner
   * follows when it adopts a running slot. A reset would navigate an already-signed-in browser
   * to `about:blank`, discarding exactly the state an operator provisioned it for.
   */
  private suspend fun bindWebCompanionBrowser(
    companionDeviceId: TrailblazeDeviceId,
    deviceName: String,
    onProgressMessage: (String) -> Unit,
  ): PlaywrightPageManager {
    val webBrowserManager = trailblazeDeviceManager.webBrowserManager
    val instanceId = companionDeviceId.instanceId
    webBrowserManager.getPageManager(instanceId)?.let { running ->
      onProgressMessage("Device '$deviceName' bound to the running browser '$instanceId'")
      return running
    }
    onProgressMessage("Launching browser '$instanceId' for device '$deviceName'...")
    val launched = CompletableDeferred<Unit>()
    webBrowserManager.launchBrowser(instanceId = instanceId) { launched.complete(Unit) }
    withTimeoutOrNull(WEB_COMPANION_LAUNCH_TIMEOUT_MS) { launched.await() }
    // `launchBrowser` reports failures through the slot's state rather than throwing, and its
    // completion callback doesn't fire on the error path — so the manager, not the callback, is
    // what says whether this companion actually has a browser to drive.
    return webBrowserManager.getPageManager(instanceId)
      ?: throw TrailblazeException(
        "Device '$deviceName' is bound to web browser '$instanceId', but the browser did not " +
          "come up within ${WEB_COMPANION_LAUNCH_TIMEOUT_MS / 1000}s. Check the daemon log for a " +
          "Playwright/Chromium launch failure (a first run installs Chromium, which can take " +
          "longer), then re-run.",
      )
  }

  /**
   * Closes each companion's RPC client, tolerating individual failures so one unresponsive
   * companion can't strand the others' connections. No-op for single-device runs.
   */
  private fun closeCompanionRpcClients(clients: List<OnDeviceRpcClient>) {
    clients.forEach { client ->
      runCatching { client.close() }.onFailure { t ->
        Console.log("[DesktopYamlRunner] Failed to close a companion RPC client: ${t.message}")
      }
    }
  }

  /**
   * Resolves a unified trail's `config.devices:` CONFIGURATION entry against the
   * `TRAILBLAZE_DEVICE_BINDINGS` env var. Thin env-reading wrapper over the pure
   * [MultiDeviceConfigurationResolver.resolve] so the resolution rules stay unit-testable.
   */
  private fun resolveMultiDeviceConfiguration(
    runYamlRequest: RunYamlRequest,
    primaryDeviceId: TrailblazeDeviceId,
  ): MultiDeviceConfigurationResolver.ResolvedMultiDeviceConfiguration? =
    MultiDeviceConfigurationResolver.resolve(
      yaml = runYamlRequest.yaml,
      primaryDeviceId = primaryDeviceId,
      rawDeviceBindings = System.getenv(MultiDeviceConfigurationResolver.DEVICE_BINDINGS_ENV_VAR),
    )

  /**
   * Reads [sessionId]'s logs from disk and, when the terminal status OR any failed tool log
   * carries the non-recoverable UiAutomation wedge (see [shouldRelaunchOnDeviceServer]), arms
   * every device in [boundDeviceIds] in [wedgedDeviceIds] so each one's next trail
   * force-restarts its shared on-device server. Shared by the V1 on-device path (which polls
   * completion in [awaitOnDeviceSessionCompletion]) and the host-agent / V3 paths (detection
   * runs in their `finally` against the on-disk logs). No-op when [sessionId] is null or
   * nothing in the session matches the wedge signature.
   *
   * Multi-device sessions pass ALL bound devices (launch device + companions): the session's
   * logs are shared with no per-device attribution yet, so a companion-side wedge is
   * indistinguishable from a launch-device wedge here. Arming every bound device over-arms the
   * healthy ones — each pays one cheap force-restart before its next trail — but scoping to the
   * launch device alone left a wedged companion poisoned for every subsequent run.
   */
  private fun armIfWedged(
    sessionId: SessionId?,
    boundDeviceIds: List<TrailblazeDeviceId>,
    onProgressMessage: (String) -> Unit,
  ) {
    if (sessionId == null) return
    val logs = trailblazeDeviceManager.logsRepo.getLogsForSession(sessionId)
    if (shouldRelaunchOnDeviceServer(logs)) {
      boundDeviceIds.forEach { armWedgedDevice(it) }
      onProgressMessage(
        "On-device UiAutomation wedged (non-recoverable); the on-device server will be " +
          "force-restarted before the next trail.",
      )
    }
  }

  /**
   * Executes YAML test on a device using instrumentation.
   */
  private suspend fun runYamlOnDevice(
    onDeviceRpc: OnDeviceRpcClient,
    trailblazeConnectedDevice: TrailblazeConnectedDeviceSummary,
    trailblazeOnDeviceInstrumentationTarget: TrailblazeOnDeviceInstrumentationTarget,
    runYamlRequest: RunYamlRequest,
    onConnectionStatus: (DeviceConnectionStatus) -> Unit,
    onProgressMessage: (String) -> Unit,
    additionalInstrumentationArgs: Map<String, String>,
    /**
     * Fired exactly once after the on-device RPC reports a successful start, BEFORE we begin
     * polling for completion. The session is live at this point — the on-device runner has
     * created the session directory and is about to execute the YAML. Callers use this to spin
     * up out-of-band session-scoped infrastructure that needs to run *while* the YAML executes
     * (e.g. an Android network-capture activator — it has to be polling its discovery
     * side-channel before the launch tool's first network call so it can attach to the
     * target's freshly-opened socket). Defaulted to a no-op so existing callers stay compatible.
     */
    onSessionStarted: (SessionId) -> Unit = {},
  ): SessionId? {
    return withContext(Dispatchers.IO) {
      val needsAccessibility =
        runYamlRequest.driverType == TrailblazeDriverType.ANDROID_ONDEVICE_ACCESSIBILITY
      val status = connectAndEnsureReady(
        onDeviceRpc = onDeviceRpc,
        trailblazeDeviceId = trailblazeConnectedDevice.trailblazeDeviceId,
        trailblazeOnDeviceInstrumentationTarget = trailblazeOnDeviceInstrumentationTarget,
        additionalInstrumentationArgs = additionalInstrumentationArgs,
        onProgressMessage = onProgressMessage,
        enableAccessibility = needsAccessibility,
        requireAndroidAccessibilityService = needsAccessibility,
      )

      withContext(Dispatchers.Default) {
        onConnectionStatus(status)
        when (val result: RpcResult<RunYamlResponse> = onDeviceRpc.rpcCall(runYamlRequest)) {
          is RpcResult.Failure -> {
            onProgressMessage("Failed to start YAML execution: ${result.message}${result.details?.let { " | $it" } ?: ""}")
            null
          }

          is RpcResult.Success -> {
            val runYamlResponse = result.data
            onProgressMessage("YAML test execution started for session: ${runYamlResponse.sessionId}")

            // Notify the caller that the session is live, BEFORE we block on completion. This is
            // the only window in which session-scoped out-of-band infrastructure (the network
            // capture bridge, mainly) can attach with the right session ID. Errors from the
            // callback are caught so a misbehaving listener can't crash the test run.
            try {
              onSessionStarted(runYamlResponse.sessionId)
            } catch (t: Throwable) {
              Console.log(
                "[runYamlOnDevice] onSessionStarted callback threw — continuing test run: " +
                  "${t::class.java.simpleName}: ${t.message}"
              )
            }

            // Wait for the on-device test to complete. The RPC returns immediately
            // (fire-and-forget), but we need to block until the session reaches an
            // Ended status so that logs are fully streamed before the process exits.
            awaitOnDeviceSessionCompletion(
              sessionId = runYamlResponse.sessionId,
              trailblazeDeviceId = trailblazeConnectedDevice.trailblazeDeviceId,
              onProgressMessage = onProgressMessage,
            )

            runYamlResponse.sessionId
          }
        }
      }
    }
  }

  /**
   * Polls the logsRepo until the given session reaches a terminal [SessionStatus.Ended]
   * state, or until the timeout expires. This ensures on-device logs are fully received
   * before the coroutine completes and the process potentially exits.
   *
   * Reads logs directly from disk (not cached flows) so new files are detected immediately.
   */
  private suspend fun awaitOnDeviceSessionCompletion(
    sessionId: SessionId,
    trailblazeDeviceId: TrailblazeDeviceId,
    onProgressMessage: (String) -> Unit,
    maxWaitMs: Long = 600_000,
    pollIntervalMs: Long = 1_000,
  ) {
    val logsRepo = trailblazeDeviceManager.logsRepo
    val startTime = System.currentTimeMillis()

    while (System.currentTimeMillis() - startTime < maxWaitMs) {
      val status = logsRepo.getLogsForSession(sessionId).getSessionStatus()
      if (status is SessionStatus.Ended) {
        // Gate: ONLY the non-recoverable UiAutomation wedge arms a relaunch — never an ordinary
        // failure, so a clean server is provisioned for the NEXT trail without re-running this one.
        armIfWedged(sessionId, listOf(trailblazeDeviceId), onProgressMessage)
        return
      }
      delay(pollIntervalMs)
    }
    onProgressMessage("Warning: Timed out waiting for on-device session to complete")
  }

  /**
   * If [runYamlRequest]'s config has `captureNetworkTraffic=true` and the target is Android, ask
   * the registered [AndroidNetworkCaptureRegistry.activator] (optionally set by a downstream
   * desktop app at startup) to spin up a per-session bridge. Default distributions ship without
   * an activator and this is a no-op.
   *
   * Returns the session id under which the bridge was started (so [runYaml]'s outer `finally`
   * can stop it), or null when capture wasn't requested / wasn't applicable.
   *
   * The MCP-driven path has the equivalent wiring inside `TrailblazeMcpBridgeImpl.executeToolViaRpc`.
   * Both call sites need to exist because the desktop UI's "Run YAML" button takes the
   * [DesktopYamlRunner] path, NOT the MCP path — without this method, capture is silently
   * dropped for every desktop-driven Android session even though the toggle is on.
   */
  private fun maybeStartAndroidNetworkCapture(
    runYamlRequest: RunYamlRequest,
    deviceId: TrailblazeDeviceId,
    sessionIdOverride: SessionId,
    targetAppIds: List<String>,
    onProgressMessage: (String) -> Unit,
    deviceBindings: List<CaptureDeviceBinding> = emptyList(),
  ): String? {
    if (deviceBindings.isNotEmpty()) {
      return startMultiDeviceAndroidNetworkCapture(
        runYamlRequest = runYamlRequest,
        sessionIdOverride = sessionIdOverride,
        deviceBindings = deviceBindings,
        onProgressMessage = onProgressMessage,
      )
    }
    if (deviceId.trailblazeDevicePlatform != TrailblazeDevicePlatform.ANDROID) return null
    val activator = AndroidNetworkCaptureRegistry.activator ?: return null
    // Two SELF-CONTAINED opt-ins can turn capture on for this Android run without also needing
    // --capture-network: the TRAILBLAZE_ANDROID_PROXY_CAPTURE env var (proxy path), and the
    // registered activator's own per-session opt-in (e.g. a distribution's env-var-driven capture
    // mode). So `TRAILBLAZE_ANDROID_PROXY_CAPTURE=1 trailblaze run <trail>` just works.
    // (--capture-network / the desktop "Capture Network Traffic" toggle still work too — that's the
    // path for web + the internal in-app capturer.)
    val androidProxyOptIn = CompositeAndroidNetworkCaptureActivator.proxyCaptureEnabledFromEnv()
    val activatorOptIn = activator.isSessionCaptureOptedIn(sessionIdOverride.value)
    if (!runYamlRequest.config.captureNetworkTraffic && !androidProxyOptIn && !activatorOptIn) {
      return null
    }
    val sessionDir = trailblazeDeviceManager.logsRepo.getSessionDir(sessionIdOverride)
    return runCatching {
        activator.start(
          sessionId = sessionIdOverride.value,
          sessionDir = sessionDir,
          deviceId = deviceId,
          targetAppIds = targetAppIds,
        )
        onProgressMessage(
          "Android network capture bridge started for session ${sessionIdOverride.value}",
        )
        sessionIdOverride.value
      }
      .onFailure {
        Console.log(
          "Auto-start of Android network capture failed for ${sessionIdOverride.value}: ${it.message}"
        )
      }
      .getOrNull()
  }

  /**
   * Arms one capture bridge per Android device a multi-device configuration bound, each labelled
   * with the device's configuration name so its evidence lands in its own stream.
   *
   * One device failing to arm does NOT abort the others: a pair session where only one display's
   * app carries a capture client should still capture that display. Web bindings are skipped — a
   * host-owned browser has no adb device to attach to.
   *
   * [MultiDeviceCaptureSelection.CAPTURE_DEVICES_ENV_VAR] narrows this to named devices.
   */
  private fun startMultiDeviceAndroidNetworkCapture(
    runYamlRequest: RunYamlRequest,
    sessionIdOverride: SessionId,
    deviceBindings: List<CaptureDeviceBinding>,
    onProgressMessage: (String) -> Unit,
  ): String? {
    val activator = AndroidNetworkCaptureRegistry.activator ?: return null
    // Opt-in first: everything below either logs or attaches, and a log line saying which devices
    // are being armed reads as capture having been armed even when this gate then refuses.
    val androidProxyOptIn = CompositeAndroidNetworkCaptureActivator.proxyCaptureEnabledFromEnv()
    val activatorOptIn = activator.isSessionCaptureOptedIn(sessionIdOverride.value)
    if (!runYamlRequest.config.captureNetworkTraffic && !androidProxyOptIn && !activatorOptIn) {
      return null
    }
    val allowedDeviceNames =
      MultiDeviceCaptureSelection.parseDeviceNames(
        System.getenv(MultiDeviceCaptureSelection.CAPTURE_DEVICES_ENV_VAR),
      )
    val selection =
      MultiDeviceCaptureSelection.select(
        candidates = deviceBindings.filter {
          it.deviceId.trailblazeDevicePlatform == TrailblazeDevicePlatform.ANDROID
        },
        allowedNames = allowedDeviceNames,
        nameOf = { it.name },
      )
    val androidBindings = selection.armed
    if (allowedDeviceNames.isNotEmpty()) {
      // Both directions are logged because both are silent otherwise: a skipped device just has no
      // stream in the report, and a misspelled name disarms capture for the whole session.
      Console.log(
        "${MultiDeviceCaptureSelection.CAPTURE_DEVICES_ENV_VAR} limits network capture to " +
          "${allowedDeviceNames.sorted()}; arming ${androidBindings.map { it.name }} of bound " +
          "devices ${deviceBindings.map { it.name }}",
      )
      if (selection.unknownNames.isNotEmpty()) {
        Console.log(
          "${MultiDeviceCaptureSelection.CAPTURE_DEVICES_ENV_VAR} names " +
            "${selection.unknownNames}, which this session has no capturable device for. " +
            "Check the names against the trail's `config.devices` entry.",
        )
      }
    }
    if (androidBindings.isEmpty()) return null
    val sessionDir = trailblazeDeviceManager.logsRepo.getSessionDir(sessionIdOverride)
    var anyStarted = false
    androidBindings.forEach { binding ->
      runCatching {
        activator.start(
          sessionId = sessionIdOverride.value,
          sessionDir = sessionDir,
          deviceId = binding.deviceId,
          targetAppIds = binding.targetAppIds,
          deviceLabel = binding.name,
        )
        anyStarted = true
        onProgressMessage(
          "Android network capture bridge started for device '${binding.name}' " +
            "(${binding.deviceId.instanceId}) in session ${sessionIdOverride.value}",
        )
      }
        .onFailure {
          // Named, not swallowed: a missing device stream in the report must be traceable to the
          // device that could not be attached.
          Console.log(
            "Auto-start of Android network capture failed for device '${binding.name}' " +
              "(${binding.deviceId.instanceId}) in ${sessionIdOverride.value}: ${it.message}"
          )
        }
    }
    return sessionIdOverride.value.takeIf { anyStarted }
  }

  /**
   * One device of a multi-device session, as capture needs to see it: the configuration's [name] for
   * the device (what its evidence streams are suffixed with), the device itself, and the app ids
   * capture must accept when it verifies the identity of the client that dials in on that device.
   */
  private data class CaptureDeviceBinding(
    val name: String,
    val deviceId: TrailblazeDeviceId,
    val targetAppIds: List<String>,
  )

  // `stopCaptureAndMoveArtifacts` lived here. Removed in favor of
  // [SessionCaptureCoordinator.stopForSession], which writes artifacts directly into
  // the session log dir from the start so the temp-dir + move step is no longer needed.
}
