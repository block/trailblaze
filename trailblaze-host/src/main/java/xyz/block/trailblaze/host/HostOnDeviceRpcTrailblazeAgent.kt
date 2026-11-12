package xyz.block.trailblaze.host

import xyz.block.trailblaze.mobile.tools.AdbShellTrailblazeTool
import xyz.block.trailblaze.mobile.tools.AndroidGrantPermissionsTrailblazeTool
import xyz.block.trailblaze.mobile.tools.AndroidSendBroadcastTrailblazeTool
import xyz.block.trailblaze.mobile.tools.ListInstalledAppsTrailblazeTool
import java.io.File
import xyz.block.trailblaze.logs.client.SessionStartRecords
import xyz.block.trailblaze.host.util.BufferedImageUtils
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.currentCoroutineContext
import xyz.block.trailblaze.host.android.AndroidSessionReconnector
import xyz.block.trailblaze.toolcalls.DeviceSessionReconnectSupport
import kotlinx.datetime.Clock
import maestro.orchestra.Command
import xyz.block.trailblaze.AgentMemory
import xyz.block.trailblaze.api.DriverNodeMatch
import xyz.block.trailblaze.MaestroTrailblazeAgent
import xyz.block.trailblaze.device.withDeviceCommandSecretsRedacted
import xyz.block.trailblaze.toolcalls.DispatchCancellation
import xyz.block.trailblaze.toolcalls.sensitiveLiteralValues
import xyz.block.trailblaze.toolcalls.commands.MaestroTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.TapOnByElementSelector
import xyz.block.trailblaze.toolcalls.commands.allDriverMatches
import xyz.block.trailblaze.maestro.MaestroYamlSerializer
import xyz.block.trailblaze.api.EffectiveScreenshotScalingConfig
import xyz.block.trailblaze.api.ScreenState
import xyz.block.trailblaze.api.TrailblazeNodeSelector
import xyz.block.trailblaze.devices.TrailblazeDeviceInfo
import xyz.block.trailblaze.exception.TrailblazeException
import xyz.block.trailblaze.llm.RunYamlRequest
import xyz.block.trailblaze.llm.RunYamlResponse
import xyz.block.trailblaze.logToolExecution
import xyz.block.trailblaze.logs.client.TrailblazeLogger
import xyz.block.trailblaze.logs.client.TrailblazeSessionProvider
import xyz.block.trailblaze.logs.client.temp.OtherTrailblazeTool
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.logs.model.TraceId
import xyz.block.trailblaze.mcp.android.ondevice.rpc.GetScreenStateRequest
import xyz.block.trailblaze.mcp.android.ondevice.rpc.OnDeviceRpcClient
import xyz.block.trailblaze.mcp.android.ondevice.rpc.RpcResult
import xyz.block.trailblaze.mcp.android.ondevice.rpc.GetScreenStateResponse
import xyz.block.trailblaze.mcp.utils.RpcScreenStateAdapter
import xyz.block.trailblaze.model.TapRouteOverride
import xyz.block.trailblaze.android.tools.AndroidAccessibilityClickTrailblazeTool
import xyz.block.trailblaze.android.tools.AndroidGestureTapTrailblazeTool
import xyz.block.trailblaze.host.recording.StreamFrameMonitor
import xyz.block.trailblaze.host.recording.StreamScreenshotScreenState
import xyz.block.trailblaze.host.recording.DeviceStreamScreenshotSource
import xyz.block.trailblaze.toolcalls.DelegatingTrailblazeTool
import xyz.block.trailblaze.toolcalls.ExecutableTrailblazeTool
import xyz.block.trailblaze.toolcalls.HostLocalExecutableTrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext
import xyz.block.trailblaze.toolcalls.TrailblazeToolRepo
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult
import xyz.block.trailblaze.toolcalls.interpolateMemoryInTool
import xyz.block.trailblaze.toolcalls.requiresHostInstance
import xyz.block.trailblaze.util.Console
import xyz.block.trailblaze.util.UiAutomationHandleErrors
import xyz.block.trailblaze.yaml.TrailArgBinder
import xyz.block.trailblaze.yaml.createTrailblazeYaml
import xyz.block.trailblaze.yaml.fromTrailblazeTool
import kotlin.reflect.KClass
import xyz.block.trailblaze.tracing.TrailblazeTracer

/**
 * Host-side [MaestroTrailblazeAgent] that delegates individual tool executions to an on-device
 * driver (accessibility or instrumentation) via RPC, while keeping the agent loop (LLM calls,
 * tool selection) running on the host.
 *
 * Each tool call is serialized as single-step trail YAML and sent to the device as a
 * [RunYamlRequest] with [RunYamlRequest.awaitCompletion] = `true`. The device executes the tool via whichever
 * driver is specified in the request's `driverType` and the terminal state comes back
 * inline on the response — no status polling.
 */
class HostOnDeviceRpcTrailblazeAgent(
  private val rpcClient: OnDeviceRpcClient,
  private val runYamlRequestTemplate: RunYamlRequest,
  trailblazeLogger: TrailblazeLogger,
  trailblazeDeviceInfoProvider: () -> TrailblazeDeviceInfo,
  sessionProvider: TrailblazeSessionProvider,
  customToolClasses: Set<KClass<out TrailblazeTool>> = emptySet(),
  /**
   * Whether mid-trail re-warm probes must confirm the accessibility service is bound. Should
   * be true when this agent drives `ANDROID_ONDEVICE_ACCESSIBILITY` so a post-blip UiAutomator
   * fallback can't silently take over; false for instrumentation-driver flows.
   */
  private val requireAndroidAccessibilityServiceOnRewarm: Boolean = false,
  /**
   * Budget for mid-trail re-warm probes after a transient RPC failure. Bounds the wall-clock
   * cost of the recovery path so a permanently-broken connection fails fast rather than
   * stalling each tool call for the full default budget. Tests override to ~50ms so the
   * circuit-breaker state machine can be exercised without paying real-clock retry delays.
   */
  private val reWarmTimeoutMs: Long = 10_000L,
  /**
   * Poll interval inside [OnDeviceRpcClient.waitForReady] during re-warm. Tests override to
   * ~1ms so the loop body runs quickly when the test wants to force the timeout to fire;
   * production stays at the readiness-probe default. Matches `OnDeviceRpcClient.waitForReady`'s
   * default — if you change one, audit the other.
   */
  private val reWarmPollIntervalMs: Long = 500L,
  /**
   * Session tool repo — threaded to [MaestroTrailblazeAgent] (and then [BaseTrailblazeAgent])
   * so an [OtherTrailblazeTool] naming a subprocess MCP tool in a trail YAML resolves to its
   * registered [SubprocessTrailblazeTool][xyz.block.trailblaze.scripting.subprocess.SubprocessTrailblazeTool]
   * instead of hitting "Unsupported tool type for RPC execution".
   */
  trailblazeToolRepo: TrailblazeToolRepo? = null,
  /**
   * Pre-resolved session target — threaded into every [TrailblazeToolExecutionContext] this
   * agent builds so in-process scripted-tool handlers can read `ctx.target.{id, appIds,
   * appId}` without per-call device probes. The host-side caller resolves the target once at
   * session start and passes it in here. Defaults to null to preserve back-compat with callers that
   * don't yet wire it (their in-process scripted tools will see `ctx.target` as undefined,
   * which is the pre-#2904 behaviour).
   */
  resolvedTarget: xyz.block.trailblaze.model.ResolvedTarget? = null,
  /**
   * Device-filtered app id for [resolvedTarget]. Null when no declared candidate is installed
   * (scripted tools should fall back to `ctx.target?.appIds[0]` and let the launch fail
   * downstream with a clearer message). Threaded identically to [resolvedTarget].
   */
  appId: String? = null,
  /**
   * Maps this session's [SessionId] to its on-host log dir, so host-side `requiresHost` tools
   * (e.g. a capture-reading tool) dispatched through this agent can resolve capture artifacts under
   * `<sessionDir>/`. Wired from `LogsRepo::getSessionDir` by the host runner; null in back-compat
   * callers that don't yet supply it.
   */
  sessionDirProvider: ((SessionId) -> File)? = null,
  /**
   * The trail file's own directory — see [MaestroTrailblazeAgent.workingDirectory]. Wired from
   * `RunYamlRequest.trailFilePath` by the host runner so host-local tools dispatched through this
   * agent resolve paths anchored BESIDE THE TRAIL (e.g. a committed WAV recording), not against
   * the daemon's CWD; null in back-compat callers that don't yet supply it.
   */
  workingDirectory: File? = null,
  /**
   * Shared [AgentMemory] — see [BaseTrailblazeAgent.memory]. Multi-device sessions construct one
   * agent per bound device and pass the primary agent's memory here so a `remember` on one device
   * resolves in `{{var}}` tokens dispatched to another. Defaults to a fresh instance for
   * single-device callers.
   */
  memory: AgentMemory = AgentMemory(),
  /**
   * Called with every screen state this device reports, so a caller holding a start-of-session
   * snapshot of the device's size can keep it current — the response already carries the size, so
   * nothing extra is asked of the device.
   *
   * Scoped to one agent on purpose. Each bound device gets its own agent, so a companion rotating
   * can't rewrite the launch device's size; a session-wide hook could.
   */
  private val onScreenStateObserved: (GetScreenStateResponse) -> Unit = {},
  /**
   * Starts the on-device runner again after the app's process ended. Set only for the in-process
   * driver, whose runner lives inside the app: a step that restarts the app (a launch, or a
   * broadcast the app answers by restarting itself) ends the runner with it. Null for drivers whose
   * runner is a process of its own, where a lost connection is a transport fault instead.
   */
  private val restartRunnerAfterAppExit: (suspend () -> Unit)? = null,
  /** A package's pid(s), blank when it has no process, null when it can't be told. Tests script this. */
  private val appProcessId: (packageName: String) -> String? = rpcClient::appProcessId,
  /**
   * How this device's session is re-established after the device reboots — see
   * [reconnectSession]. Wired by the host runner from the same connect it bound the device with;
   * null in callers that never run a reconnect step, where `android_reconnectSession` fails with a
   * clear message instead of relaunching a runner it could not bind.
   */
  private val sessionReconnector: AndroidSessionReconnector? = null,
) : MaestroTrailblazeAgent(
  trailblazeLogger = trailblazeLogger,
  trailblazeDeviceInfoProvider = trailblazeDeviceInfoProvider,
  sessionProvider = sessionProvider,
  trailblazeToolRepo = trailblazeToolRepo,
  resolvedTarget = resolvedTarget,
  appId = appId,
  sessionDirProvider = sessionDirProvider,
  workingDirectory = workingDirectory,
  memory = memory,
  captureNetworkTraffic = runYamlRequestTemplate.config.captureNetworkTraffic,
),
  DeviceSessionReconnectSupport {

  override val usesAccessibilityDriver: Boolean = true

  private val trailblazeYaml = createTrailblazeYaml(customToolClasses)

  /** Last observed RPC failure from [captureScreenState], surfaced by [screenStateProvider]. */
  @Volatile private var lastCaptureFailure: String? = null

  /**
   * Experimental: serve screenshots from the device's live screenrecord stream instead of the
   * per-capture on-device `UiAutomation.takeScreenshot`. Read once at agent construction
   * (i.e. per run) — see [StreamScreenshotMode].
   */
  private val streamScreenshotMode = StreamScreenshotMode.resolve()

  /**
   * Lazily initialized on the first successful capture (needs the response's device
   * dimensions to size the shared H.264 tee). Null when [streamScreenshotMode] is OFF or
   * initialization failed. Closed via [closeStreamScreenshotSource].
   */
  @Volatile private var streamScreenshotSource: DeviceStreamScreenshotSource? = null

  /** Consecutive wedge-signature [captureScreenState] failures; trips circuit breaker
   *  at [MAX_CONSECUTIVE_DEVICE_WEDGE_FAILURES] to fail fast on dead `system_server`. */
  private val consecutiveDeviceWedgeFailures = AtomicInteger(0)

  /** RPC-backed screen state provider for the host-side agent. */
  val screenStateProvider: () -> ScreenState = {
    // Linked to the trail's Job, as a dispatch is: a stopped run's screen read ends with it.
    runBlocking { DispatchCancellation.linked(this) { captureScreenState() } }
      ?: error(
        "Failed to capture screen state from device via RPC" +
          (lastCaptureFailure?.let { ": $it" } ?: ""),
      )
  }

  /**
   * Captures current screen state via RPC. The [OnDeviceRpcClient.waitForReady] handshake at
   * trail start proves `GetScreenState` works; a failure here means the connection transitioned
   * from warm to cold mid-session (app/service restart, transient network blip). In that case
   * we re-run the readiness probe once and retry the capture — no blanket retry loop.
   *
   * @param includeScreenshot whether the on-device handler should bundle the screenshot
   *   bytes in the response. Migration capture (the host's per-tool snapshot hook) needs
   *   only the trees and can pass `false` to skip the Bitmap pull / WEBP encode / base64
   *   round-trip — saves ~200-500ms per capture on real devices.
   */
  suspend fun captureScreenState(includeScreenshot: Boolean = true): ScreenState? =
    TrailblazeTracer.traceSuspend("captureScreenState", SCREEN_STATE_TRACE_CAT) {
      captureScreenStateUntraced(includeScreenshot)
    }

  /**
   * [captureScreenState] without the span, so the traced wrapper can enclose a body with several
   * early returns.
   */
  private suspend fun captureScreenStateUntraced(includeScreenshot: Boolean): ScreenState? {
    ensureInProcessRunnerUp("a screen capture")
    // Forward the host's effective screenshot scaling config (driven by
    // `trailblaze config screenshot-*` and the desktop Settings panel) so the on-device
    // agent scales/encodes screenshots the way the user asked. Skipped when the request
    // doesn't include a screenshot — no point spending the bytes.
    // Stream-screenshot mode skips the on-device Bitmap pull / WEBP encode / base64 leg and
    // fills the screenshot from the live screenrecord stream instead. Only once the source is
    // initialized (first capture always fetches the on-device screenshot — the source needs
    // that response's device dimensions), and never in AB mode (the on-device screenshot
    // stays authoritative there so the two can be compared). Deliberate trade-off: stream
    // frames come at the shared tee's recording size and JPEG quality, so the user's
    // `trailblaze config screenshot-*` scaling/format settings do NOT apply to them (they
    // still govern the warm-up and fallback on-device captures).
    val deviceScreenshot = includeScreenshot &&
      (streamScreenshotMode != StreamScreenshotMode.STREAM || streamScreenshotSource == null)
    val request = GetScreenStateRequest(includeScreenshot = deviceScreenshot).let {
      if (deviceScreenshot) it.withScreenshotScalingConfig(EffectiveScreenshotScalingConfig.effective)
      else it
    }
    when (val first = TrailblazeTracer.traceDetailSuspend("getScreenState.rpc", SCREEN_STATE_TRACE_CAT) { rpcClient.rpcCall(request) }) {
      is RpcResult.Success -> {
        consecutiveDeviceWedgeFailures.set(0)
        lastCaptureFailure = null
        return adaptScreenState(first.data, includeScreenshot)
      }
      is RpcResult.Failure -> {
        val detail = first.message + (first.details?.let { " | $it" } ?: "")
        lastCaptureFailure = detail
        Console.log(
          "[HostOnDeviceRpcAgent] GetScreenState ${first.errorType}: ${first.message}" +
            (first.details?.let { "\n  Details: $it" } ?: "") +
            "\n  Re-warming connection and retrying once.",
        )
        if (restartRunnerAfterAppExit != null && first.errorType == RpcResult.ErrorType.NETWORK_ERROR &&
          !runnerStillAnswers()
        ) {
          restartRunner(restartRunnerAfterAppExit)
        }
      }
    }
    try {
      rpcClient.waitForReady(
        timeoutMs = reWarmTimeoutMs,
        pollIntervalMs = reWarmPollIntervalMs,
        requireAndroidAccessibilityService = requireAndroidAccessibilityServiceOnRewarm,
      )
    } catch (e: Exception) {
      val detail = "re-warm failed: ${e.message} | runner_pid=${rpcClient.probeRunnerPid()}"
      lastCaptureFailure = "$lastCaptureFailure | $detail"
      Console.log("[HostOnDeviceRpcAgent] $detail")
      tripCircuitBreakerIfDeviceWedged()
      return null
    }
    return when (val retry = TrailblazeTracer.traceDetailSuspend("getScreenState.rpc.retry", SCREEN_STATE_TRACE_CAT) { rpcClient.rpcCall(request) }) {
      is RpcResult.Success -> {
        consecutiveDeviceWedgeFailures.set(0)
        lastCaptureFailure = null
        adaptScreenState(retry.data, includeScreenshot)
      }
      is RpcResult.Failure -> {
        val detail = retry.message + (retry.details?.let { " | $it" } ?: "")
        lastCaptureFailure = "$lastCaptureFailure | retry: $detail"
        Console.log(
          "[HostOnDeviceRpcAgent] GetScreenState retry after re-warm still failed " +
            "${retry.errorType}: ${retry.message}" +
            (retry.details?.let { "\n  Details: $it" } ?: ""),
        )
        tripCircuitBreakerIfDeviceWedged()
        null
      }
    }
  }

  /**
   * Wraps the RPC response in a [ScreenState], substituting the screenshot from the live
   * screenrecord stream when stream mode is active. First successful capture initializes the
   * source (and keeps that response's on-device screenshot); later captures pair the tree's
   * device-epoch stamp with a stream frame via
   * [DeviceStreamScreenshotSource.awaitFrameMatching].
   */
  private suspend fun adaptScreenState(
    data: GetScreenStateResponse,
    includeScreenshot: Boolean,
  ): ScreenState {
    // Before any of the screenshot-source branching below, which can return early.
    onScreenStateObserved(data)
    val base = TrailblazeTracer.traceDetail("adaptScreenState", SCREEN_STATE_TRACE_CAT) {
      RpcScreenStateAdapter.from(data, BufferedImageUtils::encodeLikeCaptures)
    }
    if (!includeScreenshot || streamScreenshotMode == StreamScreenshotMode.OFF) return base

    val existing = streamScreenshotSource
    val source = if (existing != null) {
      existing
    } else {
      val created = DeviceStreamScreenshotSource.forAndroid(
        deviceId = runYamlRequestTemplate.trailblazeDeviceId,
        deviceWidth = data.deviceWidth,
        deviceHeight = data.deviceHeight,
      )
      try {
        created.start()
        streamScreenshotSource = created
      } catch (e: Exception) {
        Console.log("[stream-screenshot] failed to start stream source, staying on the on-device path: ${e.message}")
        return base
      }
      // This response already carries the on-device screenshot (the source didn't exist when
      // the request was built) — keep it while the stream warms up. AB mode falls through so
      // even the warm-up capture logs a comparison line.
      if (streamScreenshotMode == StreamScreenshotMode.STREAM) return base
      created
    }

    val result = TrailblazeTracer.traceDetailSuspend("awaitStreamFrame", SCREEN_STATE_TRACE_CAT) {
      source.awaitFrameMatching(
        treeCapturedAtMs = data.capturedAtDeviceMs,
        timeoutMs = STREAM_FRAME_TIMEOUT_MS,
      )
    }
    return when (streamScreenshotMode) {
      StreamScreenshotMode.AB_COMPARE -> {
        // On-device screenshot stays authoritative; log enough per capture to judge the
        // stream path's viability (match rate, clock skew, payload sizes) from a normal run.
        when (result) {
          is StreamFrameMonitor.Result.Matched -> Console.log(
            "[stream-screenshot] AB matched: skewMs=${result.frameVsTreeSkewMs} " +
              "streamBytes=${result.jpegBytes.size} " +
              "deviceBytes=${base.screenshotBytes?.size} treeTs=${data.capturedAtDeviceMs}",
          )
          is StreamFrameMonitor.Result.Unavailable -> Console.log(
            "[stream-screenshot] AB unmatched: ${result.reason} treeTs=${data.capturedAtDeviceMs}",
          )
        }
        base
      }
      StreamScreenshotMode.STREAM -> when (result) {
        is StreamFrameMonitor.Result.Matched -> {
          Console.log(
            "[stream-screenshot] matched: skewMs=${result.frameVsTreeSkewMs} " +
              "bytes=${result.jpegBytes.size} treeTs=${data.capturedAtDeviceMs}",
          )
          StreamScreenshotScreenState(delegate = base, streamJpegBytes = result.jpegBytes)
        }
        is StreamFrameMonitor.Result.Unavailable -> {
          Console.log("[stream-screenshot] unmatched (${result.reason}) — falling back to on-device screenshot")
          fetchOnDeviceScreenshotFallback() ?: base
        }
      }
      StreamScreenshotMode.OFF -> base // unreachable — guarded above
    }
  }

  /**
   * One direct re-request with the on-device screenshot included, used when the stream can't
   * produce a matching frame. No re-warm loop — the tree RPC just succeeded, so the channel
   * is warm; a failure here degrades to the tree-only state rather than failing the capture.
   */
  private suspend fun fetchOnDeviceScreenshotFallback(): ScreenState? {
    val request = GetScreenStateRequest(includeScreenshot = true)
      .withScreenshotScalingConfig(EffectiveScreenshotScalingConfig.effective)
    return when (val result = rpcClient.rpcCall(request)) {
      is RpcResult.Success -> RpcScreenStateAdapter.from(result.data, BufferedImageUtils::encodeLikeCaptures)
      is RpcResult.Failure -> {
        Console.log("[stream-screenshot] fallback re-capture failed: ${result.message}")
        null
      }
    }
  }

  /**
   * Detaches from the shared H.264 tee (reaping the underlying `screenrecord` if this was the
   * last consumer). Call at session teardown; no-op when stream mode never engaged.
   */
  fun closeStreamScreenshotSource() {
    streamScreenshotSource?.let {
      streamScreenshotSource = null
      runCatching { it.close() }
    }
  }

  /**
   * Brings this session back on the device's new boot for the `android_reconnectSession` step; the
   * sequence is [AndroidSessionReconnector.reconnect]. What this agent contributes is the state of
   * its own that died with the device and must go before the runner is relaunched: the
   * stream-screenshot tee, and the wedge breaker's count of failures of a server that no longer
   * exists.
   */
  override suspend fun reconnectSession(): String {
    val reconnector = sessionReconnector
      ?: throw TrailblazeException(
        "This session was not bound with session reconnection, so it cannot reconnect to " +
          "${runYamlRequestTemplate.trailblazeDeviceId.instanceId} after a reboot.",
      )
    return reconnector.reconnect(
      deviceId = runYamlRequestTemplate.trailblazeDeviceId,
      sessionId = { sessionProvider.invoke().sessionId },
      beforeReconnect = {
        closeStreamScreenshotSource()
        consecutiveDeviceWedgeFailures.set(0)
        lastCaptureFailure = null
      },
    )
  }

  /** Circuit breaker for a wedged `system_server`: cache-clear-and-reconnect can't recover
   *  a dead remote, so fail fast after N consecutive wedge-signature failures. Also arms the
   *  on-device server relaunch before throwing — see the comment at the throw site. */
  private fun tripCircuitBreakerIfDeviceWedged() {
    val detail = lastCaptureFailure ?: return
    val matchesWedge = DEVICE_WEDGE_SIGNATURES.any { detail.contains(it) }
    if (!matchesWedge) {
      consecutiveDeviceWedgeFailures.set(0)
      return
    }
    val count = consecutiveDeviceWedgeFailures.incrementAndGet()
    if (count >= MAX_CONSECUTIVE_DEVICE_WEDGE_FAILURES) {
      // A server relaunch is the right escalation for the DEVICE_WEDGE_SIGNATURES class too:
      // the exception below lacks the two-phrase non-recoverable signature, so neither the
      // rpcCall string match nor the session-log scan can arm the relaunch from it — without
      // this arm, the next trail inherits the wedge (the same poison-the-build mode the typed
      // arming closes). If a relaunch can't clear it (system_server truly dead), the armed
      // flag makes the next connect fail loudly instead.
      rpcClient.armNonRecoverableWedge()
      throw TrailblazeException(
        "Device unhealthy: $count consecutive GetScreenState " +
          "failures matched a UiAutomation-wedge signature (system_server appears dead). " +
          "Aborting trail — restart the emulator / device to recover. " +
          "Last failure: $detail",
      )
    }
  }

  companion object {
    /** Trace category for the host's view of a device screen capture. */
    private const val SCREEN_STATE_TRACE_CAT = "screenState"

    /** Trace category for the host's round trips to the on-device runner. */
    private const val RPC_TRACE_CAT = "rpc"

    private const val MAX_CONSECUTIVE_DEVICE_WEDGE_FAILURES = 3

    /** OkHttp's message when the server accepted a request and closed before replying. */
    private const val CONNECTION_DROPPED_MID_CALL = "unexpected end of stream"

    /** OkHttp's messages when a call never reached a listener, so the device cannot have run it. */
    private val CONNECT_FAILURE_SIGNATURES = listOf("Failed to connect", "Connection refused")

    /** Bound on the read that tells a live runner from a gone one after a failed call. */
    private const val RUNNER_ALIVE_PROBE_MS = 3_000L

    /** Substrings identifying a wedged-device failure vs a recoverable transient.
     *  Both `connect` and `disconnect` throw `RuntimeException` from the platform's
     *  `UiAutomation` lifecycle when the inner remote object is gone (`id=-1`); the
     *  on-device recovery shim should normally heal these, but if 3 in a row escape,
     *  trip the breaker and recycle the emulator rather than fail a trail after
     *  ~30s of futile re-warms against a dead `system_server`. */
    private val DEVICE_WEDGE_SIGNATURES = listOf(
      "Error while connecting UiAutomation",
      "Error while disconnecting UiAutomation",
      "DeadObjectException",
    )

    /**
     * Bound on waiting for the stream to produce a frame matching a tree capture. Covers the
     * quiet window + normal encode/transport latency with margin; a stream that can't match
     * within this falls back to one on-device screenshot RPC, so a busted stream costs about
     * as much as the pre-stream path per capture rather than wedging it.
     */
    private const val STREAM_FRAME_TIMEOUT_MS = 2_500L
  }

  /**
   * Set when a host-side tool finishes in an in-process session. Such a tool can stop the app over
   * adb (a force-stop before re-seeding its prefs, say), and the runner inside the app stops with it
   * without any RPC noticing. The next RPC would then read the dead runner as the app restarting
   * mid-call, call the step done and never send it.
   */
  private val appMayHaveStoppedOffRpc = AtomicBoolean(false)

  /**
   * Set while the in-process runner is known to be gone with the app's process, and cleared by the
   * restart. The restart waits for the first step that needs the runner: a seeded sign-in runs
   * several adb commands while the app is stopped, and one of them restarts the app's process on
   * its own, so restarting eagerly would start the runner for nothing.
   */
  private val runnerDown = AtomicBoolean(false)

  /**
   * The pid of the app process the runner lives in, read before the first host-side tool of each
   * runner's life. A host-side tool that relaunches the app leaves it running under a new pid with
   * no runner in it, so whether the app is running alone can't tell the runner is gone.
   */
  @Volatile private var runnerAppPid: String? = null

  override fun onHostLocalToolStarting() {
    if (restartRunnerAfterAppExit == null || runnerDown.get() || runnerAppPid != null) return
    runnerAppPid = appId?.let(appProcessId)?.takeIf { it.isNotBlank() }
  }

  override fun onHostLocalToolFinished() {
    if (restartRunnerAfterAppExit != null) appMayHaveStoppedOffRpc.set(true)
  }

  /** The runner just started, in a process whose pid is read before the next host-side tool. */
  private fun markRunnerRestarted() {
    runnerDown.set(false)
    runnerAppPid = null
  }

  /** The runner ended with the app's process; the next step that needs it starts it again. */
  private fun markRunnerDown() {
    runnerDown.set(true)
    runnerAppPid = null
  }

  /**
   * Whether the in-process runner is gone, folding in what the last host-side tool may have done to
   * the app: stopped it, or replaced its process. A `pidof` costs tens of milliseconds and runs only
   * around a host-side tool, a handful per trail.
   */
  private fun inProcessRunnerDown(): Boolean {
    if (restartRunnerAfterAppExit == null) return false
    if (appMayHaveStoppedOffRpc.getAndSet(false)) {
      val pkg = appId
      val pid = pkg?.let(appProcessId)
      val before = runnerAppPid
      if (pid != null && (pid.isBlank() || (before != null && pid != before))) markRunnerDown()
    }
    return runnerDown.get()
  }

  /**
   * Whether the runner's server still answers. A dropped connection or a refused forward says only
   * that one call failed; restarting a runner that is still alive would force-stop the app and lose
   * its screen, and calling a step done because its connection dropped would pass a step that never
   * ran. Asked of the server's `/ping`, not a screen read: a screen read fails during an Activity
   * transition or on a busy main thread with the process still alive.
   */
  private suspend fun runnerStillAnswers(): Boolean = rpcClient.answersPing(RUNNER_ALIVE_PROBE_MS)

  /** Starts the runner again when it is down; see [runnerDown]. */
  private suspend fun ensureInProcessRunnerUp(before: String) {
    val restart = restartRunnerAfterAppExit ?: return
    if (!inProcessRunnerDown()) return
    Console.log("[HostOnDeviceRpcAgent] restarting the in-process runner, which stopped with the app, before '$before'.")
    restartRunner(restart)
  }

  /**
   * Starts the runner again, unless the trail was stopped. Stop kills the runner, so a stopped run's
   * last screen read or step finds it gone; starting it again would relaunch the app, and force-stop
   * a newer run's app if one already holds the device.
   */
  private suspend fun restartRunner(restart: suspend () -> Unit) {
    DispatchCancellation.ensureTrailActive()
    currentCoroutineContext().ensureActive()
    restart()
    markRunnerRestarted()
  }

  override fun executeTool(
    tool: TrailblazeTool,
    context: TrailblazeToolExecutionContext,
    toolsExecuted: MutableList<TrailblazeTool>,
  ): TrailblazeToolResult {
    // Resolve `OtherTrailblazeTool` placeholders through the session's tool repo BEFORE the
    // `when` below. Static YAML deserialization (`TrailblazeToolJsonSerializer.deserialize`)
    // always produces `OtherTrailblazeTool` for tool names the static catalog doesn't know —
    // and "doesn't know" includes every dynamically-registered scripted tool registered via
    // `LazyYamlScriptedToolRegistration.addDynamicTools` (the #2749 inline scripted tools
    // declared on the trailmap's `target.tools:` list).
    //
    // Without this resolution, an OtherTrailblazeTool whose name maps to a registered
    // scripted tool fell into the `else` branch below and surfaced as
    // `"Unsupported tool type for RPC execution: OtherTrailblazeTool"`, even though the
    // session's `toolRepo` knew exactly how to dispatch it. Resolution is the contract every
    // host-driver dispatcher must honor (and the docstring on
    // `MaestroTrailblazeAgent.trailblazeToolRepo` already promises this).
    //
    // [BaseTrailblazeAgent.runTrailblazeTools] also resolves at its loop boundary via
    // `resolveDynamicTool`, but `executeTool` is reachable independently — e.g. from the
    // `runTrailblazeTools` `else` branch when the resolution there returns the original
    // (toolRepo returned the tool but it didn't match `HostLocalExecutableTrailblazeTool`),
    // and from any future caller that dispatches a tool without going through the batch
    // loop. Resolving here defends in depth.
    // Two senses of "resolved" appear in this method — disambiguated by name so a reader
    // (or grep) can tell them apart:
    //  - `repoResolvedTool` (this binding) = `OtherTrailblazeTool` → concrete tool, via the
    //    session's `trailblazeToolRepo.toolCallToTrailblazeTool(...)` (dynamic-tool dispatch).
    //  - `memoryResolvedTool` / `memoryResolvedExpanded` (below) = string-interpolated args via
    //    `interpolateMemoryInTool(...)` — applied ONLY on the host-local branches. RPC-routed
    //    tools are deliberately sent AS AUTHORED: the request carries a memory snapshot, the
    //    device seeds its per-request AgentMemory from it, and the device's own dispatch loop
    //    is the memory boundary there — so the device's tool log gets the raw + resolved pair
    //    and recordings regenerated from it keep their `{{var}}` tokens.
    val repoResolvedTool: TrailblazeTool = if (tool is OtherTrailblazeTool) {
      val repo = trailblazeToolRepo
      if (repo == null) {
        tool
      } else {
        try {
          repo.toolCallToTrailblazeTool(tool.toolName, tool.raw.toString())
        } catch (e: CancellationException) {
          throw e
        } catch (e: Exception) {
          // Unknown name in this repo (`IllegalStateException` from the `error(...)` branch
          // of `toolCallToTrailblazeTool`) or serializer mismatch (`SerializationException`).
          // Fall through with the original — the `else` branch below produces the
          // unsupported-tool error with the actual toolName visible for diagnostics.
          //
          // **Use `Console.info` (not `Console.log`)** so this diagnostic survives the
          // default CLI quiet mode (`CliInfrastructure.enableQuietMode()`). Without this,
          // an operator triaging a CI-mode "Unsupported tool type" failure would lose the
          // underlying repo-lookup exception — exactly the breadcrumb needed to
          // distinguish "tool genuinely not registered" from "registered but arg-schema
          // mismatch surfaced as SerializationException". See lead-dev review #1.
          Console.info(
            "[HostOnDeviceRpcTrailblazeAgent] Could not resolve '${tool.toolName}' via repo: " +
              "${e::class.simpleName}: ${e.message}",
          )
          tool
        }
      }
    } else {
      tool
    }
    return when (repoResolvedTool) {
      is ExecutableTrailblazeTool -> {
        toolsExecuted.add(repoResolvedTool)
        if (
          repoResolvedTool is HostLocalExecutableTrailblazeTool ||
          repoResolvedTool.requiresHostInstance() ||
          (repoResolvedTool.runsOverAdbAlone() && inProcessRunnerDown())
        ) {
          // Host-local tools (subprocess MCP, QuickJS scripted, cbot / dip-slot ADB tools,
          // `requiresHost = true` class-backed tools) run on the host JVM — so the host IS their
          // memory boundary: resolve here, dispatch resolved, log raw + resolved.
          val memoryResolvedTool = interpolateMemoryInTool(repoResolvedTool, memory, context.trailblazeDeviceInfo)
          executeHostLocalWithLogging(
            memoryResolvedTool,
            context,
            rawTool = repoResolvedTool.takeIf { it !== memoryResolvedTool },
          )
        } else {
          // RPC-routed: send AS AUTHORED — the device's dispatch loop resolves against the
          // seeded memory snapshot (see the "two senses of resolved" note above).
          executeToolViaRpc(repoResolvedTool, context.traceId)
        }
      }
      is DelegatingTrailblazeTool -> {
        executeDelegatingTool(repoResolvedTool, context, toolsExecuted) { expandedTool ->
          // Same host-local vs RPC fork per expanded subtool. Expanded subtools inherit any
          // `OtherTrailblazeTool` → repo resolution from the parent delegating tool — the
          // base's `runTrailblazeTools` loop resolved the parent before dispatching here, so
          // we don't re-run repo resolution on each expanded subtool.
          if (
            expandedTool is HostLocalExecutableTrailblazeTool ||
            expandedTool.requiresHostInstance() ||
            (expandedTool.runsOverAdbAlone() && inProcessRunnerDown())
          ) {
            // Host-local subtools must not be RPCed to the device — they read/write
            // host-side files and credentials that have no meaning on the device JVM.
            // Memory boundary is here (host) for them.
            val memoryResolvedExpanded = interpolateMemoryInTool(expandedTool, memory, context.trailblazeDeviceInfo)
            executeHostLocalWithLogging(
              memoryResolvedExpanded,
              context,
              rawTool = expandedTool.takeIf { it !== memoryResolvedExpanded },
            )
          } else {
            executeToolViaRpc(expandedTool, context.traceId)
          }
        }
      }
      // An in-process runner carries tools of its own (`androidTest_tap`, `androidTest_assertVisible`)
      // whose classes live in the test APK and never load on the host. A recorded call to one is
      // sent to the device as authored, where its own repo resolves it.
      is OtherTrailblazeTool if restartRunnerAfterAppExit != null -> {
        toolsExecuted.add(repoResolvedTool)
        executeToolViaRpc(repoResolvedTool, context.traceId)
      }
      else -> TrailblazeToolResult.Error.FatalError(
        // Diagnostic detail: when the unresolved tool was an `OtherTrailblazeTool`, surface
        // its `toolName` so a triager doesn't see a meaningless `OtherTrailblazeTool` token
        // and has to dig through the trail YAML to find which tool call failed.
        errorMessage = buildString {
          append("Unsupported tool type for RPC execution: ${repoResolvedTool::class.simpleName}")
          if (repoResolvedTool is OtherTrailblazeTool) {
            append(" (toolName='${repoResolvedTool.toolName}' — not registered in this session's ")
            append("tool repo as a class-backed or dynamic scripted tool)")
          }
        },
      )
    }
  }

  override suspend fun executeMaestroCommands(
    commands: List<Command>,
    traceId: TraceId?,
  ): TrailblazeToolResult {
    if (commands.isEmpty()) return TrailblazeToolResult.Success()
    // Forward maestro commands to the device via RPC by wrapping them in a MaestroTrailblazeTool.
    // This can be reached when recorded tools contain raw maestro commands or when the
    // nodeSelector tap path falls back to the maestro command path.
    Console.log(
      "[HostOnDeviceRpcAgent] Forwarding ${commands.size} maestro command(s) to device via RPC",
    )
    val maestroTool = MaestroTrailblazeTool(
      yaml = MaestroYamlSerializer.toYaml(commands, includeConfiguration = false),
    )
    // Sent as-is: the commands were built by a tool that already passed a memory boundary, and
    // any residual token resolves on the device (its dispatch loop interpolates the yaml string
    // against the seeded memory snapshot).
    return executeToolViaRpc(maestroTool, traceId)
  }

  override suspend fun executeNodeSelectorTap(
    nodeSelector: TrailblazeNodeSelector,
    longPress: Boolean,
    tapRoute: TapRouteOverride?,
    timeoutMs: Long?,
    hideKeyboardBefore: Boolean,
    traceId: TraceId?,
  ): TrailblazeToolResult? {
    // Accessibility-shaped selectors are recorded under the accessibility driver and must
    // dispatch via the on-device [AccessibilityTrailblazeAgent.executeNodeSelectorTap]
    // path — resolving against the live accessibility tree and dispatching a coordinate
    // gesture. Falling through to Maestro (the historical behavior here) loses the
    // selector's accessibility semantics and resolves against UiAutomator instead, which
    // is the bug we hit on Compose surfaces where a clickable wrapper sits over a
    // non-clickable text child (Sign in: clickable View → TextView "Sign in" with no
    // ACTION_CLICK). UiAutomator-shaped resolution silently picks the text child and
    // taps a node that doesn't fire the click handler.
    //
    // Send the recording's TapOnByElementSelector tool over RPC. On-device, when the
    // active agent is [AccessibilityTrailblazeAgent], the tool's [execute] override
    // re-enters [executeNodeSelectorTap] there and resolves the selector against the
    // live accessibility tree. No infinite loop — the host and device sides are
    // distinct agent instances.
    //
    // A pin travels as the one tool that carries it: ACTION_CLICK as `android_accessibilityClick`,
    // GESTURE as `android_gestureTap`. `tapOnElementBySelector` has no route field, so sending it
    // would drop the pin. The whole tree is checked because a `containsChild:` / `childOf:`
    // selector nests its match.
    if (nodeSelector.allDriverMatches().any { it is DriverNodeMatch.AndroidAccessibility }) {
      val tool = when {
        !longPress && tapRoute == TapRouteOverride.ACTION_CLICK ->
          AndroidAccessibilityClickTrailblazeTool(nodeSelector = nodeSelector, timeoutMs = timeoutMs)
        !longPress && tapRoute == TapRouteOverride.GESTURE ->
          AndroidGestureTapTrailblazeTool(nodeSelector = nodeSelector, timeoutMs = timeoutMs)
        else -> TapOnByElementSelector(
          nodeSelector = nodeSelector,
          longPress = longPress,
          timeoutMs = timeoutMs,
          hideKeyboardBefore = hideKeyboardBefore,
        )
      }
      return executeToolViaRpc(tool = tool, traceId = traceId)
    }
    // Non-accessibility-shaped selectors (Maestro-shaped, etc.) — let the caller fall
    // through to [executeMaestroCommands] via [super.execute]. Same behavior as before
    // for the instrumentation/Maestro flows.
    return null
  }

  /**
   * Dispatch a host-local tool (subprocess MCP, `requires_host: true`, etc.) and emit a
   * session-log entry for it (#2924). The base class's short-circuit covers the top-level
   * dispatch path uniformly, but this agent's [executeTool] also reaches host-local tools
   * via the `requiresHostInstance` annotation branch and the [DelegatingTrailblazeTool]
   * sub-tool expansion — both need their own log emit so every dispatch is visible to
   * recording and reports regardless of how it routed through the dispatcher.
   */
  private fun executeHostLocalWithLogging(
    tool: ExecutableTrailblazeTool,
    context: TrailblazeToolExecutionContext,
    /** The authored form when the host-side memory boundary rewrote [tool] — see [logToolExecution]. */
    rawTool: TrailblazeTool? = null,
  ): TrailblazeToolResult {
    val timeBeforeExecution = Clock.System.now()
    onHostLocalToolStarting()
    // Same exception-bypass guard as the base agent's host-local branch (#2924 review):
    // a thrown exception would otherwise skip `logToolExecution` and leave the dispatch
    // invisible. Convert non-cancellation throws into a `TrailblazeToolResult.Error` and
    // log it; re-throw `CancellationException` so coroutine cancellation still unwinds.
    // The host resolved this tool's memory tokens, so a sensitive value may now sit in its
    // arguments, and the adb transport logs a command line before running it (an `am broadcast`
    // built from resolved extras, say). Masked there for the length of the call.
    val sensitiveValues = sensitiveLiteralValues(memory)
    val result: TrailblazeToolResult = try {
      withDeviceCommandSecretsRedacted(sensitiveValues) {
        runBlocking { DispatchCancellation.linked(this) { tool.execute(context) } }
      }
    } catch (e: CancellationException) {
      throw e
    } catch (e: Throwable) {
      TrailblazeToolResult.Error.ExceptionThrown.fromThrowable(e, tool)
    }
    onHostLocalToolFinished()
    logToolExecution(
      tool = tool,
      timeBeforeExecution = timeBeforeExecution,
      context = context,
      result = result,
      // Same rationale as the `BaseTrailblazeAgent` host-local branch — flag for badging /
      // filtering so a developer reading the session log can tell at a glance whether the
      // dispatch ran on the host JVM (subprocess MCP, `requires_host`, host-preferred
      // composition primitive) or got routed to the device over RPC.
      dispatchedHostSide = true,
      rawTool = rawTool,
    )
    return result
  }

  private fun executeToolViaRpc(tool: TrailblazeTool, traceId: TraceId?): TrailblazeToolResult =
    // Detail rather than normal: the enclosing tool span already covers this call end to end, so
    // this only earns its place when you are asking where inside a tool the time went — how much of
    // it is the round trip to the device versus the host's own work around it.
    TrailblazeTracer.traceDetail("executeToolViaRpc", RPC_TRACE_CAT) {
      executeToolViaRpcUntraced(tool, traceId)
    }

  private fun executeToolViaRpcUntraced(tool: TrailblazeTool, traceId: TraceId?): TrailblazeToolResult {
    val timeBeforeExecution = Clock.System.now()
    // How many `TrailblazeToolLog`s the on-device dispatch emitted for this tool. Set from the
    // RPC response on the success path; stays 0 on any failure path (no device log to defer to,
    // so the host emit below stays the only record). Drives the host-side double-log skip below.
    // Atomic because it's written inside the `runBlocking` block and read after it returns: the
    // continuation happens to resume on the caller thread today, but a future `withContext` in
    // the block must not be able to silently break the cross-thread visibility of the count.
    val onDeviceToolLogCount = AtomicInteger(0)
    // The span the device's work belongs under, read on the dispatching thread. Null when the host
    // is not recording, and the device then records a trace of its own — the behavior that predates
    // the field.
    val dispatchTraceParent = TrailblazeTracer.currentTraceContext()?.toTraceParent()
    // Linked to the trail's Job, as a host-local dispatch is: Stop cancels the trail, and that must
    // end this call too, runner restart and resend included, or a stopped run could bring the
    // runner back and run its step anyway.
    val result: TrailblazeToolResult = runBlocking { DispatchCancellation.linked(this) dispatch@{
      try {
        ensureInProcessRunnerUp(tool::class.simpleName ?: "a step")
        // The tool is encoded AS AUTHORED (memory tokens intact) — the device's dispatch loop
        // resolves them against the memory snapshot below, so the device-side tool log carries
        // the raw + resolved pair and recordings keep their tokens.
        // Bare tool-wrapper list (`- <toolName>:`), decoded on-device via decodeTrailOrToolEnvelope
        // → decodeTools — never the legacy list-shape trail parser.
        val yaml = trailblazeYaml.encodeTools(listOf(fromTrailblazeTool(tool)))

        // Reuse the host's top-level session ID so every per-tool RunYamlRequest writes
        // into the same on-device session directory. When pulled back to the host via
        // `adb pull`, those logs merge into the same host-side session directory instead
        // of scattering into one `session_<millis>/` directory per tool call. Session
        // start/end logs are still suppressed — the host owns the session lifecycle.
        val request = runYamlRequestTemplate.copy(
          yaml = yaml,
          traceId = traceId,
          // Per-tool RPCs block the HTTP response on on-device completion. Explicit for
          // clarity even though the request default is also true.
          awaitCompletion = true,
          config = runYamlRequestTemplate.config.copy(
            overrideSessionId = sessionProvider.invoke().sessionId,
            sendSessionStartLog = false,
            sendSessionEndLog = false,
          ),
          // Snapshot host memory so the device's dispatch boundary can resolve {{var}}/${var}
          // tokens in the (raw) tool args, and on-device tools can read keys directly. The
          // sensitive-key set rides along so the device's per-request AgentMemory keeps those
          // values redacted in its own logs (they'd otherwise arrive unmarked and log cleartext).
          memorySnapshot = memory.variables.toMap(),
          sensitiveMemoryKeys = memory.sensitiveKeys.toList(),
          // Args are immutable per run, so unlike memory they only travel host → device (no
          // round-trip back). The device rehydrates them verbatim to resolve {{args.x}} tokens.
          argsSnapshot = TrailArgBinder.encodeProvided(memory.args),
          sensitiveArgNames = memory.sensitiveArgNames.toList(),
          traceParent = dispatchTraceParent,
          // The host owns this session's start log, so the device learns the trail from this.
          sessionStart = SessionStartRecords.forSession(sessionProvider.invoke().sessionId),
        )

        val name = tool::class.simpleName ?: "unknown"
        when (val first: RpcResult<RunYamlResponse> = rpcClient.rpcCall(request)) {
          is RpcResult.Success -> {
            // Apply the on-device agent's post-execution memory back into the host's shared
            // instance so writes from on-device tools (including TS handlers) are visible to
            // subsequent host-side or RPC dispatches.
            applyMemorySnapshot(first.data.memorySnapshot, first.data.memoryDeletions, first.data.sensitiveMemoryKeys)
            onDeviceToolLogCount.set(first.data.onDeviceToolLogCount)
            toToolResult(name, first, timeBeforeExecution)
          }
          is RpcResult.Failure -> {
            val firstDetail = first.message + (first.details?.let { " | $it" } ?: "")
            // A runner that still answers lost only its connection, so it gets the ordinary re-warm
            // and retry below. Only a runner that is gone means the app's process ended.
            if (restartRunnerAfterAppExit != null && first.errorType == RpcResult.ErrorType.NETWORK_ERROR &&
              !runnerStillAnswers()
            ) {
              if (first.details?.contains(CONNECTION_DROPPED_MID_CALL) == true) {
                // The device took the call and its process ended before replying. Running it again
                // would end it again; the runner restarts when a step next needs it. That is the
                // step's job only for a broadcast the app answers by restarting: any other step
                // that ends the app's process crashed it, which must fail the gate.
                Console.log("[HostOnDeviceRpcAgent] '$name' ended the app's process and the in-process runner with it.")
                markRunnerDown()
                return@dispatch if (tool.mayRestartApp()) {
                  TrailblazeToolResult.Success(
                    message = "The app's process restarted during '$name', taking the in-process " +
                      "runner with it.",
                  )
                } else {
                  TrailblazeToolResult.Error.ExceptionThrown(
                    errorMessage = "The app's process ended during '$name', taking the in-process " +
                      "runner with it. Only a broadcast is expected to restart the app, " +
                      "so this looks like a crash: check the device log.",
                  )
                }
              }
              if (first.details?.let { d -> CONNECT_FAILURE_SIGNATURES.any { it in d } } != true) {
                // Sent, then lost (a read timeout on a long step, say): the device may already have
                // run it, and restarting the app to run it again could do it twice.
                Console.log("[HostOnDeviceRpcAgent] '$name' lost the in-process runner after it was sent ($firstDetail).")
                markRunnerDown()
                return@dispatch rpcFailureToolResult(
                  errorMessage = "'$name' lost the in-process runner after it was sent ($firstDetail). " +
                    "Not sent again, since it may already have run.",
                  firstDetail,
                )
              }
              Console.log("[HostOnDeviceRpcAgent] '$name' lost the in-process runner ($firstDetail); restarting it.")
              restartRunner(restartRunnerAfterAppExit)
              return@dispatch when (val retry: RpcResult<RunYamlResponse> = rpcClient.rpcCall(request)) {
                is RpcResult.Success -> {
                  applyMemorySnapshot(retry.data.memorySnapshot, retry.data.memoryDeletions, retry.data.sensitiveMemoryKeys)
                  onDeviceToolLogCount.set(retry.data.onDeviceToolLogCount)
                  toToolResult(name, retry, timeBeforeExecution)
                }
                is RpcResult.Failure -> {
                  val retryDetail = retry.message + (retry.details?.let { " | $it" } ?: "")
                  rpcFailureToolResult(
                    errorMessage = "RPC call failed for '$name': $firstDetail | after runner restart: $retryDetail",
                    firstDetail,
                    retryDetail,
                  )
                }
              }
            }
            Console.log(
              "[HostOnDeviceRpcAgent] RPC failed for '$name' (${first.errorType}): " +
                "${first.message}" +
                (first.details?.let { "\n  Details: $it" } ?: "") +
                "\n  Re-warming connection and retrying once.",
            )
            try {
              rpcClient.waitForReady(
                timeoutMs = reWarmTimeoutMs,
                pollIntervalMs = reWarmPollIntervalMs,
                requireAndroidAccessibilityService = requireAndroidAccessibilityServiceOnRewarm,
              )
            } catch (e: Exception) {
              val detail = "re-warm failed: ${e.message} | runner_pid=${rpcClient.probeRunnerPid()}"
              Console.log("[HostOnDeviceRpcAgent] $detail")
              return@dispatch rpcFailureToolResult(
                errorMessage = "RPC call failed for '$name': $firstDetail | $detail",
                firstDetail,
                detail,
              )
            }
            when (val retry: RpcResult<RunYamlResponse> = rpcClient.rpcCall(request)) {
              is RpcResult.Success -> {
                applyMemorySnapshot(retry.data.memorySnapshot, retry.data.memoryDeletions, retry.data.sensitiveMemoryKeys)
                onDeviceToolLogCount.set(retry.data.onDeviceToolLogCount)
                toToolResult(name, retry, timeBeforeExecution)
              }
              is RpcResult.Failure -> {
                val retryDetail = retry.message + (retry.details?.let { " | $it" } ?: "")
                Console.log(
                  "[HostOnDeviceRpcAgent] RPC retry for '$name' after re-warm still failed " +
                    "(${retry.errorType}): ${retry.message}" +
                    (retry.details?.let { "\n  Details: $it" } ?: ""),
                )
                rpcFailureToolResult(
                  errorMessage = "RPC call failed for '$name': $firstDetail | retry: $retryDetail",
                  firstDetail,
                  retryDetail,
                )
              }
            }
          }
        }
      } catch (e: Exception) {
        // Propagate cancellation so structured-concurrency teardown (agent abort, driver
        // disconnect, session shutdown) isn't silently converted into a tool error.
        if (e is kotlinx.coroutines.CancellationException) throw e
        Console.log(
          "[HostOnDeviceRpcAgent] Exception executing '${tool::class.simpleName}': " +
            "${e::class.simpleName}: ${e.message}",
        )
        TrailblazeToolResult.Error.ExceptionThrown(
          errorMessage = "Tool execution failed: ${e::class.simpleName}: ${e.message}",
        )
      }
    } }

    // Emit a host-side TrailblazeToolLog ONLY when the on-device dispatch did not already log
    // the tool itself. The device runs the tool through
    // `BaseTrailblazeAgent.runTrailblazeTools` and, in the common path, emits its own
    // `TrailblazeToolLog` (pulled back to the host via `adb pull` and merged into this same
    // session). When that happens, a second host-side emit produces a duplicate: the one
    // execution renders twice in the session report — an outer "host" span and a nested
    // "on-device" span (#3818). So we defer to the device's log whenever it reported one
    // (`onDeviceToolLogCount > 0`).
    //
    // The host emit is still required for the catch-all case where the on-device dispatch
    // emits NO `TrailblazeToolLog` — e.g. a tool whose `execute` short-circuits straight to
    // `agent.executeNodeSelectorTap` (accessibility nodeSelectors), which only produces
    // driver-action logs and routes around the device's tool-log emit site. Without the host
    // emit there, `TrailblazeRecordingGenerator` would see zero `TrailblazeToolLog` files in
    // the host's session dir (only the unrecordable `DelegatingTrailblazeToolLog`) and emit
    // empty recordings. `dispatchedHostSide = false` is the correct semantic — the actual
    // dispatch ran on device.
    if (traceId != null && onDeviceToolLogCount.get() == 0) {
      logToolExecution(
        tool = tool,
        timeBeforeExecution = timeBeforeExecution,
        traceId = traceId,
        result = result,
        dispatchedHostSide = false,
      )
    } else if (traceId != null) {
      // Breadcrumb for the #3818 skip path: when a tool appears once (or not at all) in a
      // report, this line tells a triager the host *deliberately* deferred to the device's
      // own tool log rather than dropping its own. Mirrors the diagnostic-log density of the
      // rest of this dispatcher (every RPC state transition logs).
      Console.log(
        "[HostOnDeviceRpcAgent] Skipping host-side tool log for " +
          "'${tool::class.simpleName}' — on-device dispatch already emitted " +
          "${onDeviceToolLogCount.get()} tool log(s) (#3818).",
      )
    }
    return result
  }

  private fun toToolResult(
    name: String,
    rpcResult: RpcResult.Success<RunYamlResponse>,
    timeBeforeExecution: kotlinx.datetime.Instant,
  ): TrailblazeToolResult {
    val durationMs =
      Clock.System.now().toEpochMilliseconds() - timeBeforeExecution.toEpochMilliseconds()
    return when (rpcResult.data.success) {
      true -> {
        Console.log("[HostOnDeviceRpcAgent] '$name' completed in ${durationMs}ms")
        // Mirror the on-device tool's `Success.message` / `structuredContent` back through the
        // host-side result so scripted-tool authors composing this tool via `client.callTool(...)`
        // observe the same payload regardless of whether dispatch routed host-side or via RPC.
        // Before the response envelope grew these fields, the message/structuredContent were
        // silently discarded here — that gap is what `prefersHostSideForCallback` used to paper
        // over for `android_adbShell` / `android_sendBroadcast` / `mobile_listInstalledApps`.
        TrailblazeToolResult.Success(
          message = rpcResult.data.toolMessage,
          structuredContent = rpcResult.data.toolStructuredContent,
        )
      }
      false -> {
        val errorMessage =
          rpcResult.data.errorMessage ?: "Tool '$name' execution failed on-device"
        if (rpcClient.noteIfNonRecoverableWedge(rpcResult.data)) {
          // The on-device handler tagged this failure as the non-recoverable UiAutomation
          // wedge (its in-process reconnect retry already failed). The typed arm above
          // schedules a server relaunch before the NEXT session — the wedge arrives as an
          // HTTP-200 `success=false` response the string-matching breaker in
          // `OnDeviceRpcClient.rpcCall` never sees. FatalError (not ExceptionThrown) aborts
          // THIS trail: every subsequent dispatch is guaranteed to fail identically, so
          // feeding the error back to the LLM (or to self-heal) just burns the call budget
          // against a dead server.
          //
          // Console.info (this file's CI-triage convention): when the device's errorMessage
          // lacks the wedge phrases — the exact case the typed field exists for — no other
          // operator-visible line attributes the next trail's force-restart to this dispatch,
          // and Console.log is suppressed in the CLI's default quiet mode.
          Console.info(
            "[HostOnDeviceRpcAgent] '$name' failed with the non-recoverable UiAutomation " +
              "wedge — arming on-device server relaunch and aborting the trail.",
          )
          TrailblazeToolResult.Error.FatalError(errorMessage = errorMessage)
        } else {
          TrailblazeToolResult.Error.ExceptionThrown(errorMessage = errorMessage)
        }
      }
      null -> TrailblazeToolResult.Error.ExceptionThrown(
        errorMessage = "On-device server returned null success inline for '$name' — " +
          "contract violation for awaitCompletion=true (expected true/false, got null)",
      )
    }
  }

  /**
   * Terminal result for an RPC-level [RpcResult.Failure]. When any of [details] carries the
   * non-recoverable UiAutomation wedge signature, the relaunch breaker has already been armed by
   * the string match at the [OnDeviceRpcClient.rpcCall] chokepoint — return
   * [TrailblazeToolResult.Error.FatalError] so this trail aborts too, keeping arm-and-abort
   * consistent with the typed HTTP-200 path in [toToolResult]. Any other failure stays
   * LLM-retryable as a plain ExceptionThrown.
   */
  private fun rpcFailureToolResult(
    errorMessage: String,
    vararg details: String?,
  ): TrailblazeToolResult.Error = if (
    details.any { UiAutomationHandleErrors.isNonRecoverableStaleHandleSignature(it) }
  ) {
    TrailblazeToolResult.Error.FatalError(errorMessage = errorMessage)
  } else {
    TrailblazeToolResult.Error.ExceptionThrown(errorMessage = errorMessage)
  }

  /** Merges the device's post-execution snapshot into this agent's [memory] then applies explicit deletes. */
  private fun applyMemorySnapshot(
    deviceSnapshot: Map<String, String>,
    deletions: List<String>,
    sensitiveKeys: List<String>,
  ) {
    // Merge (device wins on conflict) rather than replace. An on-device tool's per-request
    // AgentMemory is seeded from the host push at request start, so its returned snapshot SHOULD
    // carry back every host key — but some dispatch paths (e.g. a scripted tool whose request
    // resets the on-device session) return a snapshot that drops host-set keys, and a plain
    // clear()+putAll() then WIPED them, losing a value a later ${var} step needed (the "typed ''"
    // empty-input false green). Preserving host-only keys keeps cross-step remembers alive;
    // device-written keys still overwrite on conflict. [deletions] then removes keys an on-device
    // tool EXPLICITLY deleted — merge alone can't distinguish those from a merely-omitted key.
    // [sensitiveKeys] re-marks device-side `rememberSensitive` keys so host-side logging keeps
    // redacting them after the merge (the snapshot map itself carries no sensitivity marking).
    // Mark BEFORE storing the values (mirroring `AgentMemory.storeSensitive`): a reader that
    // snapshots `variables` and filters against `sensitiveKeys` must never observe a returned
    // secret in the window before its marker lands.
    memory.mergeDeviceMemory(deviceSnapshot, deletions, sensitiveKeys)
  }
}

/**
 * Merges what a device's memory held after an RPC into [this]: sensitivity markers, then values
 * (device wins), then the keys the device explicitly deleted. See [HostOnDeviceRpcTrailblazeAgent].
 */
internal fun AgentMemory.mergeDeviceMemory(
  deviceSnapshot: Map<String, String>,
  deletions: List<String>,
  sensitiveKeys: List<String>,
) {
  sensitiveKeys.forEach { markSensitive(it) }
  variables.putAll(deviceSnapshot)
  deletions.forEach { variables.remove(it) }
}

/**
 * A tool whose ordinary effect can end the app's process: a broadcast the app may answer by
 * restarting itself. Not a launch: in-process, every launch form relaunches the entry Activity in
 * the running process, so a launch that ends the process crashed it.
 */
private fun TrailblazeTool.mayRestartApp(): Boolean = this is AndroidSendBroadcastTrailblazeTool

/**
 * A tool that only runs adb commands (shell, broadcast, permission grant, package list), so the
 * host can run it over adb while the in-process runner is down. The device runs it otherwise: from
 * inside the app it skips an adb round trip.
 */
private fun TrailblazeTool.runsOverAdbAlone(): Boolean =
  this is AdbShellTrailblazeTool ||
    this is AndroidSendBroadcastTrailblazeTool ||
    this is AndroidGrantPermissionsTrailblazeTool ||
    this is ListInstalledAppsTrailblazeTool
