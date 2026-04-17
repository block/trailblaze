package xyz.block.trailblaze.cli

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
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
) : java.io.Closeable {

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
    var consecutiveErrors = 0
    try {
      while (true) {
        kotlinx.coroutines.delay(RUN_POLL_INTERVAL_MS)

        // Overall timeout to prevent infinite polling if daemon crashes
        if (System.currentTimeMillis() - pollStartTime > RUN_POLL_TIMEOUT_MS) {
          return CliRunResponse(
            success = false,
            error =
              "Timed out waiting for run to complete after ${RUN_POLL_TIMEOUT_MS / 1000}s",
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
              consecutiveErrors++
              if (consecutiveErrors >= MAX_CONSECUTIVE_POLL_ERRORS) {
                return CliRunResponse(
                  success = false,
                  error =
                    "Daemon unreachable after $MAX_CONSECUTIVE_POLL_ERRORS consecutive poll failures",
                )
              }
              continue // Transient error, keep polling
            }
          } catch (e: kotlinx.coroutines.CancellationException) {
            throw e // Preserve coroutine cancellation
          } catch (_: Exception) {
            consecutiveErrors++
            if (consecutiveErrors >= MAX_CONSECUTIVE_POLL_ERRORS) {
              return CliRunResponse(
                success = false,
                error =
                  "Daemon unreachable after $MAX_CONSECUTIVE_POLL_ERRORS consecutive poll failures",
              )
            }
            continue // Transient network error, keep polling
          }

        // Emit progress updates
        val progress = status.progressMessage
        if (progress != null && progress != lastProgress) {
          onProgress(progress)
          lastProgress = progress
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

    /** Overall timeout for polling a run to completion (30 minutes) */
    const val RUN_POLL_TIMEOUT_MS = 30 * 60 * 1000L

    /** Max consecutive poll errors before giving up (daemon likely crashed) */
    const val MAX_CONSECUTIVE_POLL_ERRORS = 30
  }
}
