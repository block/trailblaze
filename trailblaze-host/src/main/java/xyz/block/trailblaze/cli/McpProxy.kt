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
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import picocli.CommandLine
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.devices.TrailblazeDevicePort
import xyz.block.trailblaze.devices.WebInstanceIds
import xyz.block.trailblaze.ui.TrailblazeDesktopUtil

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
 *   3. trailblaze app --stop                           (kill daemon)
 *   4. ./gradlew :trailblaze-desktop:releaseArtifacts   (rebuild uber JAR)
 *   5. trailblaze                                     (restart daemon)
 *   6. Proxy auto-reconnects, replays session -- MCP client doesn't notice
 */
class McpProxy(
  private val port: Int = TrailblazeDevicePort.TRAILBLAZE_DEFAULT_HTTP_PORT,
  private val retryIntervalMs: Long = 1000,
  private val maxRetryMs: Long = 120_000,
  /**
   * Device spec to auto-bind on startup (e.g. `android`, `android/emulator-5554`).
   * Resolved by [McpCommand] from `--device` flag or `TRAILBLAZE_DEVICE` env var.
   * When non-null the proxy synthesizes a `tools/call name=device` request right
   * after the client's `notifications/initialized`, so the agent sees a session
   * that's already device-bound without having to call the `device` tool itself.
   *
   * Null = consult [autodetectSingleConnectedDevice] as a fallback before
   * giving up. When that resolves to exactly one connected device, the proxy
   * still injects an auto-bind — same zero-setup behavior the CLI's
   * `trailblaze snapshot` got via PR #3456 for single-device users. When the
   * autodetect finds 0 or 2+ devices the agent falls through to the
   * unbound-device error on its first tool call (legacy behavior).
   */
  private val initialDeviceSpec: String? = null,
  /**
   * Target app to bind to the auto-bound device. Only meaningful when
   * [initialDeviceSpec] is also non-null. When set, the proxy synthesizes a
   * `tools/call name=setSessionTargetForBoundDevice` request after the device
   * bind succeeds so the target's tools are visible from the agent's first
   * tool call.
   */
  private val initialTarget: String? = null,
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

  /** Test-only view of the most recently tracked `device` tool call (null when none). */
  internal val trackedDeviceToolCall: String? get() = lastDeviceToolCall.get()

  // The last 'setSessionTargetForBoundDevice' tool call -- replayed on reconnect so
  // a daemon restart preserves the per-session target override the user pinned via
  // `device connect --target` (or `mcp --target`). Tracked separately from the
  // device-bind so the replay order (device first, then target) stays deterministic.
  private val lastTargetToolCall = AtomicReference<String?>(null)

  /** Test-only view of the most recently tracked target-bind tool call (null when none). */
  internal val trackedTargetToolCall: String? get() = lastTargetToolCall.get()

  // Whether the proxy has already injected its startup device-bind from
  // [initialDeviceSpec]. Guard against double-injection across reconnects: the
  // initial injection happens after the client's first `notifications/initialized`;
  // subsequent reconnects rely on the replay path (lastDeviceToolCall) instead.
  private val hasInjectedInitialDeviceBind = AtomicBoolean(false)

  // The client's requested protocol version -- used to bridge version mismatches between
  // the client and the MCP SDK (e.g., client wants 2025-11-25 but SDK only supports 2025-06-18)
  private val clientProtocolVersion = AtomicReference<String?>(null)

  // Queue for SSE notifications from daemon -> client
  private val notificationQueue = LinkedBlockingQueue<String>(10_000)

  // Whether the proxy is shutting down
  private val shutdownRequested = AtomicBoolean(false)

  // The daemon process we launched (if any) -- prevents double-launching
  private val daemonProcess = AtomicReference<Process?>(null)

  // True iff [waitForDaemon] hit its [DAEMON_WAIT_TIMEOUT_SECONDS] deadline
  // without ever seeing a reachable daemon. Read by [forwardRequest]: when set,
  // the very first request after startup short-circuits to a JSON-RPC error
  // envelope on ConnectException instead of spinning for another `maxRetryMs`
  // (default 120s). Without this, the user-visible "daemon unavailable" surface
  // would land at ~3 minutes total (60s startup wait + 120s forwardRequest
  // retry) instead of the ~60s the startup wait advertises. Cleared by the
  // first successful forwardRequest so a daemon that recovers mid-session
  // doesn't keep getting the fast-fail behavior.
  private val daemonStartupFailed = AtomicBoolean(false)

  fun run(): Int {
    System.setProperty("java.awt.headless", "true")

    // Capture stdout BEFORE any redirection -- this is the JSON-RPC pipe to the MCP client
    val stdoutForTransport = System.out
    val origStderr = System.err

    // File-based logging to the app data dir's mcp.log (stderr may be swallowed by MCP clients).
    // Goes through getDefaultAppDataDirectory() so TRAILBLAZE_HOME isolation is honored.
    val logDir = TrailblazeDesktopUtil.getDefaultAppDataDirectory()
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

        // Decide injection ordering relative to forwarding for the two trigger
        // shapes the proxy supports:
        //
        //  - `notifications/initialized`: the conventional MCP completion
        //    signal. The daemon's MCP state machine only considers the session
        //    fully initialized AFTER it receives this notification. Posting the
        //    synthetic `tools/call name=device` before forwarding would race the
        //    daemon's session setup and could be rejected with "Server not
        //    initialized" (consuming the one-shot CAS in the process, leaving
        //    the agent's first real call unbound). Forward first, then inject —
        //    mirrors `reInitializeSession`'s replay order.
        //
        //  - `tools/call`: the lazy-client fallback for MCP clients that skip
        //    `notifications/initialized` entirely. If we forward first, the
        //    daemon executes the client's real tool call on a session that's
        //    not yet device-bound — exactly the bug this injection exists to
        //    prevent. Inject first so the device-bind lands before the real
        //    call.
        //
        // The CAS guard in [maybeInjectInitialDeviceBind] ensures the injection
        // still fires at most once across the proxy's lifetime regardless of
        // which trigger arrives first; subsequent reconnects rely on
        // [reInitializeSession]'s replay path.
        val isInitializedNotification = isInitializedNotification(line)
        if (!isInitializedNotification) {
          maybeInjectInitialDeviceBind(line, log)
        }

        val response = forwardRequest(line, log)
        if (response != null) {
          log("<- ${response.take(200)}")
          writeToStdout(stdoutForTransport, response)
        }

        if (isInitializedNotification) {
          maybeInjectInitialDeviceBind(line, log)
        }
      }
    } catch (e: Exception) {
      log("STDIO read error: ${e.message}")
    }

    log("Client disconnected -- shutting down.")
    shutdownRequested.set(true)
    httpClient.close()
    logWriter.close()
    return TrailblazeExitCode.SUCCESS.code
  }

  /**
   * Block until the daemon is reachable, attempting to start it if not running.
   * Periodically retries starting the daemon in case the first attempt failed.
   *
   * Bounded by [DAEMON_WAIT_TIMEOUT_SECONDS]. Without a cap, a missing launcher
   * JAR or wedged install would make the proxy spin forever while the MCP
   * client (Claude Code, etc.) hangs waiting for a JSON-RPC response that will
   * never come. When the cap is hit we return anyway and let the first
   * forwarded request fail with a proper JSON-RPC error envelope so the client
   * surfaces "daemon unavailable" to the user rather than appearing frozen.
   */
  private fun waitForDaemon(log: (String) -> Unit) {
    if (isDaemonReachable()) {
      log("Daemon is reachable.")
      return
    }

    // With auto-start disabled, no daemon will ever appear on its own — polling the full
    // DAEMON_WAIT_TIMEOUT_SECONDS window would just hang the MCP client. Fail fast so the
    // first forwarded request returns the "daemon unavailable" JSON-RPC error immediately.
    if (isDaemonAutoStartDisabled()) {
      log(
        "Daemon auto-start is disabled (TRAILBLAZE_DISABLE_DAEMON_AUTOSTART) and no daemon " +
          "is reachable — failing fast instead of waiting. Start one with: trailblaze app",
      )
      daemonStartupFailed.set(true)
      return
    }

    log("Daemon not running -- attempting to start...")
    startDaemon(log)

    val deadline = System.currentTimeMillis() + DAEMON_WAIT_TIMEOUT_SECONDS * 1000L
    var attempts = 0
    while (!shutdownRequested.get()) {
      if (isDaemonReachable()) {
        log("Daemon is reachable.")
        return
      }
      if (System.currentTimeMillis() >= deadline) {
        log(
          "Gave up waiting for daemon after ${DAEMON_WAIT_TIMEOUT_SECONDS}s. " +
            "Proceeding anyway — the first forwarded request will fail-fast " +
            "with a JSON-RPC error (skipping the normal ${maxRetryMs}ms retry " +
            "window) so the client can surface the problem promptly.",
        )
        daemonStartupFailed.set(true)
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
   * Start the daemon in headless mode.
   * Skips if a previously launched daemon process is still alive.
   */
  private fun startDaemon(log: (String) -> Unit) {
    if (isDaemonAutoStartDisabled()) {
      log("Daemon auto-start disabled via TRAILBLAZE_DISABLE_DAEMON_AUTOSTART. Start it manually with: trailblaze app")
      return
    }
    val existing = daemonProcess.get()
    if (existing != null && existing.isAlive) {
      log("Daemon process still starting -- skipping duplicate launch.")
      return
    }

    val launcher = findLauncher()
    if (launcher == null) {
      log("Cannot auto-start daemon: trailblaze launcher not found. Start it manually with: trailblaze app")
      return
    }

    val command = mutableListOf(launcher.absolutePath, "app", "--headless")

    log("Starting daemon: ${launcher.name} app --headless")

    try {
      val pb = ProcessBuilder(command)
      if (port != TrailblazeDevicePort.TRAILBLAZE_DEFAULT_HTTP_PORT) {
        pb.environment()["TRAILBLAZE_PORT"] = port.toString()
      }
      // Append (not DISCARD) so a spawned daemon that exits — e.g. losing the port-bind race
      // to a concurrent launch — leaves its exit reason recoverable from the daemon log.
      val daemonLogFile = xyz.block.trailblaze.ui.TrailblazeDesktopUtil.getDaemonLogFile()
      pb.redirectOutput(ProcessBuilder.Redirect.appendTo(daemonLogFile))
      pb.redirectError(ProcessBuilder.Redirect.appendTo(daemonLogFile))
      val process = pb.start()
      daemonProcess.set(process)
      log("Daemon process launched. Log: ${daemonLogFile.absolutePath}")
    } catch (e: Exception) {
      log("Failed to start daemon: ${e.message}")
    }
  }

  private fun findLauncher(): File? = findTrailblazeLauncher()

  /**
   * Forward a JSON-RPC request to the daemon, retrying if it's down.
   * Returns the response body, or a JSON-RPC error if retries are exhausted.
   *
   * Fast-fail interaction with [waitForDaemon]: when [daemonStartupFailed] is
   * set (waitForDaemon already exhausted its [DAEMON_WAIT_TIMEOUT_SECONDS]
   * deadline), a ConnectException on the first attempt short-circuits the
   * retry loop and emits the error envelope immediately — so the client sees
   * "daemon unavailable" at ~60s total instead of ~180s (60s startup +
   * default 120s retry window). The flag is cleared on the first successful
   * httpPost so a daemon that recovers mid-session reverts to the normal
   * retry behavior.
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

        // First successful post — the daemon recovered. Clear the
        // startup-failure flag so any future ConnectException uses the normal
        // retry window again.
        daemonStartupFailed.set(false)

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
        // Fast-fail: waitForDaemon already exhausted its deadline. Don't burn
        // another `maxRetryMs` retrying — surface the failure to the client
        // immediately so the user sees what's wrong at ~60s instead of ~3min.
        // CAS the flag to false so subsequent calls (if the client retries
        // after the error) get the normal retry behavior; the failure has
        // been surfaced once already.
        if (daemonStartupFailed.compareAndSet(true, false)) {
          log("Daemon startup deadline already exhausted -- failing fast on first ConnectException.")
          lastError = e.message
          break
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
   *
   * `internal` so the unit suite can pin the replay-tracking contract (which
   * tool names + argument shapes get persisted into `lastDeviceToolCall` /
   * `lastTargetToolCall`) without needing a live daemon.
   */
  internal fun trackForReplay(jsonRpc: String) {
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

    // Track device connect calls (ANDROID, IOS, WEB, CONNECT actions) and the
    // matching session-scoped target binding. Both need to survive a daemon
    // restart: without the target capture, a client-issued
    // `setSessionTargetForBoundDevice` would only ever apply for the lifetime
    // of one daemon process — `reInitializeSession` already replays
    // `lastTargetToolCall`, but it stays null unless we record it here.
    if (method == "tools/call") {
      val params = root["params"]?.jsonObject ?: return
      val toolName = params["name"]?.jsonPrimitive?.content ?: return
      when (toolName) {
        "device" -> {
          val arguments = params["arguments"]?.jsonObject
          val action = arguments?.get("action")?.jsonPrimitive?.content
          if (action in DEVICE_CONNECT_ACTIONS) {
            lastDeviceToolCall.set(jsonRpc)
          }
        }
        "setSessionTargetForBoundDevice" -> {
          // Record the latest target binding (whether issued by the client or
          // synthesized by the proxy's own startup injection). The replay path
          // in `reInitializeSession` reads this and re-posts it after the
          // device bind on every reconnect, so an agent that retargets
          // mid-session keeps its target after a daemon restart instead of
          // silently reverting to the startup-injected (or null) value.
          lastTargetToolCall.set(jsonRpc)
        }
      }
    }
  }

  /**
   * Re-establish a session with the daemon after it restarts.
   * Replays the initialize handshake, the device connect, and the target binding
   * (if either was previously made — whether by the client or by the proxy's own
   * startup-from-env injection in [maybeInjectInitialDeviceBind]).
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

      // Order matters: target-bind must come AFTER device-bind because
      // setSessionTargetForBoundDevice is keyed on the session's currently-bound
      // device. Replay in the same order the proxy issued them originally.
      lastTargetToolCall.get()?.let { targetCall ->
        log("Replaying target binding...")
        httpPost(rewriteRequestId(targetCall), log)
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
   * Synthesize a `tools/call name=device` and post it directly to the daemon
   * so the agent's first tool call runs against a session that's already
   * device-bound — even agents that don't know the `device` tool exists pick
   * up the resolved device for free.
   *
   * Device spec resolution (mirrors the CLI's three-tier chain in
   * [resolveDeviceWithAutodetect]):
   *
   *   1. `--device` flag or `TRAILBLAZE_DEVICE` env var, collapsed into
   *      [initialDeviceSpec] by [McpCommand].
   *   2. **Autodetect** via [autodetectSingleConnectedDevice]: when exactly
   *      one real device is connected (the playwright-native virtual entry
   *      is filtered out, same as PR #3456 for the CLI), use it. This is
   *      what closes the OOBE gap for single-device MCP users — opening
   *      Claude Desktop with no env var and one emulator booted now "just
   *      works" without teaching the agent the `device` tool.
   *   3. Neither resolved → no auto-bind, agent gets the unbound-device
   *      error on its first tool call (legacy behavior).
   *
   * If [initialTarget] is also set (via `--target` or `TRAILBLAZE_TARGET`),
   * a `setSessionTargetForBoundDevice` is posted right after the device bind —
   * same idea, just for the per-session target override. The target is applied
   * **whichever way the device was resolved** (explicit tier-1 OR autodetect
   * tier-2) — a user who set `TRAILBLAZE_TARGET=myapp` without setting
   * `TRAILBLAZE_DEVICE` still wants their target applied to whichever device
   * autodetect picked. Tier-3 (no device bound at all) is the only case
   * where target is skipped, since there's no session to scope it onto.
   *
   * Ordering relative to forwarding is the caller's responsibility — see the
   * main loop in [run] where `notifications/initialized` triggers
   * forward-then-inject (so the daemon's MCP state machine has marked the
   * session initialized) and `tools/call` triggers inject-then-forward (so the
   * device-bind lands before the client's real first tool call).
   *
   * Idempotent: [hasInjectedInitialDeviceBind] guards against double-injection
   * across reconnects. Subsequent daemon restarts replay the same calls via the
   * existing `lastDeviceToolCall` / `lastTargetToolCall` track-and-replay path
   * inside [reInitializeSession], not by re-firing this injection.
   */
  private fun maybeInjectInitialDeviceBind(clientLine: String, log: (String) -> Unit) {
    // Cheap atomic read before the JSON parse: once the one-shot injection has
    // already fired, every subsequent stdin line would otherwise re-parse JSON
    // just to confirm it isn't `notifications/initialized`. Short-circuiting
    // here keeps the steady-state path a single atomic .get().
    if (hasInjectedInitialDeviceBind.get()) return
    if (!isInitialInjectionTrigger(clientLine)) return

    // Resolve the device spec via the three-tier chain that mirrors the CLI's
    // [resolveDeviceWithAutodetect]:
    //   1. explicit --device / TRAILBLAZE_DEVICE (already collapsed into
    //      [initialDeviceSpec] by McpCommand via resolveCliDevice)
    //   2. autodetect: when exactly one real device is connected, use it
    //   3. no auto-bind (agent will hit the unbound-device error on its first
    //      tool call — same behavior as before this autodetect existed)
    //
    // Source label is carried alongside the spec so the subsequent log line
    // distinguishes "explicit env/flag" from "autodetect" in the proxy's log
    // file. The PR's manual-verification step grep for the tier-1-specific
    // phrasing to confirm an env-pinned shell doesn't accidentally fall into
    // autodetect; uniform wording would break that signal.
    //
    // Important: do not flip the CAS until we actually have a spec to inject.
    // Otherwise an autodetect that resolves to "0 or 2+ devices" on the
    // notifications/initialized trigger would burn the one-shot guard, and the
    // followup `tools/call` trigger (which could in principle reach a state
    // where the device set changes — though rare in practice) would silently
    // skip its second chance. Resolving first then CASing keeps the
    // "fire at most once" semantics without prematurely closing the window.
    val explicitSpec = initialDeviceSpec?.takeIf { it.isNotBlank() }
    val resolvedSpec: String
    val sourceLabel: String
    if (explicitSpec != null) {
      resolvedSpec = explicitSpec
      sourceLabel = "from --device / TRAILBLAZE_DEVICE"
    } else {
      resolvedSpec = autodetectSingleConnectedDevice(log) ?: return
      // [resolveAutodetectFromDeviceList] already logged the "Auto-using only
      // connected device: <id>" line at the resolve site, so this label is
      // mostly for symmetry on the "Auto-binding device" line below.
      sourceLabel = "from autodetect"
    }

    // CAS so a duplicate trigger (e.g. duplicate `notifications/initialized` or a
    // tools/call arriving after notifications/initialized already injected)
    // doesn't re-inject. Reconnects replay via reInitializeSession instead.
    if (!hasInjectedInitialDeviceBind.compareAndSet(false, true)) return

    val deviceCall = synthesizeDeviceBindCall(resolvedSpec) ?: run {
      log("Could not parse initial device spec '$resolvedSpec' — skipping auto-bind.")
      return
    }

    try {
      log("Auto-binding device $sourceLabel: $resolvedSpec")
      httpPost(deviceCall, log)
      lastDeviceToolCall.set(deviceCall)
    } catch (e: Exception) {
      log("Initial device-bind injection failed: ${e.message}. Agent will need to call `device` manually.")
      return
    }

    if (!initialTarget.isNullOrBlank()) {
      val targetCall = synthesizeTargetBindCall(initialTarget)
      try {
        log("Auto-binding target from --target: $initialTarget")
        httpPost(targetCall, log)
        lastTargetToolCall.set(targetCall)
      } catch (e: Exception) {
        log("Initial target-bind injection failed: ${e.message}. Agent's toolset may not reflect the requested target.")
      }
    }

    // Tell the client to re-fetch the tool list — device + target binding may
    // have changed which target-specific tools are available. Without this the
    // agent's cached tool descriptors could be stale until the first daemon
    // notification arrives.
    notificationQueue.put("""{"jsonrpc":"2.0","method":"notifications/tools/list_changed"}""")
  }

  /**
   * Mirrors the CLI's `autodetectSingleConnectedDevice` (in [CliInfrastructure])
   * — opens a short-lived one-shot MCP session to the daemon, calls
   * `device LIST`, filters out the always-present virtual web entry
   * (`web/playwright-native`), and returns the fully-qualified device spec iff
   * exactly one real device is connected. Returns null when the device count
   * is 0 or 2+, or when the daemon probe fails for any reason.
   *
   * This is the MCP-side equivalent of the third tier in the CLI's resolver
   * chain (`--device` → `TRAILBLAZE_DEVICE` → autodetect-single → give up).
   * Single-device MCP users who haven't set `TRAILBLAZE_DEVICE` get the same
   * zero-setup experience as `trailblaze snapshot`: the proxy auto-binds the
   * only connected device on startup, and the agent's first tool call lands
   * on a session that's already device-bound.
   *
   * The playwright-native virtual entry is filtered the same way the CLI's
   * autodetect does it — counting it would mis-classify the common case
   * "1 emulator + 0 browsers" as `Multiple`, defeating the purpose of the
   * autodetect.
   *
   * Best-effort: any exception (daemon down, connection refused, parse error)
   * is logged and swallowed; the inject path falls through to "no auto-bind"
   * so the agent gets the same unbound-device error it would have gotten
   * before this autodetect existed. Cancellation propagates (never swallowed)
   * so Ctrl+C still works.
   *
   * Opens a separate one-shot MCP session rather than reusing the proxy's
   * active session because the proxy session is in the middle of the MCP
   * handshake at this point — the synthetic device-bind has yet to be
   * injected. Reusing the proxy session would require interleaving a LIST
   * call with the client's actual JSON-RPC traffic on the same session ID,
   * which the proxy's request-response pump isn't set up to demultiplex.
   * The one-shot is cheap (connect → call → close, ~one round-trip extra).
   */
  internal fun autodetectSingleConnectedDevice(log: (String) -> Unit): String? {
    return try {
      runBlocking {
        // Bound the probe aggressively: the proxy's stdin loop is blocked
        // until this returns, so a hung daemon shouldn't delay the client's
        // first tool call indefinitely. [CliMcpClient.connectOneShot] uses a
        // 3-minute default request timeout (sized for AI tool calls) — way
        // too long for a single read-only LIST probe. If the daemon can't
        // list devices in [AUTODETECT_PROBE_TIMEOUT_MS], we fall through to
        // "no auto-bind" and the agent gets the same unbound-device error
        // they'd have gotten before this autodetect existed.
        kotlinx.coroutines.withTimeout(AUTODETECT_PROBE_TIMEOUT_MS) {
          CliMcpClient.connectOneShot(port = port).use { client ->
            val result = client.callTool("device", mapOf("action" to "LIST"))
            if (result.isError) {
              log("Autodetect: daemon device LIST returned error — skipping auto-bind.")
              return@use null
            }
            // Filter via the shared helper on `CliMcpClient` so the CLI's
            // `autodetectSingleConnectedDevice` (in [CliInfrastructure]) and
            // this MCP-side variant stay in lock-step. Adding a new virtual
            // entry (e.g. a future headless-iOS sim placeholder) means one
            // edit, not two.
            val realDevices =
              with(CliMcpClient) { parseDeviceList(result.content).filterRealDevices() }
            resolveAutodetectFromDeviceList(realDevices, log)
          }
        }
      }
    } catch (e: kotlinx.coroutines.CancellationException) {
      // Never swallow cancellation — Ctrl+C / structured cancel needs to
      // propagate, not turn into a false "daemon unreachable" autodetect-skip.
      throw e
    } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
      log(
        "Autodetect: daemon probe exceeded ${AUTODETECT_PROBE_TIMEOUT_MS}ms — " +
          "skipping auto-bind. Agent will hit the unbound-device error on first tool call; " +
          "set TRAILBLAZE_DEVICE explicitly to skip the probe.",
      )
      null
    } catch (e: Exception) {
      log("Autodetect: daemon probe failed (${e.message}) — skipping auto-bind.")
      null
    }
  }


  /**
   * Pure decision function: given the filtered device list (no virtual web
   * entry), return the fully-qualified spec iff exactly one device is present.
   *
   * Extracted from [autodetectSingleConnectedDevice] so the unit suite can
   * pin the size-based decision (1 → resolve, 0 or 2+ → skip) without a live
   * daemon. The filter rule is asserted at the call boundary by passing
   * pre-filtered fixtures (mirrors what the daemon-fed code path would yield
   * once the playwright-native virtual entry is dropped).
   */
  internal fun resolveAutodetectFromDeviceList(
    realDevices: List<CliMcpClient.DeviceListEntry>,
    log: (String) -> Unit,
  ): String? = when (realDevices.size) {
    0 -> {
      log("Autodetect: no devices connected — skipping auto-bind.")
      null
    }
    1 -> {
      val spec = realDevices.single().toFullyQualifiedDeviceId()
      log("Auto-using only connected device: $spec")
      spec
    }
    else -> {
      log(
        "Autodetect: ${realDevices.size} devices connected " +
          "(${realDevices.joinToString(", ") { it.toFullyQualifiedDeviceId() }}) " +
          "— skipping auto-bind. Pass --device or set TRAILBLAZE_DEVICE to disambiguate.",
      )
      null
    }
  }

  /**
   * True iff [jsonRpc] is one of the two messages that mean "the client is past
   * the MCP handshake and ready for tool calls":
   *
   *  - `notifications/initialized` — the conventional MCP completion signal.
   *  - `tools/call` — the lazy-client fallback: some MCP clients skip
   *    `notifications/initialized` entirely and jump straight to invoking a
   *    tool. Without this fallback, env-pinned device-binding would silently
   *    leak past startup for those clients and the LLM's first call would see
   *    an unbound session.
   *
   * Triggering on either path (instead of just `notifications/initialized`)
   * keeps the auto-bind UX consistent regardless of which client integration
   * the user picked. The CAS guard in [maybeInjectInitialDeviceBind] still
   * enforces one-shot — if both messages arrive, only the first injects.
   */
  internal fun isInitialInjectionTrigger(jsonRpc: String): Boolean {
    val root = runCatching { Json.parseToJsonElement(jsonRpc).jsonObject }.getOrNull() ?: return false
    val method = root["method"]?.jsonPrimitive?.content ?: return false
    return method == "notifications/initialized" || method == "tools/call"
  }

  /**
   * Narrower discriminator used by the main loop to pick whether the startup
   * injection should fire BEFORE or AFTER forwarding the current client line.
   *
   * `notifications/initialized` callers want forward-then-inject so the daemon's
   * MCP session is fully marked initialized before the synthetic device-bind
   * arrives. `tools/call` callers want inject-then-forward so the device-bind
   * lands ahead of the client's real first tool call (otherwise the call runs
   * against an unbound session — the exact bug the injection exists to
   * prevent).
   *
   * Internal-only — the main loop is the only caller. Kept separate from
   * [isInitialInjectionTrigger] so the trigger contract (which messages
   * eligible for injection) stays decoupled from the ordering policy.
   */
  internal fun isInitializedNotification(jsonRpc: String): Boolean {
    val root = runCatching { Json.parseToJsonElement(jsonRpc).jsonObject }.getOrNull() ?: return false
    val method = root["method"]?.jsonPrimitive?.content ?: return false
    return method == "notifications/initialized"
  }

  /**
   * Build the JSON-RPC body for `tools/call name=device` from a CLI-style spec
   * (`android`, `android/emulator-5554`, `ios/SIM-X`, `web`, `web/foo`).
   *
   * Returns null when the spec doesn't parse to a known platform — caller logs
   * and skips the injection rather than crashing the proxy startup.
   *
   * The synthesized arguments mirror what the existing `DeviceConnectCommand`
   * sends via `ensureDevice` (action = platform name uppercased; deviceId =
   * instance suffix if specified), so the daemon's device handler treats this
   * the same as a manual `device(action=ANDROID, deviceId=emulator-5554)` call.
   */
  internal fun synthesizeDeviceBindCall(spec: String): String? {
    val parts = spec.split("/", limit = 2)
    val platform = parts[0].trim().uppercase()
    if (platform !in DEVICE_CONNECT_ACTIONS) return null
    val instance = parts.getOrNull(1)?.trim()?.takeIf { it.isNotBlank() }
    val args = buildJsonObject {
      put("action", platform)
      if (instance != null) put("deviceId", instance)
    }
    return buildJsonRpcToolCall(toolName = "device", arguments = args)
  }

  /**
   * Build the JSON-RPC body for `tools/call name=setSessionTargetForBoundDevice`.
   * Caller normalizes [target] (lowercase, blank-trimmed) at the CLI boundary so
   * this helper just wraps the value.
   *
   * The argument name is `appTargetId` to match the daemon-side tool schema
   * (see `TrailblazeMcpBridgeImpl.setSessionTargetForDevice` and `CliMcpClient
   * .setSessionTargetForBoundDevice`, both of which key on `appTargetId`).
   * Using `target` here would silently no-op — the daemon would not recognize
   * the argument and the requested app target would never bind.
   */
  internal fun synthesizeTargetBindCall(target: String): String {
    val args = buildJsonObject { put("appTargetId", target) }
    return buildJsonRpcToolCall(toolName = "setSessionTargetForBoundDevice", arguments = args)
  }

  /**
   * Builds a JSON-RPC `tools/call` request body with a fresh UUID-derived id.
   * Centralizes the structure so the request the synthesized injections post
   * matches what a real MCP client would send byte-for-byte (modulo id), which
   * keeps the daemon's handlers exercising the same code path.
   */
  private fun buildJsonRpcToolCall(
    toolName: String,
    arguments: kotlinx.serialization.json.JsonObject,
  ): String {
    val id = java.util.UUID.randomUUID().toString().take(8)
    val request = buildJsonObject {
      put("jsonrpc", "2.0")
      put("id", "mcp-proxy-init-$id")
      put("method", "tools/call")
      put(
        "params",
        buildJsonObject {
          put("name", toolName)
          put("arguments", arguments)
        },
      )
    }
    return Json.encodeToString(kotlinx.serialization.json.JsonObject.serializer(), request)
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

    /**
     * Upper bound on how long the proxy will block waiting for the daemon to become
     * reachable on startup. Without a cap, a missing launcher / wedged install /
     * bad port would make the proxy spin forever while the MCP client hangs. With
     * the cap, we proceed to the main loop after this many seconds and let the
     * first forwarded request surface a structured JSON-RPC "daemon unavailable"
     * error to the client so the user sees what's wrong instead of a frozen UI.
     * 60s is generous — normal daemon cold-start is <30s.
     */
    private const val DAEMON_WAIT_TIMEOUT_SECONDS = 60L

    /** If an SSE connection lasts less than this, the session is likely stale. */
    private const val SSE_MIN_STABLE_MS = 5_000L

    /** Device actions that represent a connection/binding (should be replayed on reconnect). */
    private val DEVICE_CONNECT_ACTIONS = setOf("ANDROID", "IOS", "WEB", "CONNECT")

    /**
     * Upper bound on the autodetect daemon-probe duration. Sized so that a
     * hung daemon doesn't delay the agent's first tool call beyond a couple
     * seconds — the probe is a one-shot read-only LIST call, latency is in
     * the tens-of-ms range on a healthy daemon, so 5 seconds is plenty
     * headroom. Configurable here (not via env) because there's no scenario
     * a user should tune this from the outside — if your LIST genuinely
     * takes >5s, the daemon's already broken. The CLI's
     * [CliMcpClient.connectOneShot] uses a 3-minute default request timeout
     * sized for AI tool calls; we override it locally for this read-only
     * probe via [kotlinx.coroutines.withTimeout].
     */
    internal const val AUTODETECT_PROBE_TIMEOUT_MS: Long = 5_000L
  }
}

/**
 * Find an executable on the system PATH.
 * Returns the [File] if found and executable, null otherwise.
 */
internal fun findOnPath(name: String): File? {
  return try {
    val process = ProcessBuilder("which", name)
      .redirectError(ProcessBuilder.Redirect.DISCARD)
      .start()
    val path = process.inputStream.bufferedReader().readLine()?.trim()
    process.waitFor()
    if (process.exitValue() == 0 && !path.isNullOrBlank()) {
      val file = File(path)
      if (file.exists() && file.canExecute()) file else null
    } else {
      null
    }
  } catch (_: Exception) {
    null
  }
}

/**
 * Kill-switch for daemon auto-start (`cliTryStartDaemon`, the MCP proxy's re-launch). When set,
 * "daemon not running" becomes a hard failure instead of spawning a detached daemon process.
 *
 * Wired into every Gradle `Test` task so unit tests can never launch the machine-global
 * `trailblaze` from PATH — the launcher walk in [findTrailblazeLauncher] otherwise resolves a
 * Homebrew/installed CLI from a test worker and leaves orphaned daemon JVMs on the default port.
 * Read per call. `1`/`true` (case-insensitive) disables; anything else — including the `"0"` that
 * `:trailblaze-server:integrationTest` uses to opt back out — leaves auto-start on.
 *
 * [flag] is injectable so the parse contract is unit-testable ([DaemonAutoStartFlagTest]);
 * production callers use the default env read.
 */
internal fun isDaemonAutoStartDisabled(
  flag: String? = System.getenv("TRAILBLAZE_DISABLE_DAEMON_AUTOSTART"),
): Boolean = flag != null && (flag == "1" || flag.equals("true", ignoreCase = true))

/**
 * Find the trailblaze launcher executable.
 * Dev launchers export TRAILBLAZE_LAUNCHER so the subprocess uses the same script.
 * For release builds, checks next to the running JAR, then the system PATH.
 */
internal fun findTrailblazeLauncher(): File? {
  System.getenv("TRAILBLAZE_LAUNCHER")?.let { path ->
    val file = File(path)
    if (file.exists() && file.canExecute()) return file
  }

  val jarDir = McpProxy::class.java.protectionDomain?.codeSource?.location?.toURI()
    ?.let { File(it).parentFile }
  if (jarDir != null) {
    val launcher = File(jarDir, "trailblaze")
    if (launcher.exists() && launcher.canExecute()) return launcher
  }

  return findOnPath("trailblaze")
}
