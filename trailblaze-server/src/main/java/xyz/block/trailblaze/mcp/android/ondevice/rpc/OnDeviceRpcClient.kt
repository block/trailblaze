package xyz.block.trailblaze.mcp.android.ondevice.rpc

import kotlinx.coroutines.delay
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.SerializationException
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDevicePort.getTrailblazeOnDeviceSpecificPort
import xyz.block.trailblaze.llm.RunYamlResponse
import xyz.block.trailblaze.logs.client.TrailblazeJsonInstance
import xyz.block.trailblaze.mcp.android.ondevice.rpc.RpcRequest.Companion.toRpcPath
import xyz.block.trailblaze.mcp.utils.HttpRequestUtils
import xyz.block.trailblaze.mcp.utils.HttpRequestUtils.HttpRpcException
import xyz.block.trailblaze.util.AndroidHostAdbUtils
import xyz.block.trailblaze.util.UiAutomationHandleErrors
import xyz.block.trailblaze.transport.AndroidWireTransport
import xyz.block.trailblaze.transport.AndroidWireTransportMode
import java.io.IOException
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * This is a pseudo-RPC client that communicates with the on-device server.
 */
class OnDeviceRpcClient(
  private val trailblazeDeviceId: TrailblazeDeviceId,
  private val sendProgressMessage: (String) -> Unit = {},
  /**
   * Circuit breaker fired whenever ANY RPC failure carries the non-recoverable UiAutomation
   * stale-handle signature. Every synchronous on-device RPC flows through [rpcCall], so arming
   * here — at the single chokepoint — lets the host relaunch the on-device server at the source,
   * covering paths (e.g. `launchApp` pre-actions) that surface a wedge before the per-call-site
   * arming the V3/host-agent runners do in their finally blocks could ever see it. `@PublishedApi
   * internal` (not `private`) because [rpcCall] is a public `inline` fun and references it from
   * inlined call sites. Defaulted to a no-op so callers that don't manage server recovery are
   * unaffected.
   */
  @PublishedApi
  internal val onNonRecoverableWedge: () -> Unit = {},
) : AutoCloseable {

  /**
   * Arms [onNonRecoverableWedge] when either the failure [message] or its [details] carries the
   * terminal non-recoverable stale-handle signature. Both are checked because the signature can
   * land in `message` (handler-caught path) or `details` (HTTP-error path). The matcher requires
   * two distinct phrases, so an ordinary failure can't trip it. `@PublishedApi internal` for the
   * same inline-visibility reason as [onNonRecoverableWedge].
   */
  @PublishedApi
  internal fun noteIfNonRecoverableWedge(message: String?, details: String?) {
    if (UiAutomationHandleErrors.isNonRecoverableStaleHandleSignature(message) ||
      UiAutomationHandleErrors.isNonRecoverableStaleHandleSignature(details)
    ) {
      onNonRecoverableWedge()
    }
  }

  /**
   * Arms the circuit breaker from a signal that isn't a string match on an RPC failure —
   * e.g. `:trailblaze-host`'s GetScreenState circuit breaker, which decides the device is
   * wedged from repeated failures rather than from any single message.
   *
   * Public (not `@PublishedApi internal` like [onNonRecoverableWedge]) so `:trailblaze-host` can
   * reach it across the module boundary — it is the bridge to the otherwise-inline-only breaker.
   * Call sites that hold an inline [RunYamlResponse] should use the typed
   * [noteIfNonRecoverableWedge] overload instead of reading the field and calling this by hand.
   */
  fun armNonRecoverableWedge() {
    onNonRecoverableWedge()
  }

  /**
   * Typed twin of the string-matching [noteIfNonRecoverableWedge]: arms the breaker when an
   * `awaitCompletion = true` [RunYamlResponse] is tagged [RunYamlResponse.nonRecoverableWedge]
   * at the source, and returns whether it armed so the call site can shape its own terminal
   * result (FatalError, `recoverable = false`, …).
   *
   * A wedge tagged this way arrives as an `RpcResult.Success` carrying `success = false` — the
   * per-call `Failure`-arm string matches in [rpcCall] never see it (no failure body
   * deserializes on a Success). Every call site that inspects an inline [RunYamlResponse]
   * failure must route through this single reader rather than hand-inlining the field check:
   * a hand-rolled site that forgets to arm is exactly how the host-agent path missed the wedge
   * the first time (trailblaze-android-pr/2712).
   */
  fun noteIfNonRecoverableWedge(response: RunYamlResponse): Boolean {
    if (response.nonRecoverableWedge) {
      onNonRecoverableWedge()
    }
    return response.nonRecoverableWedge
  }

  /**
   * Single source of truth for the on-device HTTP server's port. Computed once from
   * [trailblazeDeviceId] so [baseUrl] and the ADB-forward recovery in
   * [recoverFromNetworkError] can't drift.
   */
  @PublishedApi
  internal val port: Int = trailblazeDeviceId.getTrailblazeOnDeviceSpecificPort()

  @PublishedApi
  internal val baseUrl = "http://localhost:$port"

  @PublishedApi
  internal val httpRequestUtils: HttpRequestUtils = HttpRequestUtils(
    baseUrl = baseUrl,
  )

  @PublishedApi
  internal val webSocketClient = OnDeviceRpcWebSocketClient(baseUrl)

  /**
   * Generic RPC call function that handles request serialization, routing, and response deserialization.
   * Wraps the result in RpcResult to handle errors gracefully.
   * 
   * This is the primary way to make RPC calls. Any type implementing [RpcRequest] can be used.
   * The response type is automatically inferred from the request type.
   * 
   * @param TResponse The response type (automatically inferred from the request)
   * @param TRequest The request type (must implement RpcRequest<TResponse>)
   * @param request The request object
   * @return RpcResult containing either the deserialized response or error information
   * 
   * Example:
   * ```
   * // Response type is automatically inferred!
   * val result = rpcCall(DeviceStatusRequest) // Returns RpcResult<DeviceStatusResponse>
   * when (result) {
   *   is RpcResult.Success -> println(result.data)
   *   is RpcResult.Failure -> println(result.message)
   * }
   * ```
   */
  suspend inline fun <reified TResponse : Any, reified TRequest : RpcRequest<TResponse>> rpcCall(
    request: TRequest
  ): RpcResult<TResponse> {
    val urlPath = TRequest::class.toRpcPath()
    val fullUrl = "$baseUrl$urlPath"
    val methodName = TRequest::class.simpleName

    return try {
      if (AndroidWireTransport.mode != AndroidWireTransportMode.JSON) {
        tryBinaryRpc<TResponse>(
          request = request,
          urlPath = urlPath,
        )?.let { binaryResult ->
          return binaryResult
        }
      }
      val jsonInputString = TrailblazeJsonInstance.encodeToString(request)
      val responseJson = httpRequestUtils.postRequest(
        urlPath = urlPath,
        jsonPostBody = jsonInputString,
        requestTimeoutMs = request.requestTimeoutMs,
      )
      val response: TResponse = TrailblazeJsonInstance.decodeFromString(responseJson)
      RpcResult.Success(response)
    } catch (e: CancellationException) {
      throw e
    } catch (e: HttpRpcException) {
      RpcResult.Failure(
        errorType = RpcResult.ErrorType.HTTP_ERROR,
        message = "Server error during RPC call: ${e.message}",
        details = e.responseBody,
        method = methodName,
        url = fullUrl
      ).also { noteIfNonRecoverableWedge(it.message, it.details) }
    } catch (e: SerializationException) {
      RpcResult.Failure(
        errorType = RpcResult.ErrorType.SERIALIZATION_ERROR,
        message = "Failed to serialize/deserialize RPC data",
        details = e.message,
        method = methodName,
        url = fullUrl
      ).also { noteIfNonRecoverableWedge(it.message, it.details) }
    } catch (e: IOException) {
      // ADB forward can drop silently mid-session on Android — every RPC over this client
      // eats the same IOException when that happens. Diagnose-and-heal here so the next
      // call has a fresh tunnel, and fold a structured port-presence note into details so
      // future triage can tell at a glance whether the silent-drop hypothesis was
      // load-bearing for this particular failure (PRESENT vs ABSENT).
      val recoveryNote = recoverFromNetworkError()
      RpcResult.Failure(
        errorType = RpcResult.ErrorType.NETWORK_ERROR,
        message = "Network error during RPC call",
        details = listOfNotNull(e.message, recoveryNote).joinToString(" | "),
        method = methodName,
        url = fullUrl
      ).also { noteIfNonRecoverableWedge(it.message, it.details) }
    } catch (e: Exception) {
      RpcResult.Failure(
        errorType = RpcResult.ErrorType.UNKNOWN_ERROR,
        message = "RPC call failed: ${e.message}",
        details = e.stackTraceToString(),
        method = methodName,
        url = fullUrl
      ).also { noteIfNonRecoverableWedge(it.message, it.details) }
    }
  }

  /**
   * Uses the persistent, typed protobuf WebSocket for every on-device RPC. `null` means no bytes
   * were sent and the caller may safely fall back to HTTP/JSON (older runner or not-yet-listening
   * server).
   */
  @PublishedApi
  internal suspend fun <TResponse : Any> tryBinaryRpc(
    request: RpcRequest<*>,
    urlPath: String,
  ): RpcResult<TResponse>? {
    val timeoutMs = request.requestTimeoutMs ?: DEFAULT_REQUEST_TIMEOUT_MS
    val attempt = try {
      webSocketClient.call(request, timeoutMs)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      return RpcResult.Failure(
        errorType = RpcResult.ErrorType.NETWORK_ERROR,
        message = "Binary RPC failed: ${e.message}",
        details = e.stackTraceToString(),
        method = request::class.simpleName,
        url = "$baseUrl$urlPath",
      ).also { noteIfNonRecoverableWedge(it.message, it.details) }
    }

    return when (attempt) {
      is OnDeviceRpcWebSocketClient.Attempt.FallbackToHttp -> {
        if (AndroidWireTransport.mode == AndroidWireTransportMode.AUTO) {
          null
        } else {
          RpcResult.Failure(
            errorType = RpcResult.ErrorType.NETWORK_ERROR,
            message = "Protobuf RPC transport is unavailable",
            method = request::class.simpleName,
            url = "$baseUrl$urlPath",
          )
        }
      }
      is OnDeviceRpcWebSocketClient.Attempt.Failure ->
        attempt.failure.also { noteIfNonRecoverableWedge(it.message, it.details) }
      is OnDeviceRpcWebSocketClient.Attempt.Success<*> -> {
        @Suppress("UNCHECKED_CAST")
        RpcResult.Success(attempt.value as TResponse)
      }
    }
  }

  /**
   * Convenience method for RPC calls with success and failure callbacks.
   * This is more ergonomic than manually handling RpcResult in a when expression.
   * 
   * @param TResponse The response type (automatically inferred from the request)
   * @param TRequest The request type (must implement RpcRequest<TResponse>)
   * @param request The request object
   * @param onSuccess Callback invoked with the response data when successful
   * @param onFailure Callback invoked with the failure when the call fails
   * 
   * Example:
   * ```
   * rpcCall(
   *   request = runYamlRequest,
   *   onSuccess = { response -> println("Started: ${response.message}") },
   *   onFailure = { failure -> println("Error: ${failure.message}") }
   * )
   * ```
   */
  suspend inline fun <reified TResponse : Any, reified TRequest : RpcRequest<TResponse>> rpcCall(
    request: TRequest,
    crossinline onSuccess: (TResponse) -> Unit,
    crossinline onFailure: (RpcResult.Failure) -> Unit
  ) {
    when (val result = rpcCall(request)) {
      is RpcResult.Success -> onSuccess(result.data)
      is RpcResult.Failure -> onFailure(result)
    }
  }

  /**
   * Polls [GetScreenStateRequest] until it succeeds, proving the device is ready to serve real
   * RPC calls: HTTP server is up, the accessibility service is actually bound (when
   * [requireAndroidAccessibilityService] is true), and the window is populated. Subsumes the older weaker
   * checks (ping the HTTP server, or check that the service instance field is set) which could
   * return "ready" while the very first real `GetScreenState` still failed — the root cause of
   * the flakiness that retry loops used to paper over.
   *
   * Uses a minimal payload (no screenshot, no annotation) since we only care that the call
   * returns `Success`. The first call after a fresh instrumentation launch can take several
   * seconds while the accessibility service binds; on a warm connection it returns in ms.
   *
   * @param requireAndroidAccessibilityService When true, the on-device handler returns `Failure` unless the
   *   accessibility service is bound — preventing UiAutomator fallback from faking readiness
   *   on accessibility-driver flows. Callers using `ANDROID_ONDEVICE_ACCESSIBILITY` MUST set
   *   this true; instrumentation-driver flows should leave it false.
   */
  @OptIn(ExperimentalTime::class)
  suspend fun waitForReady(
    // Default of 60s reflects the real cold-start cost on CI emulators: instrumentation launch,
    // app install, HTTP server listener, and accessibility service binding can collectively
    // exceed 30s. Callers with a faster-known-good path can pass a tighter budget.
    timeoutMs: Long = 60_000L,
    pollIntervalMs: Long = 500L,
    requireAndroidAccessibilityService: Boolean = false,
  ) {
    val startMs = Clock.System.now().toEpochMilliseconds()
    val probe = GetScreenStateRequest(
      includeScreenshot = false,
      includeAnnotatedScreenshot = false,
      requireAndroidAccessibilityService = requireAndroidAccessibilityService,
    )
    var attempt = 0
    // Prefer a real server Failure message over a probe-timeout synthetic message — a server
    // that always 500s is far more informative to operators than "probe timed out".
    var lastRpcFailure: String? = null
    var lastTimeoutMs: Long? = null
    var nextProgressMs = PROGRESS_REPORT_INTERVAL_MS
    while (true) {
      attempt++
      val elapsedAtProbeStart = Clock.System.now().toEpochMilliseconds() - startMs
      val remainingMs = timeoutMs - elapsedAtProbeStart
      if (remainingMs <= 0L) {
        // Budget already exhausted — don't bother probing, just report.
        throw IOException(
          "Device not ready after ${elapsedAtProbeStart}ms (${attempt - 1} probe(s)): " +
            (lastRpcFailure ?: lastTimeoutMs?.let { "probe timed out after ${it}ms" } ?: "no response"),
        )
      }
      // HttpRequestUtils's default request timeout (300s) would swallow our overall readiness
      // budget if a server accepts the TCP connection but never responds. Cap each probe at
      // the remaining budget (bounded by PROBE_MAX_MS) so `timeoutMs` is actually honored.
      val probeBudgetMs = minOf(remainingMs, PROBE_MAX_MS)
      val result = withTimeoutOrNull(probeBudgetMs) { rpcCall(probe) }
      when (result) {
        is RpcResult.Success -> {
          val elapsedMs = Clock.System.now().toEpochMilliseconds() - startMs
          sendProgressMessage("Device ready after ${elapsedMs}ms ($attempt probe(s))")
          return
        }
        is RpcResult.Failure -> {
          lastRpcFailure = result.message + (result.details?.let { " | $it" } ?: "")
        }
        null -> {
          lastTimeoutMs = probeBudgetMs
        }
      }
      val elapsedMs = Clock.System.now().toEpochMilliseconds() - startMs
      if (elapsedMs >= timeoutMs) {
        throw IOException(
          "Device not ready after ${elapsedMs}ms ($attempt probe(s)): " +
            (lastRpcFailure ?: lastTimeoutMs?.let { "probe timed out after ${it}ms" } ?: "no response"),
        )
      }
      // First probe usually succeeds in ms on a warm connection; only emit progress on a
      // genuine cold start so we don't spam the log with a line per attempt.
      if (elapsedMs >= nextProgressMs) {
        sendProgressMessage("Waiting for device... (${elapsedMs}ms, $attempt probe(s))")
        nextProgressMs += PROGRESS_REPORT_INTERVAL_MS
      }
      delay(pollIntervalMs)
    }
  }

  /**
   * Diagnose-and-heal for [RpcResult.ErrorType.NETWORK_ERROR] failures. Delegates the adb
   * subprocess work to [AndroidHostAdbUtils.diagnoseAndReAdbPortForward] and translates its
   * boolean pre-recovery state into a short structured note (`host_forward=PRESENT|ABSENT|
   * UNKNOWN, re-forwarded`) suitable for inclusion in [RpcResult.Failure.details], so future
   * triage can tell at a glance whether the silent-drop hypothesis was load-bearing for that
   * particular failure.
   *
   * Public so the inline [rpcCall] can invoke it from its `IOException` branch — every
   * Android RPC over this client benefits.
   */
  fun recoverFromNetworkError(): String {
    val portStatus = when (
      AndroidHostAdbUtils.diagnoseAndReAdbPortForward(
        deviceId = trailblazeDeviceId,
        localPort = port,
        remotePort = port,
      )
    ) {
      true -> "PRESENT"
      false -> "ABSENT"
      null -> "UNKNOWN"
    }
    val runnerPidStatus = probeRunnerPid()
    return "host_forward=$portStatus, re-forwarded, runner_pid=$runnerPidStatus"
  }

  /**
   * Disambiguates host-side eviction (runner pid present) from on-device death (pid absent).
   * Public so the host-side re-warm-fail branches can include it in their failure note.
   */
  fun probeRunnerPid(): String {
    val output = AndroidHostAdbUtils.execAdbShellCommandWithTimeout(
      deviceId = trailblazeDeviceId,
      args = listOf("pidof", RUNNER_PACKAGE),
    ) ?: return "UNKNOWN"
    val trimmed = output.trim()
    return if (trimmed.isEmpty()) "ABSENT" else trimmed
  }

  private companion object {
    const val DEFAULT_REQUEST_TIMEOUT_MS = 300_000L
    /** How often to emit a "still waiting" progress message while polling for readiness. */
    const val PROGRESS_REPORT_INTERVAL_MS = 5_000L

    /**
     * Upper bound on a single probe. The HTTP client's own request timeout (300s) is useless
     * as a probe budget; this keeps one stuck probe from blowing the overall readiness window.
     */
    const val PROBE_MAX_MS = 5_000L

    const val RUNNER_PACKAGE = "xyz.block.trailblaze.runner"
  }

  override fun close() {
    webSocketClient.close()
    httpRequestUtils.close()
  }
}
