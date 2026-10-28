package xyz.block.trailblaze.host.yaml

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import xyz.block.trailblaze.devices.AndroidAccessibilityServiceDrivers
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
import xyz.block.trailblaze.devices.TrailDeviceLocale
import xyz.block.trailblaze.host.devices.DeviceLocaleConfigurator
import xyz.block.trailblaze.host.devices.MaestroConnectedDevice
import xyz.block.trailblaze.host.devices.TrailblazeConnectedDevice
import xyz.block.trailblaze.host.devices.TrailblazeDeviceService
import xyz.block.trailblaze.host.dexopt.SessionAppCompileEnsurer
import xyz.block.trailblaze.host.turbo.SessionTurboAttacher
import xyz.block.trailblaze.host.networkcapture.AndroidNetworkCaptureRegistry
import xyz.block.trailblaze.host.capture.SessionCaptureCoordinator
import xyz.block.trailblaze.host.capture.finalizeHostSessionResources
import xyz.block.trailblaze.host.networkcapture.CompositeAndroidNetworkCaptureActivator
import xyz.block.trailblaze.host.networkcapture.WebCompanionNetworkCapture
import xyz.block.trailblaze.host.networkcapture.androidCaptureRequiresTraffic
import xyz.block.trailblaze.host.networkcapture.androidCaptureTargetAppIds
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
import xyz.block.trailblaze.mcp.android.ondevice.rpc.OnDeviceRpcClient
import xyz.block.trailblaze.mcp.android.ondevice.rpc.GetScreenStateResponse
import xyz.block.trailblaze.mcp.android.ondevice.rpc.OnDeviceRunnerCapabilities
import xyz.block.trailblaze.mcp.android.ondevice.rpc.RpcResult
import xyz.block.trailblaze.model.DesktopAppRunYamlParams
import xyz.block.trailblaze.model.DeviceConnectionStatus
import xyz.block.trailblaze.model.TrailExecutionResult
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult
import xyz.block.trailblaze.model.TrailblazeHostAppTarget
import xyz.block.trailblaze.model.TrailblazeOnDeviceInstrumentationTarget
import xyz.block.trailblaze.trailhead.TrailheadHandoffStore
import xyz.block.trailblaze.trailhead.TrailheadRuns
import xyz.block.trailblaze.transport.AndroidWireTransport
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
import xyz.block.trailblaze.yaml.TrailConfig
import xyz.block.trailblaze.yaml.createTrailblazeYaml
import xyz.block.trailblaze.yaml.unified.UnknownDriverException
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

class DesktopYamlRunner(
  private val trailblazeDeviceManager: TrailblazeDeviceManager,
  private val trailblazeAnalytics: TrailblazeAnalytics,
  private val trailblazeHostAppTargetProvider: () -> TrailblazeHostAppTarget,
  private val dynamicLlmClientProvider: (TrailblazeLlmModel) -> DynamicLlmClient,
  /**
   * The app's effective logs directory (the persisted `logsDirectory` setting, else the default),
   * handed to every [TrailblazeHostYamlRunner] path so the per-run logging rules it builds write
   * — and read their recordings back from — where the setting points. Pass the same
   * `appConfig.logsDir` the app's own [xyz.block.trailblaze.report.utils.LogsRepo] is pinned at, so
   * a session's rule-side files and its daemon-persisted files land in one directory.
   *
   * A provider rather than a value only to defer evaluation, since a desktop app may construct its
   * runner eagerly, ahead of the app config. Like the app's own repo, the value is fixed for the
   * process — a `logsDirectory` change takes effect at daemon start.
   */
  private val logsDirProvider: () -> File,
) {
  companion object {
    /**
     * How long a WEB companion's browser gets to come up. Generous because a first run on a
     * fresh machine downloads and installs Chromium before the browser can launch.
     */
    private const val WEB_COMPANION_LAUNCH_TIMEOUT_MS = 180_000L

    private val COMPANION_PLATFORMS = listOf(
      TrailblazeDevicePlatform.ANDROID,
      TrailblazeDevicePlatform.WEB,
      TrailblazeDevicePlatform.IOS,
    )

    // Force early class loading of nested DeviceConnectionStatus classes
    // This prevents ClassNotFoundException in catch blocks which reference these types
    @Suppress("unused")
    private val connectionFailureClass = DeviceConnectionStatus.DeviceConnectionError.ConnectionFailure::class
    @Suppress("unused")
    private val startingConnectionClass = DeviceConnectionStatus.WithTargetDevice.StartingConnection::class
    @Suppress("unused")
    private val instrumentationRunningClass = DeviceConnectionStatus.WithTargetDevice.TrailblazeInstrumentationRunning::class

    internal fun completeProcessFatalFailure(
      fatalFailure: Throwable,
      executionResult: TrailExecutionResult.Failed,
      onComplete: ((TrailExecutionResult) -> Unit)?,
    ): Nothing {
      try {
        onComplete?.invoke(executionResult)
      } catch (callbackFailure: Throwable) {
        fatalFailure.addSuppressed(callbackFailure)
        Console.log(
          "⚠️ Failed to report process-fatal completion: ${callbackFailure::class.java.simpleName} - ${callbackFailure.message}"
        )
      }
      throw fatalFailure
    }

    /**
     * The language a trail runs in: its `locale:`, or the default when it declares none. Null — no
     * change to the device — when the trail could not be read ([readable] false) or is skipped: a
     * skipped trail must not mutate persistent device language state. A run that
     * [continuesSession] — a prompt or single tool sent into a running session — keeps the
     * session's language unless it declares one. A trail with no steps ([hasSteps] false) also
     * leaves the device alone: the run rejects it, and a rejected run must not have switched it.
     */
    internal fun requestedDeviceLocale(
      readable: Boolean,
      config: TrailConfig?,
      continuesSession: Boolean = false,
      hasSteps: Boolean = true,
    ): TrailDeviceLocale.Request? =
      TrailDeviceLocale.forRun(config?.locale, continuesSession)
        ?.takeIf { readable && hasSteps && config?.skip.isNullOrBlank() }

    /**
     * The part of a trail's `locale:` this runner sets on the device itself. The Playwright native
     * driver has no system language to set: it opens the session's browser context in the trail's
     * locale instead (see `BasePlaywrightNativeTest.resolveBrowserLocale`). Every other driver,
     * Electron included, goes through [DeviceLocaleConfigurator], which rejects what it cannot set
     * rather than run the trail in the wrong language.
     */
    internal fun systemLocaleToApply(
      driverType: TrailblazeDriverType,
      locale: TrailDeviceLocale.Request?,
    ): TrailDeviceLocale.Request? = locale
      ?.takeUnless { driverType == TrailblazeDriverType.PLAYWRIGHT_NATIVE }
      // A Revyl cloud device is not reachable over this host's adb or simctl, so the default is
      // not attempted there; a declared locale still fails rather than run in the wrong language.
      ?.takeIf { it.declared || driverType !in REMOTE_DEVICE_DRIVERS }

    private val REMOTE_DEVICE_DRIVERS = setOf(TrailblazeDriverType.REVYL_ANDROID, TrailblazeDriverType.REVYL_IOS)

    /**
     * Changing the device language requires a fresh target process; the same language leaves a
     * running app alone. A reusable trailhead hand-off skips the ordinary clean start: the
     * trailhead decides what to keep.
     */
    internal fun shouldForceStopTargetApp(requested: Boolean, localeChanged: Boolean, keepForReuse: Boolean = false): Boolean =
      (requested && !keepForReuse) || localeChanged

    /**
     * The session id to start capture under BEFORE the whole-trail RPC is sent, or null when it can
     * only be learned from the reply.
     *
     * A synchronous [RunYamlRequest] replies when the trail has finished, so a session started from
     * the reply records nothing: on an in-process run the logcat stream came back empty and memory
     * held one reading, taken after the trail. The stop then cost every run several seconds on
     * top — the first memory reading of a just-started capture was still in flight against the
     * device. A pinned id (the CLI and the daemon both pin one) is the id the device will create
     * the session under, so capture can start under it while the trail still has to run.
     */
    internal fun sessionIdToCaptureBeforeDispatch(runYamlRequest: RunYamlRequest): SessionId? =
      runYamlRequest.config.overrideSessionId?.takeIf { runYamlRequest.awaitCompletion }

    /** The session MCP reserved for a run that ends it though the caller asked not to (a browser-launched
     *  configuration). Left on the device when setup fails before it starts, it gets the retry refused. */
    internal fun reservedSessionEndedOnReturn(runYamlRequest: RunYamlRequest, endsSession: Boolean): SessionId? =
      runYamlRequest.config.overrideSessionId?.takeIf {
        endsSession && runYamlRequest.config.sendSessionStartLog && !runYamlRequest.config.sendSessionEndLog
      }

    /**
     * Sends a synchronous whole-trail request and returns when the run is over: at the reply, or
     * as soon as the session [pinnedSessionId] names has written its end — whichever comes first.
     *
     * The device's end status already reaches the host through the log stream, and the run's
     * verdict is read from those logs anyway. The reply is a second, slower signal: an in-process
     * runner's server shares the app's IO threads, and when the app holds them the reply sat for
     * seconds — once 118 s — behind an end status that had already landed. Settling on whichever
     * arrives first stops the run waiting on the slow one. A request that fails before any session
     * starts never writes an end, so its failure reply still wins.
     *
     * With no pinned id the end cannot be recognised before the reply names it, so this is just
     * [sendRequest]. An end status the pinned id already had before the request was sent belongs
     * to an earlier run, so it is ignored and the reply alone decides.
     *
     * [onDispatching] gets the pinned id just before the request is sent, so session-scoped capture
     * runs while the trail does. If the request then fails or throws, the device never ran that
     * session and writes nothing for it, so [onAbandoned] gets the id to stop that capture.
     */
    internal suspend fun replyOrSessionEnd(
      pinnedSessionId: SessionId?,
      sendRequest: suspend () -> RpcResult<RunYamlResponse>,
      sessionHasEnded: (SessionId) -> Boolean,
      onDispatching: (SessionId) -> Unit = {},
      onAbandoned: (SessionId) -> Unit = {},
      pollIntervalMs: Long = SESSION_END_POLL_MS,
    ): RpcResult<RunYamlResponse> {
      if (pinnedSessionId == null) return sendRequest()
      val endedBeforeDispatch = sessionHasEnded(pinnedSessionId)
      onDispatching(pinnedSessionId)
      val result = try {
        if (endedBeforeDispatch) {
          sendRequest()
        } else {
          coroutineScope {
            val reply = async { sendRequest() }
            val ended = async {
              while (!sessionHasEnded(pinnedSessionId)) delay(pollIntervalMs)
              RpcResult.Success(RunYamlResponse(sessionId = pinnedSessionId))
            }
            select { reply.onAwait { it }; ended.onAwait { it } }
              .also { reply.cancel(); ended.cancel() }
          }
        }
      } catch (e: CancellationException) {
        // The run itself was cancelled, possibly mid-trail: its own teardown owns the session.
        throw e
      } catch (t: Throwable) {
        onAbandoned(pinnedSessionId)
        throw t
      }
      if (result is RpcResult.Failure) onAbandoned(pinnedSessionId)
      return result
    }

    /** How often [replyOrSessionEnd] looks for the end status; it reads only status logs. */
    private const val SESSION_END_POLL_MS = 250L

    /** Refuses a classifier-qualified run when the installed runner would discard its override. */
    internal fun deviceClassifierOverrideCapabilityRejection(
      requestsOverride: Boolean,
      runnerCapabilities: Collection<String>?,
    ): String? {
      if (!requestsOverride) return null
      if (OnDeviceRunnerCapabilities.DEVICE_CLASSIFIER_OVERRIDE in runnerCapabilities.orEmpty()) {
        return null
      }
      return "the installed on-device runner predates device classifier overrides, so it would " +
        "select recordings using only the device's physical classifiers; rebuild and reinstall " +
        "the on-device runner before retrying"
    }

    /**
     * The launch step that follows the readiness handshake: gate a classifier-qualified run on the
     * capabilities the runner advertised in [readiness], then send [runYamlRequest].
     *
     * Judged on the handshake's own answer, not a fresh probe: the runner already described itself
     * there, and a second `GetScreenState` would re-enter the not-ready window the handshake just
     * waited out with no budget to absorb it — a device that had not drawn a frame yet used to fail
     * here as "unsupported" before the trailhead launched the app. Kept as its own step so a test
     * can hold a mock server that answers every probe "not ready" and prove the only request this
     * sends is the launch itself; see DesktopYamlRunnerLaunchGateTest.
     */
    internal suspend fun startYamlAfterReadiness(
      onDeviceRpc: OnDeviceRpcClient,
      readiness: GetScreenStateResponse,
      runYamlRequest: RunYamlRequest,
    ): RpcResult<RunYamlResponse> {
      deviceClassifierOverrideCapabilityRejection(
        requestsOverride = runYamlRequest.deviceClassifierOverride.isNotEmpty(),
        runnerCapabilities = readiness.runnerCapabilities,
      )?.let { throw TrailblazeException(it) }
      return onDeviceRpc.rpcCall(runYamlRequest)
    }

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

    /** What a finished run does with the capture of the session it was running in. */
    internal enum class RunEndCaptureAction {
      /** Stop capture and finalize the session's downstream evidence — the last run of a session. */
      FINALIZE,

      /** Stop capture, but leave finalizing to whoever owns the session end. */
      STOP,

      /** Touch nothing: the session goes on, and so must its capture. */
      LEAVE_RUNNING,
    }

    /**
     * What a finished run should do with its session's capture.
     *
     * A run that does not send the session end is one call in a conversation that goes on, so its
     * capture has to go on with it. Stopping it as each call returned left every stream dark
     * between calls: a crash or a memory spike in a gap was simply never recorded, and each call
     * restarted capture from scratch. Those sessions stop their capture exactly once, from
     * `endSessionForDevice` / `cancelSessionForDevice`.
     *
     * Owning the session end is a separate question, and only decides whether the downstream
     * finalizers run here: a run holding a session id it merely discovered must not tombstone a
     * concurrent run's capture registries. Its capture still stops, because its session is ending.
     */
    internal fun runEndCaptureAction(sendsSessionEndLog: Boolean, ownsSessionEnd: Boolean): RunEndCaptureAction = when {
      !sendsSessionEndLog -> RunEndCaptureAction.LEAVE_RUNNING
      ownsSessionEnd -> RunEndCaptureAction.FINALIZE
      else -> RunEndCaptureAction.STOP
    }

    /**
     * The session-wide "the trail's declared target did not resolve" fact, narrowed to ONE device
     * of the session. Null means the device's app ids are the ones the trail drives.
     *
     * A multi-device configuration lets a member declare its own `target:`, and that member's app
     * ids come from it — not from the workspace fallback the session dropped to when the trail's
     * `config.target` named nothing this installation carries. So the session-wide fact describes
     * only the devices that took the fallback. Applying it to every device instead skips one whose
     * app is exactly the app it runs.
     */
    internal fun unresolvedTargetForDevice(
      sessionUnresolvedTarget: String?,
      deviceHasOwnTarget: Boolean,
    ): String? = sessionUnresolvedTarget.takeUnless { deviceHasOwnTarget }

    /**
     * Network capture's `targetAppIds` for one device of a run: null for a device with no target,
     * so identity-checking capture skips it rather than failing the session.
     *
     * A declared target that did not resolve stays a NAMED target (an empty list, which capture
     * refuses), even though [targetTestApp] is then the workspace fallback. Taking the fallback's
     * ids would verify capture against an app the trail did not name; and when the fallback is the
     * neutral default it would read as no target at all, silently skipping a capture the trail
     * asked for.
     */
    internal fun deviceCaptureTargetAppIds(
      targetTestApp: TrailblazeHostAppTarget?,
      unresolvedDeclaredTarget: String?,
      platform: TrailblazeDevicePlatform,
    ): List<String>? =
      androidCaptureTargetAppIds(
        targetId = unresolvedDeclaredTarget?.takeIf { it.isNotBlank() } ?: targetTestApp?.id,
        platform = platform,
        findTarget = { id -> targetTestApp?.takeIf { it.id.equals(id, ignoreCase = true) } },
      )

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
          // Through the resolver, not straight to Resolved: this entry decoded cleanly, but
          // "decodes" and "can be run" are different questions — a pin on a retired driver
          // parses fine and must still be rejected here.
          return CliRunDriverResolver.resolve(it.driver)
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
   * Driver-aware instrumentation-target resolution for the RPC branches: the trail's own target
   * first, the session's host app target as fallback — the same precedence the branches always
   * used, now routed through [TrailblazeHostAppTarget.getTrailblazeOnDeviceInstrumentationTargetForDriver]
   * so ANDROID_TEST resolves the target's in-process harness (or null when none is declared)
   * instead of the bundled runner, which knows nothing about the app's process.
   */
  private fun resolveOnDeviceInstrumentationTarget(
    driverType: TrailblazeDriverType,
    targetTestApp: TrailblazeHostAppTarget?,
    trailblazeHostAppTarget: TrailblazeHostAppTarget,
  ): TrailblazeOnDeviceInstrumentationTarget? =
    // Which target answers is decided before the call, not by whether the answer was null. Once
    // ANDROID_TEST can legitimately resolve to null, an elvis here would silently instrument a
    // DIFFERENT target's harness whenever the trail's own target declared none.
    (targetTestApp ?: trailblazeHostAppTarget)
      .getTrailblazeOnDeviceInstrumentationTargetForDriver(driverType)

  /**
   * The runner to warm on one multi-device Android companion. Same precedence rule as
   * [resolveOnDeviceInstrumentationTarget]: the companion's own target answers when it has one,
   * and only a companion with NO declared target falls back to the run's target.
   *
   * The fallback deliberately does not extend to a null ANSWER. On ANDROID_TEST a declared target
   * with no `android_test:` block resolves to null, and treating that as "use the run target's
   * harness" would instrument a different app's test APK on this device — a run that connects,
   * serves, and reports against the wrong app. Failing names the target and the fix instead.
   */
  private fun resolveCompanionInstrumentationTarget(
    configurationName: String,
    companionName: String,
    companionTarget: TrailblazeHostAppTarget?,
    driverType: TrailblazeDriverType,
    runTargetApp: TrailblazeHostAppTarget,
  ): TrailblazeOnDeviceInstrumentationTarget {
    val target = companionTarget ?: runTargetApp
    return target.getTrailblazeOnDeviceInstrumentationTargetForDriver(driverType)
      ?: throw TrailblazeException(
        "Configuration '$configurationName' binds companion device '$companionName' to target " +
          "'${target.id}'. ${target.missingInProcessHarnessMessage()}",
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
   *
   * @return the trail's job. It runs in the device's scope, not the caller's, so a caller that
   * stops waiting must cancel this job to stop the trail.
   */
  fun runYaml(desktopAppRunYamlParams: DesktopAppRunYamlParams): Job {
    val targetTestApp = desktopAppRunYamlParams.targetTestApp
    val trailblazeDeviceId = desktopAppRunYamlParams.runYamlRequest.trailblazeDeviceId
    val forceStopTargetApp = desktopAppRunYamlParams.forceStopTargetApp
    // Carried on the request so an on-device runner keeps the device's language too.
    val runYamlRequest = desktopAppRunYamlParams.runYamlRequest.let { request ->
      if (desktopAppRunYamlParams.keepDeviceLanguage) request.copy(keepDeviceLanguage = true) else request
    }
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

    // Registered before launching, not once the coroutine gets going: a caller that resolved this
    // run's session has already handed it over, and until the run is registered a cast member
    // forcing a new session would take the device off that session and stop its capture.
    val runInFlight = trailblazeDeviceManager.beginRun(trailblazeDeviceId)
    val trailJob = coroutineScope.launch {
      Console.log("🚀 COROUTINE STARTED for device: ${trailblazeDeviceId.instanceId}")
      
      // Track the execution result to report in finally block
      var executionResult: TrailExecutionResult = TrailExecutionResult.Success()
      var processFatalFailure: Throwable? = null
      // This run's session as its runner reported it starting, so a run that throws before
      // returning its session id still knows which session is its own.
      var startedSessionId: SessionId? = null

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

      // Ahead of the force-stop below, and ahead of dispatch routing, because both of those act on
      // the device: force-stopping falls back to the session-default app whenever the selection
      // resolves to nothing, so validating afterwards would kill an unrelated app on behalf of a
      // run that then fails as misuse. Needs no driver type, so it can run this early — the
      // dispatch-path refusal further down does need one and stays there.
      val selectedConfigurationName: String?
      try {
        selectedConfigurationName = MultiDeviceConfigurationResolver.selectConfigurationName(
          yaml = runYamlRequest.yaml,
          requestConfigurationName = runYamlRequest.deviceConfiguration,
          environmentConfigurationName =
            System.getenv(MultiDeviceConfigurationResolver.DEVICE_CONFIGURATION_ENV_VAR),
          requestDeviceBindingNames = runYamlRequest.deviceBindings.keys,
          rawDeviceBindings = System.getenv(MultiDeviceConfigurationResolver.DEVICE_BINDINGS_ENV_VAR),
        )
      } catch (e: TrailblazeException) {
        val message = e.message ?: "This run's device configuration is not valid"
        prefixedProgressMessage(message)
        Console.log("❌ COROUTINE ENDING (invalid device configuration)")
        executionResult = TrailExecutionResult.Failed(message, misuse = true)
        onComplete?.invoke(executionResult)
        return@launch
      }
      MultiDeviceConfigurationResolver
        .singleDeviceFallbackMessage(runYamlRequest.yaml, selectedConfigurationName)
        ?.let(prefixedProgressMessage)

      val deviceClassifiers = try {
        val classification = DeviceClassifierResolver.classificationFor(
          platform = connectedTrailblazeDevice.platform,
          instanceId = connectedTrailblazeDevice.instanceId,
        )
        DeviceClassifierResolver.resolveOverride(
          detected = DeviceClassifierResolver.trustedForBehavior(classification),
          requested = runYamlRequest.deviceClassifierOverride.map(::TrailblazeDeviceClassifier),
        )
      } catch (e: IllegalArgumentException) {
        val message = e.message ?: "This run's device classifier is not valid"
        prefixedProgressMessage(message)
        executionResult = TrailExecutionResult.Failed(message, misuse = true)
        onComplete?.invoke(executionResult)
        return@launch
      }
      // This preflight is deliberately tolerant: the runtime's normal decode still owns parse
      // errors. Its only job is to avoid a persistent locale mutation for a skipped or invalid
      // trail, before any driver/session setup starts.
      val resolvedTrailConfigResult = runCatching {
        createTrailblazeYaml().extractTrailConfig(
          yaml = runYamlRequest.yaml,
          deviceClassifiers = deviceClassifiers,
          selectedDeviceConfiguration = selectedConfigurationName,
        )
      }
      val requestedLocale = requestedDeviceLocale(
        readable = resolvedTrailConfigResult.isSuccess,
        config = resolvedTrailConfigResult.getOrNull(),
        continuesSession = runYamlRequest.keepDeviceLanguage ||
          !runYamlRequest.config.sendSessionStartLog ||
          TrailblazeYaml.Default.isBareToolEnvelope(runYamlRequest.yaml),
        // A document that does not decode as a trail here is left to the run's own decode.
        hasSteps = runCatching {
          createTrailblazeYaml().let { yaml ->
            yaml.hasActionableSteps(
              yaml.decodeTrail(
                yaml = runYamlRequest.yaml,
                deviceClassifiers = deviceClassifiers,
                selectedDeviceConfiguration = selectedConfigurationName,
              ),
            )
          }
        }.getOrDefault(true),
      )

      // Resolve driver type: request (CLI --driver / trail config) > the trail's own driver pin
      // resolved against THIS device > app setting > connected device default. The trail-pin rung
      // matches the CLI in-process precedence (trail config over app setting) — see
      // [trailPinnedDriverResolution] for why unified pins can only resolve here. A pin naming an
      // unknown driver fails the run loud instead of falling through to the default driver.
      val appConfig = trailblazeDeviceManager.settingsRepo.serverStateFlow.value.appConfig
      val appSettingDriverType = appConfig.selectedTrailblazeDriverTypes[
        trailblazeDeviceId.trailblazeDevicePlatform
      ]
      // A driver that arrives ON the request short-circuits the pin rung below entirely, so it
      // needs its own check — producers stamp it from a trail's `config.driver`, a `--driver` flag,
      // and (for MCP's on-device tool requests) a PERSISTED setting that was runnable when it was
      // saved. Without this, a retired one ships to the device and fails there, where the message
      // cannot say which trail or setting asked for it. Same resolver as the two rungs below.
      val requestedDriverType = runYamlRequest.driverType
      if (requestedDriverType != null) {
        val requestResolution = CliRunDriverResolver.resolve(requestedDriverType)
        if (requestResolution is CliRunDriverResolution.Unrecognized) {
          prefixedProgressMessage("Requested driver rejected: ${requestResolution.message}")
          Console.log("❌ COROUTINE ENDING (unrecognized requested driver) for device: ${trailblazeDeviceId.instanceId}")
          executionResult = TrailExecutionResult.Failed(requestResolution.message, misuse = true)
          onComplete?.invoke(executionResult)
          return@launch
        }
      }
      val trailblazeDriverType = requestedDriverType ?: run {
        val pinResolution = trailPinnedDriverResolution(
          trailYaml = runYamlRequest.yaml,
          deviceClassifiers = deviceClassifiers,
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
        ?: run {
          // The app setting is PERSISTED, so it can name a driver that was runnable when the
          // user chose it and has since been retired. Same check as the pin rung, so a stale
          // setting fails saying what to change instead of resolving to a runtime that is gone.
          when (val settingResolution = CliRunDriverResolver.resolve(appSettingDriverType)) {
            is CliRunDriverResolution.Unrecognized -> {
              prefixedProgressMessage("Configured driver rejected: ${settingResolution.message}")
              Console.log("❌ COROUTINE ENDING (unrecognized configured driver) for device: ${trailblazeDeviceId.instanceId}")
              executionResult = TrailExecutionResult.Failed(settingResolution.message, misuse = true)
              onComplete?.invoke(executionResult)
              return@launch
            }
            is CliRunDriverResolution.Resolved -> settingResolution.driverType
          }
        }
        ?: connectedTrailblazeDevice.trailblazeDriverType

      // The host runner selects its driver from `RunOnHostParams.trailblazeDriverType`, which
      // derives from the device summary — so a host-side pin (e.g. a unified trail pinning iOS
      // Axe over the simulator's default IOS_HOST) is only honored if the device carries the
      // resolved driver. Tag it here; the on-device branches propagate the same value via
      // `runYamlRequest.copy(driverType = …)`. The swap is same-platform (resolution is
      // platform-scoped), so instanceId / platform / deviceId are unchanged.
      val hostRunDevice = connectedTrailblazeDevice.copy(trailblazeDriverType = trailblazeDriverType)
      val deviceLocale = systemLocaleToApply(driverType = trailblazeDriverType, locale = requestedLocale)

      // Which path this run takes. Resolved once here so the multi-device gate below and the
      // `when` that dispatches both read the same answer — the gate used to re-derive the
      // host-agent predicate, which only stayed correct as long as someone kept the two copies
      // in agreement by hand.
      val dispatchPath = DesktopDispatchDecision.decide(
        driverType = trailblazeDriverType,
        preferHostAgent = runYamlRequest.config.preferHostAgent,
        selectedConfigurationName = selectedConfigurationName,
      )
      // Log the inputs alongside the answer: "why did this run take that path" is otherwise only
      // reconstructible by re-deriving the decision from values none of which are logged.
      Console.log(
        "Dispatch path $dispatchPath (driver=$trailblazeDriverType, " +
          "preferHostAgent=${runYamlRequest.config.preferHostAgent})",
      )
      if (TrailheadRuns.reuseEnabledFor(runYamlRequest.config.trailheadReuse)) {
        DesktopDispatchDecision.ignoredTrailheadReuseNotice(dispatchPath, trailblazeDriverType)
          ?.let { prefixedProgressMessage("⚠️ $it") }
      }

      // Multi-device configurations are wired into exactly one dispatch path: the host agent
      // dispatching to each device (Android over RPC, iOS simulators host-side). Every other path
      // (the on-device agent, web/Compose host runs) has no companion connect, no device bindings, and no
      // per-device routing — it would run a multi-device trail against the launch device alone and
      // report success, with the configuration-keyed steps quietly falling through to the LLM.
      // Reject up front, naming the change that would make this run dispatchable.
      if (DesktopDispatchDecision.refusesMultiDeviceRun(dispatchPath, selectedConfigurationName)) {
        val remedy = DesktopDispatchDecision.multiDeviceRemedy(dispatchPath, trailblazeDriverType)
        val message = "This run binds the trail's multi-device configuration " +
          "'$selectedConfigurationName', which only runs on the multi-device host agent. This " +
          "run resolved driver $trailblazeDriverType, " +
          "preferHostAgent=" +
          "${runYamlRequest.config.preferHostAgent} — running it here would silently use the " +
          "launch device only. $remedy"
        prefixedProgressMessage(message)
        Console.log("❌ COROUTINE ENDING (multi-device trail on an unsupported dispatch path)")
        executionResult = TrailExecutionResult.Failed(message, misuse = true)
        onComplete?.invoke(executionResult)
        return@launch
      }
      if (
        DesktopDispatchDecision.refusesContinuedBrowserLaunch(
          driverType = trailblazeDriverType,
          selectedConfigurationName = selectedConfigurationName,
          startsSession = runYamlRequest.config.sendSessionStartLog,
        )
      ) {
        val message = "This call continues an open session, but a browser launches its " +
          "multi-device configuration '$selectedConfigurationName', which runs one call per " +
          "session. Start a new session for this call."
        prefixedProgressMessage(message)
        Console.log("❌ COROUTINE ENDING (continued browser-launched multi-device session)")
        executionResult = TrailExecutionResult.Failed(message, misuse = true)
        onComplete?.invoke(executionResult)
        return@launch
      }

      // Per-session video / logcat capture used to be started here against a
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
      // not always the first-declared one).
      val appIdsForCapture = targetTestApp
        ?.getPossibleAppIdsForPlatform(trailblazeDeviceId.trailblazeDevicePlatform)
        .orEmpty()
      // Capture samples ONE app, so the declared list has to be narrowed to the one this device
      // actually has — taking the first declared id scoped capture to an app that is not there,
      // and a reading against an absent package is quiet: it just reports the app as not running.
      // `lazy` because the answer costs a device probe, and a run whose capture never starts
      // (`--no-logging`) should not pay for it.
      val appIdForCapture: String? by lazy {
        trailblazeDeviceManager.resolveCaptureAppId(
          trailblazeDeviceId = trailblazeDeviceId,
          target = targetTestApp,
          candidateAppIds = appIdsForCapture,
        )
      }
      // Resolve per-run capture toggles in the same order the pre-coordinator flow did:
      // request-level overrides (CLI `--capture-video` / `--capture-logcat`) > daemon
      // appConfig toggles > built-in defaults. Passed to `coordinator.startForSession`
      // below so the user-visible CLI flag actually takes effect. Video's appConfig tier
      // (`trailblaze config capture-video`) defaults OFF, unlike the log streams, and its
      // null stays null so `hostCaptureOptions` can rank the env var between the two.
      val captureOptionsForRun = xyz.block.trailblaze.capture.CaptureOptions.hostCaptureOptions(
        captureVideo = desktopAppRunYamlParams.captureVideo,
        persistedCaptureVideo = appConfig.captureVideo,
        captureLogcat = desktopAppRunYamlParams.captureLogcat ?: appConfig.captureLogcat,
        captureIosLogs = desktopAppRunYamlParams.captureIosLogs ?: appConfig.captureIosLogs,
        captureMemory = desktopAppRunYamlParams.captureMemory ?: appConfig.captureMemory,
      )

      var sessionId: SessionId? = null
      // The teardown stored its hand-off while the run still read as passing; a run that ends
      // failed must not hand that setup to the next trail. Only for the session this run knows is
      // its own: a session found by device match may be a concurrent run's on the same device.
      fun dropOwnHandoffs() {
        (sessionId ?: startedSessionId)?.let { TrailheadHandoffStore.dropSession(it) }
      }
      val endsSession = DesktopDispatchDecision.endsSessionOnReturn(
        driverType = trailblazeDriverType,
        selectedConfigurationName = selectedConfigurationName,
        sendsSessionEndLog = runYamlRequest.config.sendSessionEndLog,
      )
      val reservedSessionToEnd = reservedSessionEndedOnReturn(runYamlRequest, endsSession)
      // Every device a multi-device configuration bound, populated by the multi-device branch
      // BEFORE the runner starts the session so `captureSessionStarted` can arm one capture bridge
      // per device instead of only the launch device's. Empty for a single-device run.
      var captureDeviceBindings: List<CaptureDeviceBinding> = emptyList()
      // Snapshot existing session IDs so we can find newly created ones on cancellation
      val preExistingSessionIds = trailblazeDeviceManager.logsRepo.getSessionIds().toSet()
      // Read before this run's session exists: once it does, the device's pointer moves to it and
      // the session it replaced (e.g. an interactive CLI session) can no longer be found.
      val sessionBeforeRun = trailblazeDeviceManager.getCurrentSessionIdForDevice(trailblazeDeviceId)

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

      // Once per run: the Android paths release from `captureSessionStarted`, and the host-driver
      // paths that never fire it (Playwright, Compose, Revyl) release once their session id is known.
      val replacedSessionReleased = java.util.concurrent.atomic.AtomicBoolean(false)
      val releaseReplacedSession: (SessionId) -> Unit = { sid ->
        if (replacedSessionReleased.compareAndSet(false, true)) {
          trailblazeDeviceManager.releaseReplacedSession(trailblazeDeviceId, sessionBeforeRun, sid, runInFlight)
        }
      }
      try {
        // Interruptible: a cancel landing while iOS waits for SpringBoard stops the wait, so a
        // cancelled run cannot go on to stop the app of the run that replaced it.
        val localeChanged = deviceLocale?.let {
          runInterruptible { DeviceLocaleConfigurator.applyForTrail(trailblazeDeviceId, it) }
        } == true
        // The app restarts below, so a setup a trailhead kept in the old process is gone with it.
        if (localeChanged) TrailheadHandoffStore.dropDevice(trailblazeDeviceId)

        // Only a trail whose trailhead will take the reusable hand-off skips the clean start. A
        // trail that can't be read here keeps it; the run's own decode reports the error.
        val keepForReuse = TrailheadRuns.reuseEnabledFor(runYamlRequest.config.trailheadReuse) &&
          runCatching {
            createTrailblazeYaml().decodeTrail(
              yaml = runYamlRequest.yaml,
              deviceClassifiers = deviceClassifiers,
              selectedDeviceConfiguration = selectedConfigurationName,
            )
          }.getOrNull()?.let { TrailheadHandoffStore.holdsReusableFor(trailblazeDeviceId, it) } == true
        var targetAppStopped = false
        if (shouldForceStopTargetApp(forceStopTargetApp, localeChanged, keepForReuse)) {
          // Stop the app this device will actually run. A locale change forces a fresh process even
          // when the caller did not request the usual clean start, or the app could keep rendering
          // with the old language.
          val startDeviceTarget = MultiDeviceConfigurationResolver
            .startDeviceTargetId(
              yaml = runYamlRequest.yaml,
              selectedConfigurationName = selectedConfigurationName,
            )
            ?.let { desktopAppRunYamlParams.findTargetById?.invoke(it) }
            ?: targetTestApp
          val possibleAppIds = startDeviceTarget
            ?.getPossibleAppIdsForPlatform(trailblazeDeviceId.trailblazeDevicePlatform)
            ?.toSet()
            ?: emptySet()
          targetAppStopped = MobileDeviceUtils.ensureAppsAreForceStopped(possibleAppIds, trailblazeDeviceId)
        }
        // A run with no target app stopped nothing, so the restart stays owed to the next run that has one.
        if (localeChanged && targetAppStopped) DeviceLocaleConfigurator.cleanupDone(trailblazeDeviceId)

        trailblazeAnalytics.runTest(trailblazeDriverType, desktopAppRunYamlParams)
        prefixedProgressMessage(
          "Starting ${trailblazeDeviceId.trailblazeDevicePlatform.displayName} test on device ${trailblazeDeviceId.instanceId} with driver type $trailblazeDriverType",
        )

        val trailblazeHostAppTarget = trailblazeHostAppTargetProvider()

        // Capture-aware onSessionStarted callback shared across the Android dispatch
        // branches. Each branch knows the resolved
        // session id at a slightly different point in its flow; this lambda lets them all
        // converge on the same activator wiring without duplicating the `runCatching` /
        // `maybeStartAndroidNetworkCapture` plumbing.
        val captureSessionStarted: (SessionId) -> Unit = { sid ->
          startedSessionId = sid
          // First, so the replaced session restores the animation settings it changed before this
          // session records them as the device's defaults.
          releaseReplacedSession(sid)
          pendingAdvisories.logTo(sid)
          // Every capture writer below lands its artifacts in the session directory, and each
          // creates that directory itself rather than going through the run's LogsRepo — so the
          // read-only repo a `--no-logging` run installs cannot suppress them. Skip the wiring
          // outright, or the flag's "no session files" contract holds for the session JSON while
          // video / logcat / network capture keep writing beside it.
          if (!desktopAppRunYamlParams.noLogging) {
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
              // Asked for only when a stream will use the answer. `appIdForCapture` is lazy
              // because resolving it can ask the device what is installed, and passing it as a
              // value forces it — so a run with every stream off still paid for a probe whose
              // result the coordinator was about to decline.
              appId = if (captureOptionsForRun.hasAnyCaptureEnabled) appIdForCapture else null,
              // A browser-launched multi-device session runs no single-device web runner to record it.
              followWebScreencast = captureDeviceBindings.isNotEmpty(),
            )
            // Every display a multi-device configuration bound gets its own recording, so a step
            // that ran on a companion is shown on that companion's screen rather than on the
            // start device sitting still. Whole roster, start device included: the coordinator
            // names the recording it already runs from the entry that matches its device and
            // records the rest. Empty for a single-device run.
            if (captureDeviceBindings.isNotEmpty()) {
              trailblazeDeviceManager.sessionCaptureCoordinator.bindDevices(
                sessionId = sid,
                devices = captureDeviceBindings.map { binding ->
                  SessionCaptureCoordinator.BoundDevice(
                    name = binding.name,
                    deviceId = binding.deviceId,
                    // Resolved against what the device has, as `appIdForCapture` is: a buyer
                    // display runs the target's last declared id, and crashes are attributed by
                    // package.
                    appId = { trailblazeDeviceManager.resolveCaptureAppId(binding.deviceId, binding.target, binding.targetAppIds) },
                  )
                },
              )
            }
            maybeStartAndroidNetworkCapture(
              runYamlRequest = runYamlRequest,
              deviceId = trailblazeDeviceId,
              sessionIdOverride = sid,
              targetTestApp = targetTestApp,
              unresolvedDeclaredTarget = desktopAppRunYamlParams.unresolvedDeclaredTarget,
              onProgressMessage = prefixedProgressMessage,
              deviceBindings = captureDeviceBindings,
            )
          }
          // Outside the logging guard on purpose: this writes no session artifacts, it changes
          // an OS setting to steady trail timing. Gating it on --no-logging would silently
          // ignore a separately requested optimization and make the same trail run differently.
          // Experimental opt-in (gated internally, idempotent — the MCP path may have already
          // fired it at session-resolution time).
          SessionAnimationDisabler.startForSession(sid, trailblazeDeviceId)
          // Same placement rationale — the first point that knows both the device AND which app
          // the session will drive — but on by default (kill switch inside): makes sure the app
          // has compiled ART artifacts so its cold starts do not re-verify the APK. Idempotent,
          // so the MCP path having fired it at session resolution costs nothing here.
          //
          // One call per resolved device binding, not just the launch device: a multi-device
          // session's companions are the same kind of Android device and pay the same cold-start
          // tax, and captureDeviceBindings already carries the launch device (as its first entry)
          // plus every companion once the multi-device branch has resolved them. Empty for a
          // single-device run, where the launch device is the only display to check.
          (
            captureDeviceBindings.ifEmpty {
              listOf(CaptureDeviceBinding(name = "", deviceId = trailblazeDeviceId, targetAppIds = appIdsForCapture))
            }
          ).forEach { binding ->
            SessionAppCompileEnsurer.startForSession(
              sessionId = sid.toString(),
              deviceId = binding.deviceId,
              candidateAppIds = binding.targetAppIds,
              // The fallback app when the trail's declared target did not resolve is not the app
              // the trail drives; compiling it would be time spent on nothing the run launches.
              // Asked per device, because a multi-device member that declared its own `target:`
              // did resolve one.
              unresolvedDeclaredTarget = unresolvedTargetForDevice(
                sessionUnresolvedTarget = desktopAppRunYamlParams.unresolvedDeclaredTarget,
                deviceHasOwnTarget = binding.hasOwnTarget,
              ),
            )
          }
          // Same placement rationale, and the same experimental opt-in shape: this is the first
          // point that knows both the device AND which app the session will drive, which a device
          // connect does not.
          SessionTurboAttacher.startForSession(
            sessionId = sid.toString(),
            deviceId = trailblazeDeviceId,
            candidateAppIds = appIdsForCapture,
            runOverride = desktopAppRunYamlParams.turbo,
            // `appIdsForCapture` came from the workspace fallback when the trail's declared target
            // did not resolve; turbo must not attach to that app and call the run turbo. Session
            // resolution may have already attached to that fallback — this is the only call site
            // that knows better, so the attacher lets a decline overrule it.
            unresolvedDeclaredTarget = desktopAppRunYamlParams.unresolvedDeclaredTarget,
          )
        }

        sessionId = when (dispatchPath) {
          // In-process host agent, for every driver whose tools do not run on the device: web
          // (Playwright), Electron, Revyl (Android + iOS), Compose, and local Maestro, all via
          // [TrailblazeHostYamlRunner.runHostYaml]. Prompt steps run through
          // [KoogStrategyGraphAgent.createInProcess] against a Trailblaze-owned `ToolRegistry`, so
          // there is no MCP self-connection and none of the re-entrancy deadlock that pattern
          // caused.
          //
          // Android on-device drivers (instrumentation/accessibility) never land here: they need
          // the device attached via the on-device RPC server, which `runHostYaml` can't provide.
          // They take one of the two RPC branches below.
          DispatchPath.HOST_IN_PROCESS_KOOG -> {
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
                keepDeviceLanguage = desktopAppRunYamlParams.keepDeviceLanguage,
                // Thread the resolved video toggle to the web / Electron rules, which
                // self-instrument capture (the coordinator skips WEB) and would otherwise
                // ignore `--no-capture-video`.
                captureVideo = captureOptionsForRun.captureVideo,
                snapshotBaselineRef = desktopAppRunYamlParams.snapshotBaselineRef,
                snapshotBaselineThresholdPercent = desktopAppRunYamlParams.snapshotBaselineThresholdPercent,
              ),
              deviceManager = trailblazeDeviceManager,
              logsDir = logsDirProvider(),
            )
            lastToolResult = hostResult.lastToolResult

            // Mirror the neighboring branches' session/connection bookkeeping: fire the
            // capture activator for the resolved session and report instrumentation-running.
            // The run's own cleanup has usually stopped this session's capture by now; the
            // coordinator refuses to start a stopped session again, so this cannot record a
            // teardown clip over the real one.
            hostResult.sessionId?.let { captureSessionStarted(it) }
            onConnectionStatus(
              DeviceConnectionStatus.WithTargetDevice.TrailblazeInstrumentationRunning(
                trailblazeDeviceId = connectedTrailblazeDevice.trailblazeDeviceId,
              ),
            )
            hostResult.sessionId
          }

          // Host agent with on-device driver (accessibility or instrumentation): run the
          // agent loop on the host JVM, send individual tool calls to the device via RPC.
          // The device executes each tool using whichever driver is selected.
          //
          // This is the `preferHostAgent` path: it keeps the agent's reasoning loop (and its
          // growing history) on the host instead of the device — useful when device memory
          // pressure is a concern.
          //
          // Drivers that declare `hostAgentDispatchable = false` (ANDROID_TEST) never land here:
          // this branch builds a dynamic LLM client and delegates unrecorded and self-heal steps
          // to it, and that driver exists to be a merge-blocking gate whose on-device runner fails
          // an unrecorded step BY NAME rather than improvising. They fall through to the
          // deterministic on-device envelope branch below, which is the only way they should ever
          // execute.
          //
          // This is the only branch wired for multi-device companions — see the gate above, which
          // reads the same resolved path. A multi-device session launched on an iOS simulator
          // lands here too, with no on-device runner to resolve for its launch device.
          DispatchPath.HOST_AGENT_OVER_ONDEVICE_RPC -> {
            val androidLaunch = trailblazeDeviceId.trailblazeDevicePlatform == TrailblazeDevicePlatform.ANDROID
            val trailblazeOnDeviceInstrumentationTarget = if (androidLaunch) {
              resolveOnDeviceInstrumentationTarget(
                driverType = trailblazeDriverType,
                targetTestApp = targetTestApp,
                trailblazeHostAppTarget = trailblazeHostAppTarget,
              )
            } else {
              null
            }
            if (androidLaunch && trailblazeOnDeviceInstrumentationTarget == null) {
              val reason = (targetTestApp ?: trailblazeHostAppTarget).missingInProcessHarnessMessage()
              prefixedProgressMessage(reason)
              executionResult = TrailExecutionResult.Failed(reason)
              null
            } else {
              runHostAgentWithOnDeviceRpc(
                runTargetApp = targetTestApp ?: trailblazeHostAppTarget,
                dynamicLlmClient = dynamicLlmClientProvider(runYamlRequest.trailblazeLlmModel),
                runYamlRequest = runYamlRequest.copy(
                  driverType = trailblazeDriverType,
                  config = runYamlRequest.config.copy(sendSessionEndLog = endsSession),
                ),
                connectedTrailblazeDevice = connectedTrailblazeDevice,
                trailblazeOnDeviceInstrumentationTarget = trailblazeOnDeviceInstrumentationTarget,
                onProgressMessage = prefixedProgressMessage,
                onConnectionStatus = onConnectionStatus,
                additionalInstrumentationArgs = additionalInstrumentationArgs,
                targetTestApp = targetTestApp,
                onSessionStarted = captureSessionStarted,
                findTargetById = desktopAppRunYamlParams.findTargetById,
                onCaptureDeviceBindingsResolved = { captureDeviceBindings = it },
                noLogging = desktopAppRunYamlParams.noLogging,
              )
            }
          }

          // On-device agent: send entire YAML to device, agent loop runs on-device. The request
          // goes to the device's RunYamlRequestHandler, which runs the agent in-process via
          // AndroidTrailblazeRule.
          //
          // Reached when `preferHostAgent = false`, or for a driver that is reachable over the
          // on-device RPC but not host-agent dispatchable (ANDROID_TEST). NOT the default for the
          // accessibility / instrumentation drivers: `preferHostAgent` defaults true, so those
          // route to HOST_AGENT_OVER_ONDEVICE_RPC above. Note for capture: `ToolCallObservers` has
          // no cross-process transport, so a run on this path produces no tool-boundary memory
          // events — only the periodic samples and the start/end bookends.
          DispatchPath.ON_DEVICE_AGENT -> {
            val trailblazeOnDeviceInstrumentationTarget = resolveOnDeviceInstrumentationTarget(
              driverType = trailblazeDriverType,
              targetTestApp = targetTestApp,
              trailblazeHostAppTarget = trailblazeHostAppTarget,
            )
            if (trailblazeOnDeviceInstrumentationTarget == null) {
              val reason = (targetTestApp ?: trailblazeHostAppTarget).missingInProcessHarnessMessage()
              prefixedProgressMessage(reason)
              executionResult = TrailExecutionResult.Failed(reason)
              null
            } else {
              val onDeviceRpc = OnDeviceRpcClient(
                trailblazeDeviceId = trailblazeDeviceId,
                sendProgressMessage = prefixedProgressMessage,
                // Arm at the single chokepoint every synchronous on-device RPC flows through, so a
                // wedge surfacing on ANY path (including the `launchApp` pre-action, which the
                // session-status detection can't see) force-restarts the shared server next trail.
                onNonRecoverableWedge = { armWedgedDevice(trailblazeDeviceId) },
                wireTransportMode = AndroidWireTransport.modeFor(trailblazeDriverType),
              )

              // Set driver type on request so the on-device server knows which driver to use
              val requestWithDriverType = runYamlRequest.copy(driverType = trailblazeDriverType)

              runYamlOnDevice(
                runTargetApp = targetTestApp ?: trailblazeHostAppTarget,
                onDeviceRpc = onDeviceRpc,
                trailblazeConnectedDevice = connectedTrailblazeDevice,
                trailblazeOnDeviceInstrumentationTarget = trailblazeOnDeviceInstrumentationTarget,
                runYamlRequest = requestWithDriverType,
                onProgressMessage = prefixedProgressMessage,
                onConnectionStatus = onConnectionStatus,
                additionalInstrumentationArgs = additionalInstrumentationArgs,
                onSessionStarted = captureSessionStarted,
                onSessionAbandoned = { sid ->
                  finalizeHostSessionResources(
                    listOf(sid),
                    trailblazeDeviceManager.sessionCaptureCoordinator::stopForSession,
                  )
                },
              )
            }
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
      } catch (t: Throwable) {
        val failureMessage = t.message ?: t::class.java.simpleName
        Console.log("⚠️ EXCEPTION in coroutine for device ${trailblazeDeviceId.instanceId}: ${t::class.java.simpleName} - $failureMessage")
        // Full stack trace to the daemon log so the throw site is diagnosable — the one-line
        // message alone hid which internal call actually failed (e.g. a decodeTrail deep in the
        // dispatch path vs. a device-connect IOException).
        Console.log(t.stackTraceToString())
        if (t is VirtualMachineError || t is ThreadDeath) {
          processFatalFailure = t
        } else {
          prefixedProgressMessage("Error: $failureMessage")
          executionResult = TrailExecutionResult.Failed(failureMessage)
          // Before the connection failure below releases whoever waits on this run: capture stop
          // can outlast their wait, so the drop in `finally` may come after the next trail's
          // trailhead has already taken the hand-off.
          dropOwnHandoffs()
          try {
            onConnectionStatus(
              DeviceConnectionStatus.DeviceConnectionError.ConnectionFailure(
                errorMessage = t.message ?: "Unknown error",
              ),
            )
          } catch (classLoadError: Throwable) {
            // Fallback if ConnectionFailure class fails to load (Kotlin multiplatform classloading issue)
            Console.log("⚠️ Failed to create ConnectionFailure instance: ${classLoadError::class.simpleName} - ${classLoadError.message}")
          }
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
          ?: reservedSessionToEnd
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
          val captureAction = runEndCaptureAction(
            sendsSessionEndLog = endsSession,
            ownsSessionEnd = sessionId != null || reservedSessionToEnd != null || deviceMatched,
          )
          val finalizerFailure = when (captureAction) {
            RunEndCaptureAction.FINALIZE -> runCatching {
              finalizeHostSessionResources(
                listOf(resolvedSessionId),
                trailblazeDeviceManager.sessionCaptureCoordinator::stopForSession,
              )
            }.exceptionOrNull()
            RunEndCaptureAction.STOP -> {
              // Not ours for certain (the bare fallback above), so do not mark it ended: that would
              // refuse the capture start of a concurrent run whose session this guessed.
              trailblazeDeviceManager.sessionCaptureCoordinator.stopForSession(resolvedSessionId, markEnded = false)
              null
            }
            RunEndCaptureAction.LEAVE_RUNNING -> null
          }
          if (finalizerFailure != null && executionResult is TrailExecutionResult.Success) {
            // The session on disk already says it passed; it is failed too, before onComplete, so
            // the report reads the same failure. The flag keeps the daemon's disk-based verdict
            // from passing the run when that write could not land.
            executionResult = TrailExecutionResult.Failed(
              trailblazeDeviceManager.failSucceededSessionOnFinalization(resolvedSessionId, finalizerFailure),
              sessionFinalizationFailed = true,
            )
          }
          // The crash index is written as capture stops, so it is complete only now. A run that
          // already failed keeps its own failure; its crash still shows on the timeline.
          if (captureAction == RunEndCaptureAction.FINALIZE && executionResult is TrailExecutionResult.Success) {
            trailblazeDeviceManager.failSucceededSessionIfAppCrashed(resolvedSessionId)?.let { crashMessage ->
              Console.log("💥 $crashMessage")
              executionResult = TrailExecutionResult.Failed(crashMessage, sessionFinalizationFailed = true)
            }
          }
          // The caller expects its session to go on, so the device must drop it before replying:
          // a next call that still found it would continue it.
          if (captureAction == RunEndCaptureAction.FINALIZE && !runYamlRequest.config.sendSessionEndLog) {
            trailblazeDeviceManager.clearEndedSessionFromDevice(trailblazeDeviceId, resolvedSessionId)
          }
        }
        // Again for failures found only now, as capture stops (a finalizer, an app crash).
        if (processFatalFailure != null || executionResult !is TrailExecutionResult.Success) {
          dropOwnHandoffs()
        }
        // After this run's own capture stop: ending the device's last run releases the sessions a
        // new session replaced while this one ran, which may include this run's own.
        trailblazeDeviceManager.endRun(runInFlight)
        Console.log("🏁 COROUTINE FINISHED (finally block) for device: ${trailblazeDeviceId.instanceId}")
        val fatalFailure = processFatalFailure
        if (fatalFailure == null) {
          onComplete?.invoke(executionResult)
        } else {
          val failureMessage = fatalFailure.message ?: fatalFailure::class.java.simpleName
          val fatalExecutionResult = TrailExecutionResult.Failed(failureMessage)
          executionResult = fatalExecutionResult
          // Callback-only awaiters (the MCP bridge and RPC handler) need a terminal result. The
          // rethrow then delivers the fatal error to the coroutine scope instead of normalizing it.
          completeProcessFatalFailure(fatalFailure, fatalExecutionResult, onComplete)
        }
        // Re-throw CancellationException to properly propagate cancellation
        if (executionResult is TrailExecutionResult.Cancelled) {
          throw CancellationException("Test cancelled for device ${trailblazeDeviceId.instanceId}")
        }
      }
    }
    trailJob.invokeOnCompletion {
      // A coroutine cancelled before it started, or a `finally` that threw before its end, never ends the run.
      trailblazeDeviceManager.endRun(runInFlight)
    }
    return trailJob
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
    /**
     * The app target THIS connect is for — the run's resolved `config.target` on the launch device,
     * the companion's own target on a companion device. Required and non-null so a new dispatch
     * path can neither fall back to the daemon selection nor pass nothing at all.
     *
     * Must be the SAME target [resolveOnDeviceInstrumentationTarget] chose the runner from, i.e.
     * `targetTestApp ?: trailblazeHostAppTarget`. Passing the bare nullable `targetTestApp` instead
     * would, on a run that declares no `config.target`, instrument a harness resolved from the
     * session target while unioning in nothing — the harness stopped and the harness launched
     * coming from two different targets is exactly the bug this union is meant to close.
     */
    runTargetApp: TrailblazeHostAppTarget,
  ): ReadyOnDeviceConnection {
    // Step 1: connect (install/reuse) and enable accessibility if needed. Any IOException in
    // here is an infrastructure-level failure (ADB, instrumentation launch, APK install) that
    // a `forceRestart` retry would just repeat — so we let it propagate rather than hiding it
    // behind a misleading "readiness probe failed" log.
    suspend fun doConnectAndEnable(forceRestart: Boolean): DeviceConnectionStatus {
      val status = HostAndroidDeviceConnectUtils.connectToInstrumentationAndInstallAppIfNotAvailable(
        sendProgressMessage = onProgressMessage,
        deviceId = trailblazeDeviceId,
        trailblazeOnDeviceInstrumentationTarget = trailblazeOnDeviceInstrumentationTarget,
        // The daemon's resolved HTTPS port, so the adb reverse and the device's log POSTs both
        // target the port this process actually bound — under TRAILBLAZE_PORT=52521 the HTTPS
        // server is on 52522, and the old defaulted 52526 reverse fed every log into a dead
        // socket ("no progress for 600s" while the run executed fine on-device).
        httpsPort = trailblazeDeviceManager.settingsRepo.portManager.httpsPort,
        additionalInstrumentationArgs = additionalInstrumentationArgs,
        forceRestart = forceRestart,
        // Every harness THIS RUN's target can present, not just the one being launched — see
        // HostAndroidDeviceConnectUtils.planConnectForceStop for why, and for what it costs.
        additionalForceStopTargets = runTargetApp.allInstrumentationTargets().toSet(),
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

    // A connect that reports its own failure is terminal: nothing was launched, so probing
    // readiness can only burn the full budget and report a timeout, and the force-restart retry
    // re-runs the identical failing step and burns a second one. Worse, it replaces an error that
    // names the fix ("test APK X is not installed — build and install it") with a generic
    // readiness timeout. Fail here with the message the connect step produced.
    fun DeviceConnectionStatus.failFastIfTerminal() {
      if (this is DeviceConnectionStatus.DeviceConnectionError) {
        throw TrailblazeException(
          (this as? DeviceConnectionStatus.DeviceConnectionError.ConnectionFailure)?.errorMessage
            ?: statusText,
        )
      }
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
    initialStatus.failFastIfTerminal()
    if (recoverFromPriorWedge) {
      val readiness = onDeviceRpc.waitForReady(
        requireAndroidAccessibilityService = requireAndroidAccessibilityService,
      )
      wedgedDeviceIds -= trailblazeDeviceId
      return ReadyOnDeviceConnection(initialStatus, readiness)
    }

    // Step 2: readiness probe. This is the specific failure mode we retry — the instrumentation
    // process is alive (so `isAppRunning` returned true and `forceRestart=false` reused it) but
    // the HTTP server inside it is stuck or dead. Force-restart reinstalls the APK and relaunches
    // instrumentation, which is the only thing that actually recovers a zombie. The common path
    // pays nothing for this fallback: the first `waitForReady` succeeds in ms on a warm device.
    return try {
      val readiness = onDeviceRpc.waitForReady(
        requireAndroidAccessibilityService = requireAndroidAccessibilityService,
      )
      ReadyOnDeviceConnection(initialStatus, readiness)
    } catch (e: IOException) {
      onProgressMessage(
        "Device readiness probe failed (${e.message}); force-restarting instrumentation and retrying once.",
      )
      val restartedStatus = doConnectAndEnable(forceRestart = true)
      restartedStatus.failFastIfTerminal()
      val readiness = onDeviceRpc.waitForReady(
        requireAndroidAccessibilityService = requireAndroidAccessibilityService,
      )
      ReadyOnDeviceConnection(restartedStatus, readiness)
    }
  }

  /**
   * What [connectAndEnsureReady] hands back: the connect step's status plus the `GetScreenState`
   * answer that proved the runner ready. The answer is kept because it is the runner describing
   * itself ([GetScreenStateResponse.runnerCapabilities]); the launch path reads the device
   * classifier override capability from it rather than probing again.
   */
  private data class ReadyOnDeviceConnection(
    val status: DeviceConnectionStatus,
    val readiness: GetScreenStateResponse,
  )

  /**
   * Runs the agent on the host, dispatching each tool to its device: an Android device's over its
   * on-device RPC driver, an iOS simulator's host-side. Connects the launch device and, for a
   * multi-device configuration, every companion first.
   */
  private suspend fun runHostAgentWithOnDeviceRpc(
    dynamicLlmClient: DynamicLlmClient,
    runYamlRequest: RunYamlRequest,
    connectedTrailblazeDevice: TrailblazeConnectedDeviceSummary,
    /** The launch device's on-device runner; null when the launch device is not Android. */
    trailblazeOnDeviceInstrumentationTarget: TrailblazeOnDeviceInstrumentationTarget?,
    onProgressMessage: (String) -> Unit,
    onConnectionStatus: (DeviceConnectionStatus) -> Unit,
    additionalInstrumentationArgs: Map<String, String>,
    targetTestApp: TrailblazeHostAppTarget?,
    /**
     * The run's effective target — `targetTestApp ?: trailblazeHostAppTarget`, the same expression
     * [resolveOnDeviceInstrumentationTarget] picks the runner from. See [connectAndEnsureReady].
     */
    runTargetApp: TrailblazeHostAppTarget,
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
    /** The run's `--no-logging` flag: no session files, no trace export. */
    noLogging: Boolean = false,
  ): SessionId? {
    return withContext(Dispatchers.IO) {
      val launchDeviceId = connectedTrailblazeDevice.trailblazeDeviceId
      val launchDriver = runYamlRequest.driverType ?: TrailblazeDriverType.DEFAULT_ANDROID
      // Closed in this method's finally: one leaked RPC connection or simulator hold per
      // multi-device run would otherwise accumulate for the daemon's lifetime.
      val companionRpcClients = mutableListOf<OnDeviceRpcClient>()
      val boundIosDevices = mutableListOf<TrailblazeConnectedDevice>()

      suspend fun connectAndroid(
        deviceId: TrailblazeDeviceId,
        driverType: TrailblazeDriverType,
        instrumentationTarget: TrailblazeOnDeviceInstrumentationTarget,
        deviceRunTarget: TrailblazeHostAppTarget,
      ): Pair<DeviceConnectionStatus, OnDeviceRpcClient> {
        val rpcClient = OnDeviceRpcClient(
          trailblazeDeviceId = deviceId,
          sendProgressMessage = onProgressMessage,
          // Arm at the single chokepoint every synchronous on-device RPC flows through, so a
          // wedge surfacing on ANY path (including the `launchApp` pre-action, which the
          // session-status detection can't see) force-restarts the shared server next trail.
          onNonRecoverableWedge = { armWedgedDevice(deviceId) },
          wireTransportMode = AndroidWireTransport.modeFor(driverType),
        )
        val needsAccessibility = AndroidAccessibilityServiceDrivers.includes(driverType)
        val (status, _) = try {
          connectAndEnsureReady(
            onDeviceRpc = rpcClient,
            trailblazeDeviceId = deviceId,
            trailblazeOnDeviceInstrumentationTarget = instrumentationTarget,
            additionalInstrumentationArgs = additionalInstrumentationArgs,
            onProgressMessage = onProgressMessage,
            enableAccessibility = needsAccessibility,
            requireAndroidAccessibilityService = needsAccessibility,
            runTargetApp = deviceRunTarget,
          )
        } catch (t: Throwable) {
          // On no cleanup list until this returns, so a failed or cancelled warm-up closes it here.
          runCatching { rpcClient.close() }
          throw t
        }
        return status to rpcClient
      }

      suspend fun connectIos(
        name: String,
        deviceId: TrailblazeDeviceId,
        driverType: TrailblazeDriverType,
        target: TrailblazeHostAppTarget?,
      ): TrailblazeConnectedDevice =
        (
          TrailblazeDeviceService.connectDevice(deviceId, driverType, target)
            ?: throw TrailblazeException(
              "Device '$name' is bound to iOS simulator ${deviceId.instanceId}, but $driverType " +
                "could not connect to it. Check the simulator is booted" +
                (if (driverType == TrailblazeDriverType.IOS_AXE) " and the AXe CLI is installed." else "."),
            )
          ).also { boundIosDevices += it }

      try {
        val androidLaunch = trailblazeOnDeviceInstrumentationTarget?.let { instrumentationTarget ->
          connectAndroid(launchDeviceId, launchDriver, instrumentationTarget, runTargetApp)
        }

        // Multi-device: a unified trail may declare a `config.devices:` configuration entry (an
        // entry with an inner `devices:` map of named devices). Resolve it to concrete devices —
        // the launch device IS the first declared name — and connect every remaining named device.
        val resolvedConfiguration = resolveMultiDeviceConfiguration(
          runYamlRequest = runYamlRequest,
          primaryDeviceId = launchDeviceId,
        )
        // Each device runs its own `target:` when the configuration declares one, else the session
        // target. Resolved BEFORE any companion connects so an unknown target id fails the run
        // before it boots devices for a session that could never have been correct.
        val memberTargets = resolvedConfiguration?.let { resolved ->
          MultiDeviceConfigurationResolver.resolveMemberTargets(
            configurationName = resolved.configurationName,
            memberTargetIds = resolved.memberTargetIds,
            sessionTarget = targetTestApp,
            findTargetById = findTargetById,
          )
        }
        val launchTarget = resolvedConfiguration?.let { memberTargets?.get(it.startDeviceName) } ?: targetTestApp
        val launchName = resolvedConfiguration?.startDeviceName ?: launchDeviceId.instanceId
        val launchDevice: TrailblazeHostYamlRunner.DeviceConnection = when {
          androidLaunch != null -> TrailblazeHostYamlRunner.DeviceConnection.AndroidRpc(
            trailblazeDeviceId = launchDeviceId,
            rpcClient = androidLaunch.second,
            driverType = launchDriver,
            targetTestApp = launchTarget,
          )
          launchDeviceId.trailblazeDevicePlatform == TrailblazeDevicePlatform.WEB ->
            TrailblazeHostYamlRunner.DeviceConnection.WebBrowser(
              trailblazeDeviceId = launchDeviceId,
              pageManager = bindWebBrowser(launchDeviceId, launchName, runYamlRequest.config.browserHeadless, onProgressMessage),
              targetTestApp = launchTarget,
            )
          else -> TrailblazeHostYamlRunner.DeviceConnection.IosSimulator(
            trailblazeDeviceId = launchDeviceId,
            connectedDevice = connectIos(
              name = launchName,
              deviceId = launchDeviceId,
              driverType = launchDriver,
              target = launchTarget,
            ),
            targetTestApp = launchTarget,
          )
        }
        val launchStatus = androidLaunch?.first
          ?: DeviceConnectionStatus.WithTargetDevice.TrailblazeInstrumentationRunning(trailblazeDeviceId = launchDeviceId)

        val multiDeviceSession = resolvedConfiguration?.let { resolved ->
          onProgressMessage(
            "Multi-device configuration '${resolved.configurationName}': start device " +
              "'${resolved.startDeviceName}' is ${launchDeviceId.instanceId}",
          )
          // Android companions connect over the on-device RPC transport; WEB companions are
          // host-owned Playwright browsers and IOS companions host-driven simulators, neither over
          // RPC. Any other platform has no companion transport — reject it by name here rather than
          // surfacing an inscrutable adb failure.
          val unsupportedCompanions = resolved.companionDeviceIds.filterValues {
            it.trailblazeDevicePlatform !in COMPANION_PLATFORMS
          }
          if (unsupportedCompanions.isNotEmpty()) {
            throw TrailblazeException(
              "Configuration '${resolved.configurationName}' casts companion devices on platforms " +
                "with no companion transport: " +
                unsupportedCompanions.entries.joinToString { (name, id) ->
                  "'$name' (${id.trailblazeDevicePlatform})"
                } +
                ". Only ${COMPANION_PLATFORMS.joinToString()} companions are supported.",
            )
          }
          TrailblazeHostYamlRunner.MultiDeviceSessionRpc(
            configurationName = resolved.configurationName,
            startDeviceName = resolved.startDeviceName,
            deviceDescriptions = resolved.memberDescriptions,
            companions = resolved.companionDeviceIds.mapValues { (name, companionDeviceId) ->
              val companionTarget = memberTargets?.get(name)
              val companionDriver = resolved.companionDriverTypes.getValue(name)
              onProgressMessage(
                "Binding device '$name' to ${companionDeviceId.instanceId}" +
                  (companionTarget?.id?.let { " (target '$it')" } ?: ""),
              )
              when (companionDeviceId.trailblazeDevicePlatform) {
                TrailblazeDevicePlatform.WEB -> TrailblazeHostYamlRunner.DeviceConnection.WebBrowser(
                  trailblazeDeviceId = companionDeviceId,
                  pageManager = bindWebBrowser(companionDeviceId, name, runYamlRequest.config.browserHeadless, onProgressMessage),
                  // A browser installs no app, so this only scopes the tools its agent resolves.
                  targetTestApp = companionTarget,
                )
                TrailblazeDevicePlatform.IOS -> TrailblazeHostYamlRunner.DeviceConnection.IosSimulator(
                  trailblazeDeviceId = companionDeviceId,
                  connectedDevice = connectIos(name, companionDeviceId, companionDriver, companionTarget),
                  targetTestApp = companionTarget,
                )
                else -> {
                  val (_, companionRpc) = connectAndroid(
                    deviceId = companionDeviceId,
                    driverType = companionDriver,
                    // This device's own target decides which instrumentation runner warms up on it —
                    // a companion running a different app than the launch device needs that app's
                    // runner, not the launch device's.
                    instrumentationTarget = resolveCompanionInstrumentationTarget(
                      configurationName = resolved.configurationName,
                      companionName = name,
                      companionTarget = companionTarget,
                      driverType = companionDriver,
                      runTargetApp = runTargetApp,
                    ),
                    // This companion's own target, for the same reason its instrumentation target is
                    // resolved from it: the harnesses to clear are the ones belonging to the app
                    // THIS device runs, not the launch device's.
                    deviceRunTarget = companionTarget ?: runTargetApp,
                  )
                  companionRpcClients += companionRpc
                  TrailblazeHostYamlRunner.DeviceConnection.AndroidRpc(
                    trailblazeDeviceId = companionDeviceId,
                    rpcClient = companionRpc,
                    driverType = companionDriver,
                    targetTestApp = companionTarget,
                  )
                }
              }
            },
          )
        }

        // Announced before the runner starts the session (which is what fires `onSessionStarted`),
        // so capture arms every display's bridge rather than only the launch device's. The launch
        // device is named too: in a session with two displays there is no unlabeled "the" network
        // stream.
        multiDeviceSession?.let { session ->
          // The DECLARED ids, not the resolved targets: `resolveMemberTargets` hands a member that
          // declared nothing the session target, so a resolved target being non-null says nothing
          // about whose it is. Only a declaration makes it this device's own.
          val declaredMemberTargetIds = resolvedConfiguration?.memberTargetIds.orEmpty()
          onCaptureDeviceBindingsResolved(
            (mapOf(session.startDeviceName to launchDevice) + session.companions).map { (name, device) ->
              CaptureDeviceBinding(
                name = name,
                deviceId = device.trailblazeDeviceId,
                // This device's OWN target: capture verifies the app identity of the client that
                // dials in, so another device's app ids would make it reject the right client.
                target = device.targetTestApp ?: targetTestApp,
                targetAppIds = (device.targetTestApp ?: targetTestApp)
                  ?.getPossibleAppIdsForPlatform(device.trailblazeDeviceId.trailblazeDevicePlatform)
                  .orEmpty(),
                hasOwnTarget = declaredMemberTargetIds[name] != null,
              )
            },
          )
        }

        withContext(Dispatchers.Default) {
          onConnectionStatus(launchStatus)

          // A mid-trail wedge on this path is re-thrown by `executeTrailSession`, so the runner
          // never returns the wedged session's id — but the terminal `Ended.Failed` status (with
          // the non-recoverable signature) is written to disk before the re-throw. Capture the live
          // session id from `onSessionStarted` and arm the relaunch in a finally so detection fires
          // whether the runner returns normally or propagates the wedge as an exception.
          var hostAgentSessionId: SessionId? = null
          try {
            TrailblazeHostYamlRunner.runHostAgentWithOnDeviceRpc(
              dynamicLlmClient = dynamicLlmClient,
              launchDevice = launchDevice,
              runYamlRequest = runYamlRequest,
              onProgressMessage = onProgressMessage,
              onSessionStarted = { sessionId ->
                hostAgentSessionId = sessionId
                onSessionStarted(sessionId)
              },
              multiDeviceSession = multiDeviceSession,
              logsDir = logsDirProvider(),
              noLogging = noLogging,
            )
          } finally {
            armIfWedged(
              hostAgentSessionId,
              // Android devices only — arming relaunches an on-device server, which a browser or
              // simulator doesn't have.
              (listOf(launchDevice) + multiDeviceSession?.companions?.values.orEmpty())
                .filterIsInstance<TrailblazeHostYamlRunner.DeviceConnection.AndroidRpc>()
                .map { it.trailblazeDeviceId },
              onProgressMessage,
            )
          }
        }
      } finally {
        closeCompanionRpcClients(companionRpcClients)
        releaseBoundIosDevices(boundIosDevices)
      }
    }
  }

  /**
   * Binds a WEB device of a multi-device session, launch device or companion, to a host-owned
   * Playwright browser, reusing the daemon slot named by [deviceId] when one is already running
   * (the desktop "Launch Browser" button, a `device create web`, or a previous trail in the same
   * daemon) and launching it otherwise.
   *
   * A web device is virtual: unlike an Android companion there is nothing to boot, connect, or
   * warm up, so a CI lane binds one with `dashboard=playwright-native` and no boot step.
   *
   * Deliberately does NOT reset the browser session — same rule the single-device web runner
   * follows when it adopts a running slot. A reset would navigate an already-signed-in browser
   * to `about:blank`, discarding exactly the state an operator provisioned it for.
   */
  private suspend fun bindWebBrowser(
    deviceId: TrailblazeDeviceId,
    deviceName: String,
    /** The run's own setting, for a browser this launches; a running one keeps its mode. */
    headless: Boolean,
    onProgressMessage: (String) -> Unit,
  ): PlaywrightPageManager {
    val webBrowserManager = trailblazeDeviceManager.webBrowserManager
    val instanceId = deviceId.instanceId
    webBrowserManager.getPageManager(instanceId)?.let { running ->
      onProgressMessage("Device '$deviceName' bound to the running browser '$instanceId'")
      return running
    }
    onProgressMessage("Launching browser '$instanceId' for device '$deviceName'...")
    val launched = CompletableDeferred<Unit>()
    webBrowserManager.launchBrowser(instanceId = instanceId, headless = headless) { launched.complete(Unit) }
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

  /** Releases this run's hold on each iOS simulator's shared driver; other holders keep theirs. */
  private fun releaseBoundIosDevices(devices: List<TrailblazeConnectedDevice>) {
    devices.forEach { device ->
      runCatching { (device as? MaestroConnectedDevice)?.getMaestroDriver()?.close() }.onFailure { t ->
        Console.log("[DesktopYamlRunner] Failed to release an iOS simulator: ${t.message}")
      }
    }
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
   * Resolves a unified trail's `config.devices:` CONFIGURATION entry from the request's own
   * selection and bindings, falling back to `TRAILBLAZE_DEVICE_CONFIGURATION` /
   * `TRAILBLAZE_DEVICE_BINDINGS`. Thin env-reading wrapper over the pure
   * [MultiDeviceConfigurationResolver.resolve], which owns the precedence ladder so it stays
   * unit-testable without a process environment.
   */
  private suspend fun resolveMultiDeviceConfiguration(
    runYamlRequest: RunYamlRequest,
    primaryDeviceId: TrailblazeDeviceId,
  ): MultiDeviceConfigurationResolver.ResolvedMultiDeviceConfiguration? {
    val rawDeviceBindings = System.getenv(MultiDeviceConfigurationResolver.DEVICE_BINDINGS_ENV_VAR)
    // Only a run that binds companions can select a configuration, so a single-device run skips
    // the device discovery this needs.
    val boundDevicePlatforms =
      if (rawDeviceBindings.isNullOrBlank() && runYamlRequest.deviceBindings.isEmpty()) {
        emptyMap()
      } else {
        trailblazeDeviceManager.loadDevicesSuspend(applyDriverFilter = false)
          .associate { it.instanceId to it.platform }
      }
    return MultiDeviceConfigurationResolver.resolve(
      yaml = runYamlRequest.yaml,
      primaryDeviceId = primaryDeviceId,
      rawDeviceBindings = rawDeviceBindings,
      requestDeviceBindings = runYamlRequest.deviceBindings,
      requestConfigurationName = runYamlRequest.deviceConfiguration,
      environmentConfigurationName =
        System.getenv(MultiDeviceConfigurationResolver.DEVICE_CONFIGURATION_ENV_VAR),
      // The driver this run resolved for the launch device — and therefore the one every device in
      // the cast is dispatched over. The caller has already folded it into the request
      // (`copy(driverType = trailblazeDriverType)`), so a member pinning anything else is rejected
      // here rather than silently ignored.
      sessionDriverType = runYamlRequest.driverType,
      boundDevicePlatforms = boundDevicePlatforms,
    )
  }

  /**
   * Reads [sessionId]'s logs from disk and, when the terminal status OR any failed tool log
   * carries the non-recoverable UiAutomation wedge (see [shouldRelaunchOnDeviceServer]), arms
   * every device in [boundDeviceIds] in [wedgedDeviceIds] so each one's next trail
   * force-restarts its shared on-device server. Shared by the on-device-agent path (which polls
   * completion in [awaitOnDeviceSessionCompletion]) and the host-agent path (detection
   * runs in its `finally` against the on-disk logs). No-op when [sessionId] is null or
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
     * Fired with the session id as soon as it is known: before the RPC is sent when the request
     * pins one (see [sessionIdToCaptureBeforeDispatch]), and again from the reply. Callers use this
     * to spin up out-of-band session-scoped infrastructure that needs to run *while* the YAML
     * executes (e.g. an Android network-capture activator — it has to be polling its discovery
     * side-channel before the launch tool's first network call so it can attach to the target's
     * freshly-opened socket), so it must be idempotent per session. Defaulted to a no-op so
     * existing callers stay compatible.
     */
    onSessionStarted: (SessionId) -> Unit = {},
    /**
     * Fired with the pinned session id when [onSessionStarted] ran for it before dispatch but the
     * device never ran the session: the request was refused or threw. The device writes no logs
     * for that id, so the caller's own session-end cleanup cannot find it; this is the only point
     * that can stop what [onSessionStarted] started.
     */
    onSessionAbandoned: (SessionId) -> Unit = {},
    /**
     * The run's effective target — `targetTestApp ?: trailblazeHostAppTarget`, the same expression
     * [resolveOnDeviceInstrumentationTarget] picks the runner from. See [connectAndEnsureReady].
     */
    runTargetApp: TrailblazeHostAppTarget,
  ): SessionId? {
    return withContext(Dispatchers.IO) {
      val needsAccessibility =
        AndroidAccessibilityServiceDrivers.includes(runYamlRequest.driverType)
      val (status, readiness) = connectAndEnsureReady(
        onDeviceRpc = onDeviceRpc,
        trailblazeDeviceId = trailblazeConnectedDevice.trailblazeDeviceId,
        trailblazeOnDeviceInstrumentationTarget = trailblazeOnDeviceInstrumentationTarget,
        additionalInstrumentationArgs = additionalInstrumentationArgs,
        onProgressMessage = onProgressMessage,
        enableAccessibility = needsAccessibility,
        requireAndroidAccessibilityService = needsAccessibility,
        runTargetApp = runTargetApp,
      )

      withContext(Dispatchers.Default) {
        onConnectionStatus(status)
        // Errors from the callback are caught so a misbehaving listener can't crash the test run.
        val notifySessionStarted: (SessionId) -> Unit = { sid ->
          try {
            onSessionStarted(sid)
          } catch (t: Throwable) {
            Console.log(
              "[runYamlOnDevice] onSessionStarted callback threw — continuing test run: " +
                "${t::class.java.simpleName}: ${t.message}"
            )
          }
        }
        // A synchronous request replies once the trail is over, so session-scoped capture (logcat,
        // memory, the network bridge) has to start before it is sent. See
        // sessionIdToCaptureBeforeDispatch.
        val pinnedSessionId = sessionIdToCaptureBeforeDispatch(runYamlRequest)
        // The capability gate reads the readiness handshake's answer; see startYamlAfterReadiness.
        val result: RpcResult<RunYamlResponse> = replyOrSessionEnd(
          pinnedSessionId = pinnedSessionId,
          sendRequest = { startYamlAfterReadiness(onDeviceRpc, readiness, runYamlRequest) },
          sessionHasEnded = { trailblazeDeviceManager.logsRepo.persistedEndStatus(it) != null },
          onDispatching = notifySessionStarted,
          onAbandoned = { sid ->
            runCatching { onSessionAbandoned(sid) }.onFailure {
              Console.log("[runYamlOnDevice] onSessionAbandoned callback threw: ${it.message}")
            }
          },
        )
        when (result) {
          is RpcResult.Failure -> {
            onProgressMessage("Failed to start YAML execution: ${result.message}${result.details?.let { " | $it" } ?: ""}")
            null
          }

          is RpcResult.Success -> {
            val runYamlResponse = result.data
            onProgressMessage("YAML test execution started for session: ${runYamlResponse.sessionId}")

            // Also from the reply: an unpinned or fire-and-forget request learns its session id
            // only here. Idempotent when capture already started under the pinned id above.
            notifySessionStarted(runYamlResponse.sessionId)

            // Block until the session reaches an Ended status so its logs are fully streamed
            // before the process exits. A fire-and-forget reply arrives before the trail runs; a
            // synchronous one after it, when this returns at once.
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
    targetTestApp: TrailblazeHostAppTarget?,
    unresolvedDeclaredTarget: String?,
    onProgressMessage: (String) -> Unit,
    deviceBindings: List<CaptureDeviceBinding> = emptyList(),
  ): String? {
    if (deviceBindings.isNotEmpty()) {
      return startMultiDeviceNetworkCapture(
        runYamlRequest = runYamlRequest,
        sessionIdOverride = sessionIdOverride,
        deviceBindings = deviceBindings,
        unresolvedDeclaredTarget = unresolvedDeclaredTarget,
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
          targetAppIds = deviceCaptureTargetAppIds(
            targetTestApp = targetTestApp,
            unresolvedDeclaredTarget = unresolvedDeclaredTarget,
            platform = deviceId.trailblazeDevicePlatform,
          ),
          requireTraffic = androidCaptureRequiresTraffic(runYamlRequest.referrer),
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
   * Arms network capture on every device a multi-device configuration bound, each labelled with the
   * device's configuration name so its evidence lands in its own stream: one capture bridge per
   * Android device, and Playwright capture on each web browser (`events/network.<name>.ndjson`).
   *
   * One device failing to arm does NOT abort the others: a pair session where only one display's
   * app carries a capture client should still capture that display.
   *
   * [MultiDeviceCaptureSelection.CAPTURE_DEVICES_ENV_VAR] narrows this to named devices, web ones
   * included. With no list, a web device is armed only when the run asked for capture outright.
   */
  private fun startMultiDeviceNetworkCapture(
    runYamlRequest: RunYamlRequest,
    sessionIdOverride: SessionId,
    deviceBindings: List<CaptureDeviceBinding>,
    unresolvedDeclaredTarget: String?,
    onProgressMessage: (String) -> Unit,
  ): String? {
    val activator = AndroidNetworkCaptureRegistry.activator
    // Opt-in first: everything below either logs or attaches, and a log line saying which devices
    // are being armed reads as capture having been armed even when this gate then refuses. A web
    // device additionally needs its own opt-in; see MultiDeviceCaptureSelection.webDeviceOptedIn.
    val androidProxyOptIn = CompositeAndroidNetworkCaptureActivator.proxyCaptureEnabledFromEnv()
    val activatorOptIn = activator?.isSessionCaptureOptedIn(sessionIdOverride.value) == true
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
          when (it.deviceId.trailblazeDevicePlatform) {
            TrailblazeDevicePlatform.ANDROID -> activator != null
            TrailblazeDevicePlatform.WEB -> MultiDeviceCaptureSelection.webDeviceOptedIn(
              name = it.name,
              explicitCapture = runYamlRequest.config.captureNetworkTraffic,
              allowedNames = allowedDeviceNames,
            )
            else -> false
          }
        },
        allowedNames = allowedDeviceNames,
        nameOf = { it.name },
      )
    val (webBindings, androidBindings) =
      selection.armed.partition { it.deviceId.trailblazeDevicePlatform == TrailblazeDevicePlatform.WEB }
    if (allowedDeviceNames.isNotEmpty()) {
      // Both directions are logged because both are silent otherwise: a skipped device just has no
      // stream in the report, and a misspelled name disarms capture for the whole session.
      Console.log(
        "${MultiDeviceCaptureSelection.CAPTURE_DEVICES_ENV_VAR} limits network capture to " +
          "${allowedDeviceNames.sorted()}; arming ${selection.armed.map { it.name }} of bound " +
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
    if (selection.armed.isEmpty()) return null
    val sessionDir = trailblazeDeviceManager.logsRepo.getSessionDir(sessionIdOverride)
    var anyStarted = false
    webBindings.forEach { binding ->
      runCatching {
        val pageManager = trailblazeDeviceManager.webBrowserManager
          .getPageManager(binding.deviceId.instanceId)
          ?: error("no browser is running for ${binding.deviceId.instanceId}")
        WebCompanionNetworkCapture.start(
          sessionId = sessionIdOverride.value,
          sessionDir = sessionDir,
          pageManager = pageManager,
          deviceLabel = binding.name,
        )
        anyStarted = true
        onProgressMessage(
          "Web network capture started for device '${binding.name}' " +
            "(${binding.deviceId.instanceId}) in session ${sessionIdOverride.value}",
        )
      }
        .onFailure {
          Console.log(
            "Auto-start of web network capture failed for device '${binding.name}' " +
              "(${binding.deviceId.instanceId}) in ${sessionIdOverride.value}: ${it.message}"
          )
        }
    }
    // Non-null whenever any Android binding was selected: the candidate filter above admits
    // Android devices only when an activator is registered.
    androidBindings.forEach { binding ->
      runCatching {
        checkNotNull(activator).start(
          sessionId = sessionIdOverride.value,
          sessionDir = sessionDir,
          deviceId = binding.deviceId,
          targetAppIds = deviceCaptureTargetAppIds(
            targetTestApp = binding.target,
            unresolvedDeclaredTarget = unresolvedTargetForDevice(
              sessionUnresolvedTarget = unresolvedDeclaredTarget,
              deviceHasOwnTarget = binding.hasOwnTarget,
            ),
            platform = binding.deviceId.trailblazeDevicePlatform,
          ),
          deviceLabel = binding.name,
          requireTraffic = androidCaptureRequiresTraffic(runYamlRequest.referrer),
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
    /**
     * The target this device runs: its own declared one, else the session's. Network capture
     * derives its peer-identity ids from this with [deviceCaptureTargetAppIds] rather than reading
     * [targetAppIds], because that list cannot tell a device with no target (nothing to capture)
     * from one whose target did not resolve (capture must refuse).
     */
    val target: TrailblazeHostAppTarget? = null,
    /**
     * True when the configuration DECLARED a `target:` for this device, so [targetAppIds] are its
     * own rather than the session's. Read by [unresolvedTargetForDevice]: a device with its own
     * target has the app ids the trail drives even in a session whose `config.target` resolved to
     * nothing. Must come from the declaration, not from the resolved target being non-null — a
     * member that declares nothing inherits the session's, which is the fallback app the flag is
     * supposed to catch.
     */
    val hasOwnTarget: Boolean = false,
  )

  // `stopCaptureAndMoveArtifacts` lived here. Removed in favor of
  // [SessionCaptureCoordinator.stopForSession], which writes artifacts directly into
  // the session log dir from the start so the temp-dir + move step is no longer needed.
}
