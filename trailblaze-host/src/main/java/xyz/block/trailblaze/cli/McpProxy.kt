package xyz.block.trailblaze.cli

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.timeout
import io.ktor.client.request.get
import io.ktor.client.request.headers
import io.ktor.client.request.post
import io.ktor.client.request.prepareGet
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.utils.io.jvm.javaio.toInputStream
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStream
import java.io.PrintWriter
import java.net.ConnectException
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import picocli.CommandLine
import xyz.block.trailblaze.devices.TrailblazeDevicePort
import xyz.block.trailblaze.mcp.McpToolProfile

/**
 * Lightweight STDIO-to-HTTP proxy for MCP.
 *
 * Keeps a stable STDIO connection with the MCP client while forwarding all JSON-RPC
 * traffic to a Trailblaze HTTP daemon. When the daemon restarts (e.g., after a code
 * change), the proxy transparently reconnects -- the client never sees a disconnection.
 *
 * ```
 * MCP Client <-- STDIO (stable) --> McpProxy <-- HTTP (reconnects) --> Daemon
 * ```
 *
 * Usage: `trailblaze mcp` (default mode)
 *
 * Hot-reload workflow (development):
 *   1. Start proxy once -- MCP client connects here, stays connected
 *   2. Make code changes
 *   3. trailblaze stop                                (kill daemon)
 *   4. ./gradlew :trailblaze-desktop:releaseArtifacts   (rebuild uber JAR)
 *   5. trailblaze                                     (restart daemon)
 *   6. Proxy auto-reconnects, replays session -- MCP client doesn't notice
 */
class McpProxy(
  private val port: Int = TrailblazeDevicePort.TRAILBLAZE_DEFAULT_HTTP_PORT,
  private val retryIntervalMs: Long = 1000,
  private val maxRetryMs: Long = 120_000,
) {

  private val daemonUrl: String = "http://localhost:$port/mcp"
  private val pingUrl: String = "http://localhost:$port/ping"

  private val httpClient = HttpClient(OkHttp) {
    install(HttpTimeout) {
      connectTimeoutMillis = 5_000
      requestTimeoutMillis = 300_000 // 5 min -- tool calls can be slow
      socketTimeoutMillis = 300_000
    }
  }

  // Current session ID with the daemon (changes on each reconnect)
  private val daemonSessionId = AtomicReference<String?>(null)

  // The last 'initialize' request from the client -- replayed on reconnect
  private val lastInitializeRequest = AtomicReference<String?>(null)

  // The last 'device' tool call -- replayed on reconnect to restore device binding
  private val lastDeviceToolCall = AtomicReference<String?>(null)

  // The client's requested protocol version -- used to bridge version mismatches between
  // the client and the MCP SDK (e.g., client wants 2025-11-25 but SDK only supports 2025-06-18)
  private val clientProtocolVersion = AtomicReference<String?>(null)

  // Queue for SSE notifications from daemon -> client
  private val notificationQueue = LinkedBlockingQueue<String>(10_000)

  // Whether the proxy is shutting down
  private val shutdownRequested = AtomicBoolean(false)

  fun run(): Int {
    System.setProperty("java.awt.headless", "true")

    // Capture stdout BEFORE any redirection -- this is the JSON-RPC pipe to the MCP client
    val stdoutForTransport = System.out
    val origStderr = System.err

    // File-based logging to ~/.trailblaze/mcp.log (stderr may be swallowed by MCP clients)
    val logDir = File(System.getProperty("user.home"), ".trailblaze")
    logDir.mkdirs()
    val logFile = File(logDir, "mcp.log")
    val logWriter = PrintWriter(java.io.FileOutputStream(logFile, true), true)
    val dtf = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    val proxyId = java.util.UUID.randomUUID().toString().take(6)

    val log = { msg: String ->
      val timestamped = "[${LocalDateTime.now().format(dtf)}] [mcp-proxy:$proxyId] $msg"
      origStderr.println(timestamped)
      origStderr.flush()
      logWriter.println(timestamped)
      logWriter.flush()
    }

    log("Starting MCP proxy -> $daemonUrl")
    log("Waiting for daemon... (restart it freely, proxy handles reconnection)")

    // Wait for daemon to be available before accepting client requests
    waitForDaemon(log)

    // Start SSE notification listener (daemon -> client)
    val sseThread = Thread({ runSseListener(stdoutForTransport, log) }, "mcp-proxy-sse")
    sseThread.isDaemon = true
    sseThread.start()

    // Start notification writer (serializes writes to stdout)
    val writerThread = Thread(
      { runNotificationWriter(stdoutForTransport, log) },
      "mcp-proxy-writer",
    )
    writerThread.isDaemon = true
    writerThread.start()

    // Main loop: read JSON-RPC from stdin, forward to daemon
    val reader = BufferedReader(InputStreamReader(System.`in`))
    try {
      while (!shutdownRequested.get()) {
        val line = reader.readLine() ?: break // Client closed stdin
        if (line.isBlank()) continue

        log("-> ${line.take(200)}")
        val response = forwardRequest(line, log)
        if (response != null) {
          log("<- ${response.take(200)}")
          writeToStdout(stdoutForTransport, response)
        }
      }
    } catch (e: Exception) {
      log("STDIO read error: ${e.message}")
    }

    log("Client disconnected -- shutting down.")
    shutdownRequested.set(true)
    httpClient.close()
    logWriter.close()
    return CommandLine.ExitCode.OK
  }

  /**
   * Block until the daemon is reachable, attempting to start it if not running.
   * Periodically retries starting the daemon in case the first attempt failed.
   */
  private fun waitForDaemon(log: (String) -> Unit) {
    if (isDaemonReachable()) {
      log("Daemon is reachable.")
      return
    }

    log("Daemon not running -- attempting to start...")
    startDaemon(log)

    var attempts = 0
    while (!shutdownRequested.get()) {
      if (isDaemonReachable()) {
        log("Daemon is reachable.")
        return
      }
      attempts++
      if (attempts % DAEMON_START_RETRY_SECONDS == 0) {
        log("Still waiting for daemon... ($attempts seconds) -- retrying start...")
        startDaemon(log)
      }
      Thread.sleep(retryIntervalMs)
    }
  }

  /**
   * Check if the daemon is reachable via /ping.
   */
  private fun isDaemonReachable(): Boolean {
    return try {
      runBlocking {
        val response = httpClient.get(pingUrl) {
          timeout {
            connectTimeoutMillis = 2_000
            requestTimeoutMillis = 2_000
            socketTimeoutMillis = 2_000
          }
        }
        response.status.isSuccess()
      }
    } catch (_: Exception) {
      false
    }
  }

  /**
   * Start the daemon via the `./trailblaze` launcher script.
   * The script handles Gradle build and launches in headless mode.
   */
  private fun startDaemon(log: (String) -> Unit) {
    val launcher = findLauncher()
    if (launcher == null) {
      log("Cannot auto-start daemon: ./trailblaze launcher not found. Start manually.")
      return
    }

    val command = mutableListOf(launcher.absolutePath, "--headless")

    // Port overrides
    if (port != TrailblazeDevicePort.TRAILBLAZE_DEFAULT_HTTP_PORT) {
      command.addAll(1, listOf("--port", port.toString()))
    }

    log("Starting daemon: ${launcher.name} --headless")

    try {
      val pb = ProcessBuilder(command)
      pb.redirectOutput(ProcessBuilder.Redirect.DISCARD)
      pb.redirectError(ProcessBuilder.Redirect.DISCARD)
      pb.start()
      log("Daemon process launched.")
    } catch (e: Exception) {
      log("Failed to start daemon: ${e.message}")
    }
  }

  /**
   * Find the trailblaze launcher script next to the running JAR,
   * falling back to well-known relative paths for local development.
   */
  private fun findLauncher(): File? {
    // Launcher next to the JAR (install.sh / release builds).
    val jarDir = McpProxy::class.java.protectionDomain?.codeSource?.location?.toURI()?.let { File(it).parentFile }
    if (jarDir != null) {
      val launcher = File(jarDir, "trailblaze")
      if (launcher.exists() && launcher.canExecute()) return launcher
    }

    // Fallback: relative to CWD (local development from repo root).
    return listOf(File("trailblaze"), File("opensource/trailblaze"))
      .firstOrNull { it.exists() && it.canExecute() }
  }

  /**
   * Forward a JSON-RPC request to the daemon, retrying if it's down.
   * Returns the response body, or a JSON-RPC error if retries are exhausted.
   */
  private fun forwardRequest(jsonRpcRequest: String, log: (String) -> Unit): String? {
    trackForReplay(jsonRpcRequest)

    // JSON-RPC notifications have no "id" field -- daemon won't send a response.
    // Parse the JSON to check reliably (string contains is fragile — "id" could appear in values).
    val isNotification = try {
      !Json.parseToJsonElement(jsonRpcRequest).jsonObject.containsKey("id")
    } catch (_: Exception) {
      false // Malformed JSON — treat as request (will get an error response)
    }

    val startTime = System.currentTimeMillis()
    var lastError: String? = null
    var lastDaemonStartAttempt = 0L

    while (System.currentTimeMillis() - startTime < maxRetryMs) {
      try {
        val result = httpPost(jsonRpcRequest, log)

        // Daemon returned a JSON-RPC error indicating it needs initialization
        // (happens when daemon restarted and proxy reconnected without re-init)
        if (result.contains("Server not initialized")) {
          log("Daemon not initialized -- re-initializing session...")
          daemonSessionId.set(null)
          if (reInitializeSession(log)) {
            continue
          }
        }

        return if (isNotification) null else translateProtocolVersion(result)
      } catch (e: ConnectException) {
        if (daemonSessionId.getAndSet(null) != null) {
          log("Daemon unreachable -- attempting restart...")
        }
        // Periodically attempt to restart the daemon on connection failure
        val now = System.currentTimeMillis()
        if (now - lastDaemonStartAttempt > DAEMON_START_RETRY_SECONDS * 1000L) {
          startDaemon(log)
          lastDaemonStartAttempt = now
        }
        lastError = e.message
        Thread.sleep(retryIntervalMs)
      } catch (e: Exception) {
        // Session not found (404) -- daemon restarted, need to re-initialize
        if (e.message?.contains("404") == true) {
          log("Daemon returned 404 (session expired) -- re-initializing...")
          daemonSessionId.set(null)
          if (reInitializeSession(log)) {
            continue
          }
        }
        lastError = e.message
        log("Request error: ${e.message}")
        Thread.sleep(retryIntervalMs)
      }
    }

    if (isNotification) return null
    log("Daemon unavailable after ${maxRetryMs}ms -- returning error to client.")
    val id = extractId(jsonRpcRequest) ?: "null"
    return """{"jsonrpc":"2.0","id":$id,"error":{"code":-32000,"message":"Trailblaze daemon unavailable: $lastError"}}"""
  }

  /**
   * Perform the HTTP POST to the daemon's /mcp endpoint.
   */
  private fun httpPost(body: String, log: (String) -> Unit): String {
    return runBlocking {
      val response = httpClient.post(daemonUrl) {
        contentType(ContentType.Application.Json)
        headers {
          append("Accept", "application/json, text/event-stream")
          append("X-Tool-Profile", McpToolProfile.MINIMAL.name)
          daemonSessionId.get()?.let { append("mcp-session-id", it) }
        }
        setBody(body)
      }

      if (response.status.value == 404) {
        throw Exception("404 Session not found")
      }

      response.headers["mcp-session-id"]?.let { newSessionId ->
        val old = daemonSessionId.getAndSet(newSessionId)
        if (old != newSessionId) {
          log("Session ID: $newSessionId")
        }
      }

      response.bodyAsText()
    }
  }

  /**
   * Track requests that need to be replayed after daemon reconnect.
   */
  private fun trackForReplay(jsonRpc: String) {
    val root =
      try {
        Json.parseToJsonElement(jsonRpc).jsonObject
      } catch (_: Exception) {
        return // Malformed JSON -- nothing to track
      }

    val method = root["method"]?.jsonPrimitive?.content ?: return
    val hasId = root.containsKey("id")

    // Track the 'initialize' request (not 'notifications/initialized')
    if (method == "initialize" && hasId) {
      lastInitializeRequest.set(jsonRpc)
      root["params"]?.jsonObject?.get("protocolVersion")?.jsonPrimitive?.content?.let {
        clientProtocolVersion.set(it)
      }
    }

    // Track device connect calls (ANDROID, IOS, WEB, CONNECT actions)
    if (method == "tools/call") {
      val params = root["params"]?.jsonObject ?: return
      val toolName = params["name"]?.jsonPrimitive?.content ?: return
      if (toolName == "device") {
        val arguments = params["arguments"]?.jsonObject
        val action = arguments?.get("action")?.jsonPrimitive?.content
        if (action in DEVICE_CONNECT_ACTIONS) {
          lastDeviceToolCall.set(jsonRpc)
        }
      }
    }
  }

  /**
   * Re-establish a session with the daemon after it restarts.
   * Replays the initialize handshake and optionally the device connect.
   */
  private fun reInitializeSession(log: (String) -> Unit): Boolean {
    val initRequest = lastInitializeRequest.get() ?: return false

    log("Replaying initialize handshake...")
    try {
      httpPost(initRequest, log)
      httpPost("""{"jsonrpc":"2.0","method":"notifications/initialized"}""", log)

      lastDeviceToolCall.get()?.let { deviceCall ->
        log("Replaying device connection...")
        httpPost(rewriteRequestId(deviceCall), log)
      }

      // Notify client that tools may have changed
      notificationQueue.put("""{"jsonrpc":"2.0","method":"notifications/tools/list_changed"}""")

      log("Session re-established with daemon.")
      return true
    } catch (e: Exception) {
      log("Re-initialization failed: ${e.message}")
      return false
    }
  }

  /**
   * SSE listener: connects to GET /mcp for server->client notifications.
   * Reconnects automatically when the daemon restarts.
   *
   * If the SSE connection closes very quickly (under [SSE_MIN_STABLE_MS]),
   * it's likely because the daemon restarted and our session ID is stale.
   * In that case we clear the session ID so the next POST triggers a fresh
   * initialize handshake, and back off before retrying.
   */
  private fun runSseListener(stdout: OutputStream, log: (String) -> Unit) {
    var consecutiveQuickCloses = 0

    while (!shutdownRequested.get()) {
      val sessionId = daemonSessionId.get()
      if (sessionId == null) {
        consecutiveQuickCloses = 0
        Thread.sleep(500)
        continue
      }

      val connectTime = System.currentTimeMillis()
      try {
        runBlocking {
          httpClient.prepareGet(daemonUrl) {
            headers {
              append("Accept", "text/event-stream")
              append("mcp-session-id", sessionId)
            }
            timeout {
              requestTimeoutMillis = Long.MAX_VALUE
              socketTimeoutMillis = Long.MAX_VALUE
            }
          }.execute { response ->
            log("SSE listener connected for session $sessionId")
            response.bodyAsChannel().toInputStream().bufferedReader().use { reader ->
              var line: String?
              while (reader.readLine().also { line = it } != null) {
                if (shutdownRequested.get()) break
                consecutiveQuickCloses = 0 // Received data -- connection is healthy
                val l = line ?: continue
                if (l.startsWith("data: ")) {
                  val data = l.removePrefix("data: ").trim()
                  if (data.isNotEmpty()) {
                    notificationQueue.put(data)
                  }
                }
              }
            }
          }
        }
      } catch (_: ConnectException) {
        // Daemon down -- will retry
      } catch (e: Exception) {
        if (!shutdownRequested.get()) {
          log("SSE error: ${e.message}")
        }
      }

      if (shutdownRequested.get()) break

      // If the connection closed very quickly the session is likely stale
      val elapsed = System.currentTimeMillis() - connectTime
      if (elapsed < SSE_MIN_STABLE_MS) {
        consecutiveQuickCloses++
        if (consecutiveQuickCloses >= 3) {
          log(
            "SSE connection closed immediately $consecutiveQuickCloses times -- " +
              "session $sessionId appears stale, clearing for re-init.",
          )
          daemonSessionId.set(null)
          consecutiveQuickCloses = 0
        }
        // Exponential backoff: 1s, 2s, 4s... up to 10s
        val shift = (consecutiveQuickCloses - 1).coerceIn(0, 3)
        val backoff = (retryIntervalMs * (1L shl shift)).coerceAtMost(10_000)
        Thread.sleep(backoff)
      } else {
        consecutiveQuickCloses = 0
        Thread.sleep(retryIntervalMs)
      }
    }
  }

  /**
   * Drains the notification queue and writes to stdout (client).
   * Serializes all writes to stdout to avoid interleaving with request responses.
   */
  private fun runNotificationWriter(stdout: OutputStream, log: (String) -> Unit) {
    while (!shutdownRequested.get()) {
      try {
        val notification = notificationQueue.poll(1, TimeUnit.SECONDS) ?: continue
        writeToStdout(stdout, notification)
      } catch (_: InterruptedException) {
        break
      } catch (e: Exception) {
        log("Notification write error: ${e.message}")
      }
    }
  }

  /** Write a JSON-RPC line to stdout, synchronized to prevent interleaving. */
  private fun writeToStdout(stdout: OutputStream, line: String) {
    synchronized(stdout) {
      stdout.write(line.toByteArray())
      stdout.write('\n'.code)
      stdout.flush()
    }
  }

  /**
   * Translate the protocolVersion in an initialize response to match the client's
   * requested version. This bridges the gap when the MCP SDK doesn't yet support
   * the protocol version the client requires (e.g., client wants 2025-11-25 but
   * SDK only supports up to 2025-06-18). The core tool-calling protocol is the same
   * across these versions, so this is safe for Trailblaze's use case.
   */
  private fun translateProtocolVersion(response: String): String {
    val clientVersion = clientProtocolVersion.get() ?: return response
    return try {
      val root = Json.parseToJsonElement(response).jsonObject
      val result = root["result"]?.jsonObject ?: return response
      val serverVersion = result["protocolVersion"]?.jsonPrimitive?.content ?: return response
      if (serverVersion == clientVersion) return response

      val newResult = buildMap {
        result.forEach { (key, value) ->
          put(key, if (key == "protocolVersion") JsonPrimitive(clientVersion) else value)
        }
      }
      val newRoot = buildMap {
        root.forEach { (key, value) ->
          put(key, if (key == "result") JsonObject(newResult) else value)
        }
      }
      Json.encodeToString(JsonObject.serializer(), JsonObject(newRoot))
    } catch (_: Exception) {
      response
    }
  }

  /** Extract the "id" field from a JSON-RPC request. */
  private fun extractId(jsonRpc: String): String? {
    return try {
      val root = Json.parseToJsonElement(jsonRpc).jsonObject
      val id = root["id"] ?: return null
      // Preserve the raw JSON representation (number stays unquoted, string stays quoted)
      val primitive = id.jsonPrimitive
      if (primitive.isString) "\"${primitive.content}\"" else primitive.content
    } catch (_: Exception) {
      null
    }
  }

  /** Rewrite the "id" field to avoid ID collisions on replay. */
  private fun rewriteRequestId(jsonRpc: String): String {
    return try {
      val root = Json.parseToJsonElement(jsonRpc).jsonObject
      if (!root.containsKey("id")) return jsonRpc
      val newId = System.currentTimeMillis()
      val rewritten = buildMap {
        root.forEach { (key, value) ->
          put(key, if (key == "id") JsonPrimitive(newId) else value)
        }
      }
      Json.encodeToString(JsonObject.serializer(), JsonObject(rewritten))
    } catch (_: Exception) {
      jsonRpc // Malformed JSON -- return unchanged
    }
  }

  companion object {
    private const val DAEMON_START_RETRY_SECONDS = 15

    /** If an SSE connection lasts less than this, the session is likely stale. */
    private const val SSE_MIN_STABLE_MS = 5_000L

    /** Device actions that represent a connection/binding (should be replayed on reconnect). */
    private val DEVICE_CONNECT_ACTIONS = setOf("ANDROID", "IOS", "WEB", "CONNECT")
  }
}
