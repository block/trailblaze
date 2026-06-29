package xyz.block.trailblaze.cli

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.timeout
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import xyz.block.trailblaze.devices.TrailblazeDevicePort
import xyz.block.trailblaze.llm.RunYamlRequest
import xyz.block.trailblaze.logs.client.TrailblazeJson
import xyz.block.trailblaze.logs.server.endpoints.CliEndpoints
import xyz.block.trailblaze.logs.server.endpoints.CliRunAsyncResponse
import xyz.block.trailblaze.logs.server.endpoints.CliRunCancelResponse
import xyz.block.trailblaze.logs.server.endpoints.CliRunRequest
import xyz.block.trailblaze.logs.server.endpoints.CliRunResponse
import xyz.block.trailblaze.logs.server.endpoints.CliRunStatusResponse
import xyz.block.trailblaze.logs.server.endpoints.CliShutdownResponse
import xyz.block.trailblaze.logs.server.endpoints.CliShowWindowResponse
import xyz.block.trailblaze.logs.server.endpoints.CliStatusResponse
import xyz.block.trailblaze.logs.server.endpoints.RunState
import xyz.block.trailblaze.util.Console

/**
 * Client for communicating with the Trailblaze daemon server.
 *
 * The daemon runs the Trailblaze desktop app and exposes HTTP endpoints
 * for CLI commands to interact with. Uses Ktor HttpClient for HTTP requests.
 *
 * Core methods ([isRunning], [getStatus], [shutdown], [showWindow], [cancelRun], [run])
 * are suspend functions that call the Ktor client directly. For callers outside
 * coroutine contexts (picocli Callable.call(), shutdown hooks, etc.), blocking
 * convenience wrappers are provided (e.g. [isRunningBlocking]).
 */
class DaemonClient(
  private val host: String = "localhost",
  private val port: Int = TrailblazeDevicePort.TRAILBLAZE_DEFAULT_HTTP_PORT,
  /**
   * How long to sleep between `/cli/run-status` polls. Production keeps the
   * default; tests dial this down to keep the [MAX_CONSECUTIVE_POLL_ERRORS] /
   * ping-fallback cycle fast (~30 × pollIntervalMs is the worst case before
   * the fallback ping fires).
   */
  private val pollIntervalMs: Long = RUN_POLL_INTERVAL_MS,
  /**
   * How long the run may go WITHOUT a new progress message before the client gives up — an
   * inactivity watchdog, not a wall-clock cap (see [RUN_POLL_TIMEOUT_MS]). Resolved once from
   * [RUN_POLL_TIMEOUT_ENV_VAR] in production; tests inject a small window directly.
   */
  private val runPollTimeoutMs: Long = resolveRunPollTimeoutMs(),
) : java.io.Closeable {

  init {
    require(pollIntervalMs > 0) {
      "pollIntervalMs must be positive, was $pollIntervalMs"
    }
    require(runPollTimeoutMs > 0) {
      "runPollTimeoutMs must be positive, was $runPollTimeoutMs"
    }
  }

  private val json: Json = TrailblazeJson.defaultWithoutToolsInstance
  private val baseUrl: String
    get() = "http://$host:$port"

  private val client = HttpClient(OkHttp) {
    install(ContentNegotiation) { json(json) }
    install(HttpTimeout) {
      connectTimeoutMillis = CONNECT_TIMEOUT_MS
      requestTimeoutMillis = READ_TIMEOUT_MS
    }
    engine {
      config {
        // Use daemon threads so OkHttp's connection pool and dispatcher
        // don't prevent JVM exit after CLI commands complete.
        val daemonFactory = java.util.concurrent.ThreadFactory { r ->
          Thread(r).apply { isDaemon = true }
        }
        dispatcher(okhttp3.Dispatcher(java.util.concurrent.Executors.newCachedThreadPool(daemonFactory)))
        connectionPool(okhttp3.ConnectionPool(0, 1, java.util.concurrent.TimeUnit.SECONDS))
      }
    }
  }

  // ---------------------------------------------------------------------------
  // Suspend core methods
  // ---------------------------------------------------------------------------

  /** Check if the daemon is running by pinging the server. */
  suspend fun isRunning(): Boolean {
    return try {
      val response = client.get("$baseUrl${CliEndpoints.PING}")
      response.status.isSuccess()
    } catch (_: Exception) {
      false
    }
  }

  /** Get detailed daemon status. */
  suspend fun getStatus(): CliStatusResponse? {
    return try {
      val response = client.get("$baseUrl${CliEndpoints.STATUS}")
      if (response.status.isSuccess()) {
        json.decodeFromString(CliStatusResponse.serializer(), response.bodyAsText())
      } else {
        null
      }
    } catch (_: Exception) {
      null
    }
  }

  /**
   * Send a run request to the daemon using async polling.
   *
   * Submits the request to /cli/run-async, then polls /cli/run-status
   * until the run reaches a terminal state.
   *
   * @param onProgress called with progress messages as the trail executes
   */
  suspend fun run(
    request: CliRunRequest,
    onProgress: (String) -> Unit = {},
  ): CliRunResponse {
    return try {
      runAsync(request, onProgress)
    } catch (e: Exception) {
      CliRunResponse(success = false, error = e.message ?: "Connection failed")
    }
  }

  /** Convenience suspend method to run a [RunYamlRequest]. */
  suspend fun run(
    runYamlRequest: RunYamlRequest,
    forceStopTargetApp: Boolean = false,
  ): CliRunResponse {
    return run(
      CliRunRequest(runYamlRequest = runYamlRequest, forceStopTargetApp = forceStopTargetApp),
    )
  }

  /** Cancel an in-flight async run. */
  suspend fun cancelRun(runId: String): Boolean {
    return try {
      val response = client.post("$baseUrl${CliEndpoints.RUN_CANCEL}") {
        contentType(ContentType.Application.Json)
        setBody(mapOf("runId" to runId))
      }
      if (response.status.isSuccess()) {
        val body =
          json.decodeFromString(CliRunCancelResponse.serializer(), response.bodyAsText())
        body.success
      } else {
        false
      }
    } catch (_: Exception) {
      false
    }
  }

  /** Request daemon shutdown. */
  suspend fun shutdown(): CliShutdownResponse {
    return try {
      val response = client.post("$baseUrl${CliEndpoints.SHUTDOWN}") {
        contentType(ContentType.Application.Json)
        setBody("{}")
      }

      val responseBody = response.bodyAsText()
      if (response.status.isSuccess()) {
        json.decodeFromString(CliShutdownResponse.serializer(), responseBody)
      } else {
        CliShutdownResponse(
          success = false,
          message = "HTTP ${response.status.value}: $responseBody",
        )
      }
    } catch (e: Exception) {
      CliShutdownResponse(success = false, message = e.message ?: "Connection failed")
    }
  }

  /** Request the daemon to show its window (bring to foreground). */
  suspend fun showWindow(): CliShowWindowResponse {
    return try {
      val response = client.post("$baseUrl${CliEndpoints.SHOW_WINDOW}") {
        contentType(ContentType.Application.Json)
        setBody("{}")
      }

      val responseBody = response.bodyAsText()
      if (response.status.isSuccess()) {
        json.decodeFromString(CliShowWindowResponse.serializer(), responseBody)
      } else {
        CliShowWindowResponse(
          success = false,
          message = "HTTP ${response.status.value}: $responseBody",
        )
      }
    } catch (e: Exception) {
      CliShowWindowResponse(success = false, message = e.message ?: "Connection failed")
    }
  }

  // ---------------------------------------------------------------------------
  // Blocking convenience wrappers (for non-coroutine callers)
  // ---------------------------------------------------------------------------

  /** Blocking wrapper for [isRunning]. */
  fun isRunningBlocking(): Boolean = runBlocking { isRunning() }

  /** Blocking wrapper for [getStatus]. */
  fun getStatusBlocking(): CliStatusResponse? = runBlocking { getStatus() }

  /** Blocking wrapper for [run]. */
  fun runSync(
    request: CliRunRequest,
    onProgress: (String) -> Unit = {},
  ): CliRunResponse = runBlocking { run(request, onProgress) }

  /** Blocking convenience wrapper to run a [RunYamlRequest]. */
  fun runSync(
    runYamlRequest: RunYamlRequest,
    forceStopTargetApp: Boolean = false,
  ): CliRunResponse = runBlocking { run(runYamlRequest, forceStopTargetApp) }

  /** Blocking wrapper for [cancelRun]. */
  fun cancelRunBlocking(runId: String): Boolean = runBlocking { cancelRun(runId) }

  /** Blocking wrapper for [shutdown]. */
  fun shutdownBlocking(): CliShutdownResponse = runBlocking { shutdown() }

  /** Blocking wrapper for [showWindow]. */
  fun showWindowBlocking(): CliShowWindowResponse = runBlocking { showWindow() }

  // ---------------------------------------------------------------------------
  // Utilities
  // ---------------------------------------------------------------------------

  /**
   * The run ID of the currently executing async run, if any. Used by shutdown hooks to cancel
   * in-flight runs on Ctrl+C.
   */
  @Volatile
  var currentRunId: String? = null
    private set

  private suspend fun runAsync(
    request: CliRunRequest,
    onProgress: (String) -> Unit,
  ): CliRunResponse {
    // Submit the run
    val response = client.post("$baseUrl${CliEndpoints.RUN_ASYNC}") {
      contentType(ContentType.Application.Json)
      setBody(request)
    }
    val body = response.bodyAsText()
    if (!response.status.isSuccess() && response.status.value != 202) {
      return CliRunResponse(success = false, error = "HTTP ${response.status.value}: $body")
    }
    val submitResponse = json.decodeFromString(CliRunAsyncResponse.serializer(), body)

    val runId = submitResponse.runId
    currentRunId = runId

    // Poll for status
    var lastProgress: String? = null
    val pollStartTime = System.currentTimeMillis()
    // Inactivity watchdog: the deadline is measured from the LAST forward progress, not from run
    // start. A run that keeps advancing (a fresh progressMessage each step) is never abandoned;
    // only one that goes silent for the whole window — a genuinely wedged daemon whose /ping may
    // still answer — is timed out. Reset below whenever progress changes. Because lastProgressAtMs
    // only ever moves forward from pollStartTime, this deadline is always >= the old start-anchored
    // cap, so the change can never kill a run EARLIER than the previous flat timeout did.
    var lastProgressAtMs = pollStartTime
    var consecutiveErrors = 0
    try {
      while (true) {
        kotlinx.coroutines.delay(pollIntervalMs)

        // Time out only after a full window with NO forward progress (wedged daemon backstop).
        if (System.currentTimeMillis() - lastProgressAtMs > runPollTimeoutMs) {
          return CliRunResponse(
            success = false,
            error =
              "Timed out waiting for run to complete: no progress for ${runPollTimeoutMs / 1000}s",
          )
        }

        val status =
          try {
            val statusResponse = client.get("$baseUrl${CliEndpoints.RUN_STATUS}") {
              parameter("runId", runId)
            }
            if (statusResponse.status.isSuccess()) {
              consecutiveErrors = 0
              json.decodeFromString(
                CliRunStatusResponse.serializer(),
                statusResponse.bodyAsText(),
              )
            } else {
              val statusCode = statusResponse.status.value
              // 4xx is terminal *except* for transient subcodes (408 Request
              // Timeout, 429 Too Many Requests): the request itself is
              // malformed (400) or the runId is unknown (404 — e.g. daemon
              // restarted and lost run state). Retrying + /ping-resetting on a
              // truly terminal 4xx would silently wait the full
              // RUN_POLL_TIMEOUT_MS while /ping keeps succeeding, so bail
              // immediately and surface the body to the user.
              if (statusCode in 400..499 && statusCode !in TRANSIENT_4XX_CODES) {
                val body = try {
                  statusResponse.bodyAsText()
                } catch (e: CancellationException) {
                  throw e // Preserve coroutine cancellation through body extraction.
                } catch (_: Exception) {
                  ""
                }
                val cleanBody = body
                  .take(ERROR_BODY_MAX_BYTES)
                  .replace(WHITESPACE_RUN_REGEX, " ")
                  .trim()
                val errorMsg = if (cleanBody.isNotBlank()) {
                  val truncated =
                    if (body.length > ERROR_BODY_MAX_BYTES) " (truncated)" else ""
                  "run-status returned HTTP $statusCode: $cleanBody$truncated"
                } else {
                  "run-status returned HTTP $statusCode"
                }
                return CliRunResponse(success = false, error = errorMsg)
              }
              if (consecutiveErrors == 0) {
                Console.error(
                  "[DaemonClient] run-status returned HTTP $statusCode; will retry",
                )
              }
              consecutiveErrors =
                resolveConsecutivePollErrors(consecutiveErrors + 1, onProgress)
                  ?: return CliRunResponse(
                    success = false,
                    error =
                      "Daemon unreachable after $MAX_CONSECUTIVE_POLL_ERRORS consecutive poll failures (ping also failed)",
                  )
              continue // Transient 5xx, keep polling
            }
          } catch (e: CancellationException) {
            throw e // Preserve coroutine cancellation
          } catch (e: Exception) {
            if (consecutiveErrors == 0) {
              Console.error(
                "[DaemonClient] run-status poll failed (${e::class.simpleName}: ${e.message ?: e.toString()}); will retry",
              )
            }
            consecutiveErrors =
              resolveConsecutivePollErrors(consecutiveErrors + 1, onProgress)
                ?: return CliRunResponse(
                  success = false,
                  error =
                    "Daemon unreachable after $MAX_CONSECUTIVE_POLL_ERRORS consecutive poll failures (ping also failed)",
                )
            continue // Transient network error, keep polling
          }

        // Emit progress updates and reset the inactivity watchdog on forward progress. NB: the
        // ping-healthy-after-burst message in resolveConsecutivePollErrors deliberately does NOT
        // reset the watchdog — a daemon that pings but never advances the run is exactly the wedged
        // state the backstop exists to catch.
        val progress = status.progressMessage
        if (progress != null && progress != lastProgress) {
          onProgress(progress)
          lastProgress = progress
          lastProgressAtMs = System.currentTimeMillis()
        }

        when (status.state) {
          RunState.COMPLETED,
          RunState.FAILED ->
            return status.result
              ?: CliRunResponse(success = false, error = "No result from daemon")
          RunState.CANCELLED -> return CliRunResponse(success = false, error = "Run cancelled")
          RunState.PENDING,
          RunState.RUNNING -> continue
        }
      }
    } finally {
      currentRunId = null
    }
  }

  /**
   * Health-check fallback used by [runAsync] after [MAX_CONSECUTIVE_POLL_ERRORS]
   * consecutive `/cli/run-status` failures. Pings the daemon's lightweight `/ping`
   * endpoint with a dedicated short timeout — both endpoints share Netty's event
   * loop, so a ping success is a strong signal the daemon process is alive and
   * the run-status calls were transient (e.g. event-loop saturation, brief GC
   * pause).
   *
   * Caveat: ping success is a *probabilistic* signal, not a hard guarantee. If
   * the event loop is in a state where only `/cli/run-status` is permanently
   * wedged but `/ping` keeps responding, the outer [RUN_POLL_TIMEOUT_MS] is the
   * final safety net.
   *
   * `CancellationException` is re-thrown to preserve cooperative coroutine
   * cancellation (e.g. Ctrl+C while the ping is in flight). All other
   * exceptions are logged and reported as "not reachable".
   */
  private suspend fun isDaemonReachable(): Boolean {
    return try {
      val response = client.get("$baseUrl${CliEndpoints.PING}") {
        timeout { requestTimeoutMillis = PING_HEALTHCHECK_TIMEOUT_MS }
      }
      val ok = response.status.isSuccess()
      if (!ok) {
        Console.error("[DaemonClient] /ping returned HTTP ${response.status.value}")
      }
      ok
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      Console.error(
        "[DaemonClient] /ping failed (${e::class.simpleName}: ${e.message ?: e.toString()})",
      )
      false
    }
  }

  /**
   * Threshold + ping-fallback handling for the run-status poll loop. Returns the
   * new counter value to use going forward (possibly reset to 0 if `/ping`
   * verified the daemon is alive), or `null` if the caller should bail.
   *
   * The reset path is bounded by [RUN_POLL_TIMEOUT_MS] in the outer loop, so a
   * pathological "ping healthy but status broken" state cannot loop forever.
   *
   * Callbacks ([onProgress]) are isolated from the polling loop — a throwing
   * callback is logged but does not abort the run.
   */
  private suspend fun resolveConsecutivePollErrors(
    consecutiveErrors: Int,
    onProgress: (String) -> Unit,
  ): Int? {
    if (consecutiveErrors < MAX_CONSECUTIVE_POLL_ERRORS) return consecutiveErrors
    if (!isDaemonReachable()) return null
    val message =
      "$consecutiveErrors consecutive run-status failures, but /ping is healthy — continuing"
    Console.error("[DaemonClient] $message")
    try {
      onProgress(message)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      Console.error(
        "[DaemonClient] onProgress callback threw (${e::class.simpleName}: ${e.message ?: e.toString()}); ignoring",
      )
    }
    return 0
  }

  /**
   * Wait for the daemon to become available.
   *
   * @param maxWaitMs Maximum time to wait in milliseconds
   * @param pollIntervalMs Time between polls
   * @param onPoll Called on each poll attempt (e.g., to print progress dots)
   * @return true if daemon became available, false if timed out
   */
  fun waitForDaemon(
    maxWaitMs: Long = MAX_WAIT_FOR_DAEMON_MS,
    pollIntervalMs: Long = POLL_INTERVAL_MS,
    onPoll: () -> Unit = {},
  ): Boolean {
    val startTime = System.currentTimeMillis()
    while (System.currentTimeMillis() - startTime < maxWaitMs) {
      if (isRunningBlocking()) {
        return true
      }
      onPoll()
      Thread.sleep(pollIntervalMs)
    }
    return false
  }

  override fun close() {
    client.close()
  }

  companion object {
    /** Connection timeout for quick checks */
    const val CONNECT_TIMEOUT_MS = 2000L

    /** Read timeout for quick responses */
    const val READ_TIMEOUT_MS = 5000L

    /** Poll interval when checking async run status */
    const val RUN_POLL_INTERVAL_MS = 1000L

    /** Maximum time to wait for daemon to start */
    const val MAX_WAIT_FOR_DAEMON_MS = 30_000L

    /** Poll interval when waiting for daemon */
    const val POLL_INTERVAL_MS = 500L

    /**
     * Default inactivity window for polling a run to completion (10 minutes).
     *
     * This is NOT a wall-clock cap on total runtime — it is the maximum time the run may go
     * WITHOUT a new progress message before the client abandons it (see the watchdog in
     * [runAsync]). A run that keeps advancing — emitting a fresh `progressMessage` each step —
     * runs as long as it needs; only one that goes silent for the whole window (a wedged daemon
     * whose `/ping` may still answer) is timed out. This deliberately lets a legitimately
     * slow-but-progressing trail finish instead of being killed at a flat deadline while the
     * daemon is still doing real work.
     *
     * Previously a flat wall-clock cap measured from run start: any run exceeding 10 min total
     * was killed regardless of health, which abandoned healthy long-running trails even while the
     * daemon was still advancing steps. A deliberately-contended parallel-stress CI step (whose
     * copies serialize on a shared login lock) can legitimately approach 10 min, and the old cap
     * reported the slowest copy failed while its run was still progressing — the daemon went on to
     * complete it. The watchdog can only push the deadline later than the old cap, never earlier,
     * so the change is strictly safe for every pipeline: it converts false timeouts into passes
     * and never introduces a new early kill.
     *
     * Override per-invocation with [RUN_POLL_TIMEOUT_ENV_VAR].
     */
    const val RUN_POLL_TIMEOUT_MS = 10 * 60 * 1000L

    /**
     * Env var to override [RUN_POLL_TIMEOUT_MS] (the inactivity window), in milliseconds. Read
     * once when a [DaemonClient] is constructed (per CLI command). Lower it to fail fast while
     * triaging a wedged daemon; raise it for a pipeline whose trails legitimately go quiet for
     * long stretches. A missing / unparseable / non-positive value falls back to the default; a
     * positive value below [MIN_RUN_POLL_TIMEOUT_MS] is clamped up to it. Any time the env var is
     * actually honored (or clamped) a single diagnostic line is logged so a CI consumer can trace
     * which value was in effect when an unexpected timeout fired.
     */
    const val RUN_POLL_TIMEOUT_ENV_VAR = "TRAILBLAZE_RUN_POLL_TIMEOUT_MS"

    /** Floor for [RUN_POLL_TIMEOUT_ENV_VAR] so a fat-fingered tiny value can't abandon every run instantly. */
    const val MIN_RUN_POLL_TIMEOUT_MS = 1_000L

    /** Resolve the inactivity-watchdog window from [RUN_POLL_TIMEOUT_ENV_VAR] (read once per construction). */
    fun resolveRunPollTimeoutMs(): Long = parseRunPollTimeoutMs(System.getenv(RUN_POLL_TIMEOUT_ENV_VAR))

    /**
     * Pure resolution of the inactivity-watchdog window from a raw env-var string (extracted so the
     * parsing rules are unit-testable without touching the process environment). Rules:
     *  - `null` / unparseable / non-positive (`<= 0`) → [RUN_POLL_TIMEOUT_MS] default. Non-positive
     *    is treated as invalid (NOT clamped): otherwise a `=0` would clamp to the sub-second floor
     *    and make any run that goes quiet for ~1s fail as timed out, instead of the intended default.
     *  - positive but below [MIN_RUN_POLL_TIMEOUT_MS] → clamped up to the floor.
     *  - positive and >= the floor → honored as-is.
     *
     * Logs a single `[DaemonClient]` diagnostic whenever the env var is set (honored, clamped, or
     * rejected) so an unexpected timeout can be traced to the value that was actually in effect.
     */
    internal fun parseRunPollTimeoutMs(raw: String?): Long {
      if (raw == null) return RUN_POLL_TIMEOUT_MS
      val parsed = raw.toLongOrNull()
      if (parsed == null || parsed <= 0) {
        Console.error(
          "[DaemonClient] $RUN_POLL_TIMEOUT_ENV_VAR='$raw' is not a positive number of " +
            "milliseconds — using default ${RUN_POLL_TIMEOUT_MS}ms.",
        )
        return RUN_POLL_TIMEOUT_MS
      }
      val clamped = parsed.coerceAtLeast(MIN_RUN_POLL_TIMEOUT_MS)
      if (clamped != parsed) {
        Console.error(
          "[DaemonClient] $RUN_POLL_TIMEOUT_ENV_VAR=${parsed}ms is below the " +
            "${MIN_RUN_POLL_TIMEOUT_MS}ms floor — clamped to ${clamped}ms.",
        )
      } else {
        Console.error(
          "[DaemonClient] run-poll inactivity timeout overridden via " +
            "$RUN_POLL_TIMEOUT_ENV_VAR=${clamped}ms.",
        )
      }
      return clamped
    }

    /**
     * Max consecutive poll errors before falling back to a /ping health check.
     * If the health check also fails the run is aborted; if it succeeds, the
     * counter resets and polling continues.
     */
    const val MAX_CONSECUTIVE_POLL_ERRORS = 30

    /** Per-request timeout for the /ping fallback health check used after [MAX_CONSECUTIVE_POLL_ERRORS]. */
    const val PING_HEALTHCHECK_TIMEOUT_MS = 5_000L

    /**
     * 4xx subcodes that are semantically *transient* and should follow the
     * 5xx retry+ping-fallback path instead of bailing immediately:
     *
     * - 408 Request Timeout — the request itself timed out, retrying is correct
     * - 429 Too Many Requests — the server is back-pressuring; retrying is correct
     *
     * Everything else in 400..499 is terminal (e.g. 400 malformed, 404 unknown
     * runId from daemon restart, 401/403 auth).
     */
    val TRANSIENT_4XX_CODES = setOf(408, 429)

    /**
     * Max bytes of a 4xx response body to embed in the CLI error message. Keeps
     * a daemon stack-trace or HTML error page from flooding the user's terminal.
     */
    const val ERROR_BODY_MAX_BYTES = 512

    /** Collapses runs of whitespace (including newlines) into single spaces for error display. */
    private val WHITESPACE_RUN_REGEX = "\\s+".toRegex()
  }
}
