package xyz.block.trailblaze.mcp.android.ondevice.rpc

import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.SerializationException
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDevicePort.getTrailblazeOnDeviceSpecificPort
import xyz.block.trailblaze.logs.client.TrailblazeJsonInstance
import xyz.block.trailblaze.mcp.android.ondevice.rpc.RpcRequest.Companion.toRpcPath
import xyz.block.trailblaze.mcp.utils.HttpRequestUtils
import xyz.block.trailblaze.mcp.utils.HttpRequestUtils.HttpRpcException
import java.io.IOException
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * This is a pseudo-RPC client that communicates with the on-device server.
 */
class OnDeviceRpcClient(
  trailblazeDeviceId: TrailblazeDeviceId,
  private val sendProgressMessage: (String) -> Unit = {},
) : AutoCloseable {

  @PublishedApi
  internal val baseUrl = "http://localhost:${trailblazeDeviceId.getTrailblazeOnDeviceSpecificPort()}"

  @PublishedApi
  internal val httpRequestUtils: HttpRequestUtils = HttpRequestUtils(
    baseUrl = baseUrl,
  )

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
      val jsonInputString = TrailblazeJsonInstance.encodeToString(request)
      val responseJson = httpRequestUtils.postRequest(
        urlPath = urlPath,
        jsonPostBody = jsonInputString,
        requestTimeoutMs = request.requestTimeoutMs,
      )
      val response: TResponse = TrailblazeJsonInstance.decodeFromString(responseJson)
      RpcResult.Success(response)
    } catch (e: HttpRpcException) {
      RpcResult.Failure(
        errorType = RpcResult.ErrorType.HTTP_ERROR,
        message = "Server error during RPC call: ${e.message}",
        details = e.responseBody,
        method = methodName,
        url = fullUrl
      )
    } catch (e: SerializationException) {
      RpcResult.Failure(
        errorType = RpcResult.ErrorType.SERIALIZATION_ERROR,
        message = "Failed to serialize/deserialize RPC data",
        details = e.message,
        method = methodName,
        url = fullUrl
      )
    } catch (e: IOException) {
      RpcResult.Failure(
        errorType = RpcResult.ErrorType.NETWORK_ERROR,
        message = "Network error during RPC call",
        details = e.message,
        method = methodName,
        url = fullUrl
      )
    } catch (e: Exception) {
      RpcResult.Failure(
        errorType = RpcResult.ErrorType.UNKNOWN_ERROR,
        message = "RPC call failed: ${e.message}",
        details = e.stackTraceToString(),
        method = methodName,
        url = fullUrl
      )
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

  private companion object {
    /** How often to emit a "still waiting" progress message while polling for readiness. */
    const val PROGRESS_REPORT_INTERVAL_MS = 5_000L

    /**
     * Upper bound on a single probe. The HTTP client's own request timeout (300s) is useless
     * as a probe budget; this keeps one stuck probe from blowing the overall readiness window.
     */
    const val PROBE_MAX_MS = 5_000L
  }

  override fun close() {
    httpRequestUtils.close()
  }
}
