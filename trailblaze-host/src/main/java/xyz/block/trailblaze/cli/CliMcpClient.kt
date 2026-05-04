package xyz.block.trailblaze.cli

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.delete
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.headers
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.plugins.timeout
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import xyz.block.trailblaze.TrailblazeVersion
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.devices.TrailblazeDevicePort
import xyz.block.trailblaze.mcp.newtools.DeviceManagerToolSet
import xyz.block.trailblaze.util.Console
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * MCP client for CLI commands to call tools on the Trailblaze daemon.
 *
 * Connects to the daemon's Streamable HTTP MCP endpoint (`/mcp`) and provides
 * a clean API for calling any MCP tool. This allows CLI commands like `trailblaze blaze`
 * and `trailblaze ask` to reuse all server-side infrastructure without new endpoints.
 *
 * Adapted from `McpTestClient` with OkHttp engine (production dependency of trailblaze-host).
 *
 * @see <a href="https://modelcontextprotocol.io/specification/2025-11-25">MCP Specification</a>
 */
class CliMcpClient(
  private val serverUrl: String = "http://localhost:${TrailblazeDevicePort.TRAILBLAZE_DEFAULT_HTTP_PORT}/mcp",
  private val requestTimeoutMs: Long = DEFAULT_REQUEST_TIMEOUT_MS,
  private val toolProfile: String = "MINIMAL",
  /**
   * Optional value for the `X-Trailblaze-Origin` header sent on initialize.
   * Defaults to the CLI argv captured by [captureOrigin]; null means no
   * header is sent (used when an embedded caller has no meaningful origin).
   */
  origin: String? = capturedOrigin,
) : AutoCloseable {

  private val httpClient = HttpClient(OkHttp) {
    install(HttpTimeout) {
      requestTimeoutMillis = requestTimeoutMs
      connectTimeoutMillis = CONNECT_TIMEOUT_MS
      socketTimeoutMillis = requestTimeoutMs
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

  internal var sessionId: String? = null
  private val requestId = AtomicInteger(0)
  internal var terminateSessionOnClose: Boolean = false
  private var hasConnectedDevice: Boolean = false

  /** Whether this client reconnected to a session that already has a device. */
  var hasExistingDevice: Boolean = false
    internal set

  val isInitialized: Boolean get() = sessionId != null

  /**
   * Origin header value sent on initialize. Defaults to the captured CLI argv
   * (set by [TrailblazeCli.captureOrigin]) so the daemon can identify which
   * CLI command opened the session in busy-error messages. Tests and embedded
   * callers can override this.
   */
  internal var originHeader: String? = origin

  /**
   * Initializes an MCP session with the server.
   *
   * Performs the JSON-RPC `initialize` handshake as specified in the MCP protocol.
   * Must be called before [callTool].
   */
  suspend fun initialize() {
    val response = sendRequest(
      method = "initialize",
      params = buildJsonObject {
        put("protocolVersion", PROTOCOL_VERSION)
        putJsonObject("capabilities") {}
        putJsonObject("clientInfo") {
          put("name", CLIENT_NAME)
          put("version", TrailblazeVersion.displayVersion)
        }
      },
    )

    val result = response["result"]?.jsonObject
    if (result == null) {
      val error = response["error"]?.jsonObject?.get("message")?.jsonPrimitive?.content
      throw CliMcpException("MCP initialization failed: ${error ?: "no result in response"}")
    }

    // MCP spec requires sending initialized notification after successful handshake.
    // Notifications MUST NOT have an `id` field per JSON-RPC 2.0.
    sendNotification(
      method = "notifications/initialized",
      params = buildJsonObject {},
    )
  }

  /**
   * Lists all available MCP tools from the server.
   *
   * Calls the `tools/list` MCP method to retrieve tool names, descriptions,
   * and input schemas. Useful for CLI discovery and agent tool enumeration.
   *
   * @return List of tool descriptors, or empty list on error
   */
  suspend fun listTools(): List<McpToolInfo> {
    val response = sendRequest(
      method = "tools/list",
      params = buildJsonObject {},
    )

    val tools = response["result"]?.jsonObject?.get("tools")?.jsonArray
      ?: return emptyList()

    return tools.mapNotNull { tool ->
      val obj = tool.jsonObject
      val name = obj["name"]?.jsonPrimitive?.content ?: return@mapNotNull null
      val description = obj["description"]?.jsonPrimitive?.content ?: ""
      val inputSchema = obj["inputSchema"]?.jsonObject
      McpToolInfo(name = name, description = description, inputSchema = inputSchema)
    }
  }

  /**
   * Calls an MCP tool by name with the given arguments.
   *
   * @param name Tool name (e.g., "blaze", "ask", "device")
   * @param arguments Map of argument name to value
   * @return Tool result
   */
  suspend fun callTool(name: String, arguments: Map<String, Any?>): ToolResult {
    val argsJson = buildJsonObject {
      arguments.forEach { (key, value) ->
        putAny(key, value)
      }
    }
    return callTool(name, argsJson)
  }

  /**
   * Calls an MCP tool by name with JSON arguments.
   */
  suspend fun callTool(name: String, arguments: JsonObject): ToolResult {
    val response = sendRequest(
      method = "tools/call",
      params = buildJsonObject {
        put("name", name)
        put("arguments", arguments)
      },
    )

    response["error"]?.jsonObject?.let { error ->
      val errorMessage = error["message"]?.jsonPrimitive?.content ?: "Unknown error"
      return ToolResult(content = errorMessage, isError = true)
    }

    val result = response["result"]?.jsonObject
    val content = result?.get("content")?.jsonArray?.firstOrNull()?.jsonObject
    val text = content?.get("text")?.jsonPrimitive?.content ?: response.toString()
    val isError = result?.get("isError")?.jsonPrimitive?.content?.toBoolean() ?: false

    return ToolResult(content = text, isError = isError)
  }

  /**
   * Sends a raw JSON-RPC request to the MCP server.
   */
  suspend fun sendRequest(method: String, params: JsonObject): JsonObject {
    val id = requestId.incrementAndGet()
    val body = buildJsonObject {
      put("jsonrpc", "2.0")
      put("id", id)
      put("method", method)
      put("params", params)
    }

    val response = httpClient.post(serverUrl) {
      contentType(ContentType.Application.Json)
      headers {
        append("Accept", "application/json, text/event-stream")
        sessionId?.let { append("mcp-session-id", it) }
        // Set tool profile on initialization: trail mode → MINIMAL, blaze mode → FULL
        if (sessionId == null) {
          append("X-Tool-Profile", toolProfile)
          // X-Trailblaze-Origin: tells the daemon what command opened this
          // session ("snapshot -d android", "blaze", …). Surfaced in the
          // device-busy error so users know which CLI command is currently
          // driving the device. Only sent on the first request because the
          // server reads it once at session creation.
          originHeader?.takeIf { it.isNotBlank() }?.let { append(ORIGIN_HEADER, it) }
        }
      }
      setBody(body.toString())
    }

    // Check HTTP status before attempting to parse the response body
    if (response.status.value !in 200..299) {
      val bodyText = try { response.bodyAsText() } catch (_: Exception) { "" }
      return buildJsonObject {
        putJsonObject("error") {
          put("message", "HTTP ${response.status.value}: $bodyText".take(500))
        }
      }
    }

    response.headers["mcp-session-id"]?.let { sessionId = it }

    val jsonText = parseSSEResponse(response.bodyAsText())
    return try {
      json.decodeFromString<JsonObject>(jsonText)
    } catch (e: Exception) {
      // Use JSON-RPC error shape so callTool() handles it correctly
      buildJsonObject {
        putJsonObject("error") {
          put("message", e.message ?: "Parse error")
        }
      }
    }
  }

  /**
   * Sends a JSON-RPC notification (no `id` field, no response expected).
   *
   * Per JSON-RPC 2.0, notifications MUST NOT include an `id` field.
   */
  private suspend fun sendNotification(method: String, params: JsonObject) {
    val body = buildJsonObject {
      put("jsonrpc", "2.0")
      put("method", method)
      put("params", params)
    }

    httpClient.post(serverUrl) {
      contentType(ContentType.Application.Json)
      headers {
        append("Accept", "application/json, text/event-stream")
        sessionId?.let { append("mcp-session-id", it) }
      }
      setBody(body.toString())
    }
  }

  private fun parseSSEResponse(text: String): String {
    return if (text.startsWith("data:")) {
      text.lines()
        .filter { it.startsWith("data:") }
        .map { it.removePrefix("data:").trim() }
        .filter { it.isNotEmpty() && it != "[DONE]" }
        .lastOrNull() ?: "{}"
    } else {
      text
    }
  }

  private fun JsonObjectBuilder.putAny(key: String, value: Any?) {
    when (value) {
      null -> {}
      is String -> put(key, value)
      is Number -> put(key, value)
      is Boolean -> put(key, value)
      is Map<*, *> -> put(
        key,
        buildJsonObject {
          @Suppress("UNCHECKED_CAST")
          (value as Map<String, Any?>).forEach { (k, v) -> putAny(k, v) }
        },
      )
      is List<*> -> put(
        key,
        buildJsonArray {
          value.forEach { item ->
            when (item) {
              is String -> add(JsonPrimitive(item))
              is Number -> add(JsonPrimitive(item))
              is Boolean -> add(JsonPrimitive(item))
              is Map<*, *> -> add(
                buildJsonObject {
                  @Suppress("UNCHECKED_CAST")
                  (item as Map<String, Any?>).forEach { (k, v) -> putAny(k, v) }
                },
              )
              else -> add(JsonPrimitive(item.toString()))
            }
          }
        },
      )
      else -> put(key, value.toString())
    }
  }

  /**
   * Ensures a device is connected. Supports:
   * - `null` → auto-detect (single device) or error (multiple)
   * - `"android"` / `"ios"` / `"web"` → connect by platform
   * - `"android/emulator-5556"` → connect to specific instance
   * - `"web/foo"` → connect to (or provision) a named web browser instance
   *
   * Device claim conflicts are resolved by the daemon's
   * [DeviceClaimRegistry] yield-unless-busy policy: an idle prior holder is
   * displaced silently; a busy holder produces a rich error naming the running
   * tool. The CLI no longer surfaces a takeover flag — if you need to interrupt
   * an active session, stop it explicitly and retry.
   *
   * @param webHeadless When connecting to a `web` device, controls whether the
   *   underlying Playwright browser launches headless. Defaults to `true`. The
   *   CLI's `--headless false` flag flips this to surface a visible window.
   *   Ignored for non-web platforms.
   * @return null on success, or an error message on failure
   */
  suspend fun ensureDevice(
    deviceSpec: String? = null,
    webHeadless: Boolean = true,
  ): String? {
    // When reusing a session that already has a device, skip the expensive driver reconnect.
    // The daemon's Maestro driver persists across CLI invocations within the same MCP session,
    // so force-reconnecting on every call just adds latency (~2-5s on iOS).
    if (hasExistingDevice) {
      val infoResult = callTool("device", mapOf("action" to "INFO"))
      val currentPlatform = if (!infoResult.isError) parseDevicePlatform(infoResult) else null
      val currentInstanceId = if (!infoResult.isError) parseConnectedInstanceId(infoResult) else null
      val currentSpec = currentPlatform?.let {
        "${it.name.lowercase()}${if (currentInstanceId != null) "/$currentInstanceId" else ""}"
      }

      if (deviceSpec == null) {
        logSessionReuse(currentSpec)
        return null // same session, same device — ready to go
      }

      // Explicit device spec — check if it matches what's already connected
      if (currentPlatform != null) {
        val platformName = currentPlatform.name.lowercase()
        val specMatchesFull = currentInstanceId != null && deviceSpec.equals(currentSpec, ignoreCase = true)
        val specMatchesPlatformOnly = deviceSpec.equals(platformName, ignoreCase = true)
        if (specMatchesFull || specMatchesPlatformOnly) {
          logSessionReuse(currentSpec)
          return null // same device
        }
      }
      Console.info("Switching device — starting new session.")
    }

    if (deviceSpec != null) {
      // Support multiple formats:
      //   "android/emulator-5554"  → platform + instance
      //   "android"                → platform only
      //   "web/foo"                → web platform + virtual instance ID
      //   "emulator-5554"          → instance ID only (resolve platform from device list)
      val parts = deviceSpec.split("/", limit = 2)
      val platform = TrailblazeDevicePlatform.fromString(parts[0])

      if (platform != null) {
        // First part is a known platform. Treat a missing or blank instance segment
        // (e.g. `--device web/`) as null so it falls through to the platform-default
        // path (singleton playwright-native for web) instead of sending deviceId="".
        val instanceId = parts.getOrNull(1)?.takeIf { it.isNotBlank() }
        return connectToDevice(platform, instanceId, webHeadless = webHeadless)
      }

      // Not a known platform — treat the whole spec as an instance ID
      return connectToDeviceByInstanceId(deviceSpec)
    }

    // No explicit device — check what's available and auto-select
    val listResult = callTool("device", mapOf("action" to "LIST"))
    if (listResult.isError) return "Error listing devices: ${listResult.content}"

    val mobileDevices = parseDeviceList(listResult.content)
      .filter { it.platform != TrailblazeDevicePlatform.WEB }

    return when {
      mobileDevices.size == 1 -> connectToDevice(mobileDevices[0].platform)
      mobileDevices.isEmpty() -> {
        "No mobile devices found. Connect an Android device/emulator or start an iOS simulator.\n" +
          "Or specify a device: --device android, --device ios, --device android/emulator-5554"
      }
      else -> {
        "Multiple devices found. Specify which to use with --device (-d):\n" +
          mobileDevices.joinToString("\n") { "  --device ${it.spec}" }
      }
    }
  }

  private suspend fun connectToDevice(
    platform: TrailblazeDevicePlatform,
    instanceId: String? = null,
    webHeadless: Boolean = true,
  ): String? {
    val listResult = callTool("device", mapOf("action" to "LIST"))
    if (!listResult.isError) {
      val platformDevices = parseDeviceList(listResult.content)
        .filter { it.platform == platform }

      // For mobile platforms, force the user to disambiguate when multiple instances
      // are connected. WEB instance IDs are virtual — `--device web` is always a
      // valid shorthand for the singleton playwright-native browser, and additional
      // named instances are provisioned on demand, so multiple-running entries
      // should not block an unqualified `web` selection.
      if (instanceId == null && platformDevices.size > 1 && platform != TrailblazeDevicePlatform.WEB) {
        return "Multiple ${platform.displayName} devices found. Specify which one:\n" +
          platformDevices.joinToString("\n") { "  --device ${it.spec}" }
      }

      // Validate the instance ID against available devices — policy is per-platform.
      if (instanceId != null) {
        when (platform) {
          TrailblazeDevicePlatform.ANDROID, TrailblazeDevicePlatform.IOS -> {
            val knownIds = platformDevices.map { it.instanceId }
            if (knownIds.isNotEmpty() && instanceId !in knownIds) {
              val available = knownIds.joinToString(", ") { "${platform.name.lowercase()}/$it" }
              return "Device '${platform.name.lowercase()}/$instanceId' not found. Available: $available"
            }
          }
          TrailblazeDevicePlatform.WEB -> {
            // Web devices are virtual — the daemon provisions a Playwright browser
            // on demand for any instance ID it hasn't seen yet. Skip ID validation
            // so `--device web/foo` works without requiring the user to launch
            // the browser via the desktop app first.
          }
          TrailblazeDevicePlatform.DESKTOP -> {
            // Compose desktop has exactly one logical instance (the running host
            // window — `desktop/self`). Skip ID validation here and let the daemon
            // confirm reachability via ComposeRpcClient.waitForServer().
          }
        }
      }
    }

    Console.info("Connecting to ${platform.displayName} device${if (instanceId != null) " ($instanceId)" else ""}...")
    if (platform == TrailblazeDevicePlatform.IOS) {
      Console.info("First connection may take 10–30s while the driver is set up on the device. The driver is cached and immediately available for subsequent connections.")
    }
    val args = mutableMapOf<String, Any?>("action" to platform.name)
    if (instanceId != null) args[DeviceManagerToolSet.PARAM_DEVICE_ID] = instanceId
    // Headless flag is only meaningful for WEB; mobile/desktop ignore it.
    if (platform == TrailblazeDevicePlatform.WEB) {
      args[DeviceManagerToolSet.PARAM_HEADLESS] = webHeadless
    }
    val result = callTool("device", args)
    // Server emits the rich device-busy block (`DeviceBusyException`) as a
    // successful tool response with an "Error:"-prefixed body. Pass it through
    // verbatim — the daemon already formatted it for end-user consumption.
    if (result.content.trimStart().startsWith("Error:") &&
      "is busy" in result.content
    ) {
      return result.content
    }
    if (result.isError) return "Error connecting to device: ${result.content}"

    // Per-platform post-connect setup. Each branch is the hook point for any
    // async install/warmup a platform grows; today only Web needs one.
    when (platform) {
      TrailblazeDevicePlatform.WEB ->
        PlaywrightInstallWaiter.awaitIfInstalling(result, this)?.let { return it }
      TrailblazeDevicePlatform.ANDROID, TrailblazeDevicePlatform.IOS -> {
        // No post-connect setup required. Add driver warmup or install-progress
        // polling here (e.g. iOS driver prep) if it becomes necessary.
      }
      TrailblazeDevicePlatform.DESKTOP -> {
        // No post-connect setup required — the desktop app already runs the
        // ComposeRpcServer when `enableSelfTestServer = true` (default), so the
        // device is ready as soon as the daemon accepts the claim.
      }
    }

    // Echo the full device spec so the agent knows what was connected
    val connectedId = parseConnectedInstanceId(result) ?: instanceId
    hasConnectedDevice = true
    val sessionId = getTrailblazeSessionId()
    val sessionSuffix = sessionId?.let { " (session $it)" } ?: ""
    Console.info("Connected: ${platform.name.lowercase()}/${connectedId ?: "default"}$sessionSuffix")
    if (sessionId != null) printSessionCommandMenu(sessionId)
    printDeviceSessionInfo(result)
    return null
  }

  private fun printSessionCommandMenu(sessionId: String) {
    Console.info("Session commands:")
    Console.info("  trailblaze session info      --id $sessionId   # recorded steps so far")
    Console.info("  trailblaze session save      --id $sessionId   # write *.trail.yaml you can replay")
    Console.info("  trailblaze session report    --id $sessionId   # generate HTML report for this session")
    Console.info("  trailblaze session artifacts --id $sessionId   # video, logs, screenshots")
    Console.info("  trailblaze session end       --id $sessionId   # end the session")
  }

  /**
   * Resolves a bare instance ID (e.g., "emulator-5554") by looking it up in the device list.
   */
  private suspend fun connectToDeviceByInstanceId(
    instanceId: String,
  ): String? {
    val listResult = callTool("device", mapOf("action" to "LIST"))
    if (listResult.isError) return "Error listing devices: ${listResult.content}"

    val entry = parseDeviceList(listResult.content)
      .firstOrNull { it.instanceId == instanceId }
      ?: return "Device '$instanceId' not found. Run 'trailblaze device list' to see available devices."

    return connectToDevice(entry.platform, instanceId)
  }

  private fun parseConnectedInstanceId(result: ToolResult): String? =
    parseConnectedInstanceId(result.content)

  private fun parseDevicePlatform(result: ToolResult): TrailblazeDevicePlatform? =
    parseDevicePlatform(result.content)

  private fun printDeviceSessionInfo(result: ToolResult) {
    val sessionInfo = parseEndedSessionInfo(result.content) ?: return
    Console.info("Ended previous session ($sessionInfo) to claim device.")
  }

  private suspend fun logSessionReuse(currentSpec: String?) {
    val sessionId = getTrailblazeSessionId()
    val on = currentSpec?.let { " on $it" } ?: ""
    if (sessionId != null) {
      Console.info("Reusing session $sessionId$on")
    } else if (currentSpec != null) {
      Console.info("Reusing existing session$on")
    }
  }

  /**
   * Fetches the Trailblaze session ID from the server via the session(INFO) tool.
   * Returns null if no active Trailblaze session exists yet.
   */
  suspend fun getTrailblazeSessionId(): String? {
    return try {
      val result = callTool("session", mapOf("action" to "INFO"))
      if (result.isError) return null
      val parsed = Json.parseToJsonElement(result.content).jsonObject
      parsed["sessionId"]?.jsonPrimitive?.content
    } catch (_: Exception) {
      null
    }
  }

  override fun close() {
    if (terminateSessionOnClose) {
      try {
        kotlinx.coroutines.runBlocking {
          sessionId?.let { activeSessionId ->
            if (hasConnectedDevice) {
              endTrailblazeSession(activeSessionId)
            }
            httpClient.delete(serverUrl) {
              applyCleanupTimeouts()
              appendMcpSessionHeaders(activeSessionId)
            }
          }
        }
      } catch (_: Exception) {
        // Best-effort cleanup only. The daemon will eventually evict stale sessions.
      }
    }
    httpClient.close()
  }

  private suspend fun endTrailblazeSession(activeSessionId: String) {
    val body = sessionStopRequestJson(requestId.incrementAndGet())

    httpClient.post(serverUrl) {
      contentType(ContentType.Application.Json)
      applyCleanupTimeouts()
      appendMcpSessionHeaders(activeSessionId)
      setBody(body)
    }
  }

  private fun HttpRequestBuilder.applyCleanupTimeouts() {
    timeout {
      requestTimeoutMillis = CLEANUP_TIMEOUT_MS
      connectTimeoutMillis = CONNECT_TIMEOUT_MS
      socketTimeoutMillis = CLEANUP_TIMEOUT_MS
    }
  }

  private fun HttpRequestBuilder.appendMcpSessionHeaders(activeSessionId: String) {
    headers {
      append(HttpHeaders.Accept, MCP_ACCEPT_HEADER_VALUE)
      append(MCP_SESSION_ID_HEADER, activeSessionId)
    }
  }

  data class ToolResult(
    val content: String,
    val isError: Boolean = false,
  ) {
    val isSuccess: Boolean get() = !isError
  }

  data class McpToolInfo(
    val name: String,
    val description: String,
    val inputSchema: JsonObject? = null,
  )

  /** A parsed entry from the MCP device LIST response. */
  data class DeviceListEntry(
    val instanceId: String,
    val platform: TrailblazeDevicePlatform,
    val description: String? = null,
  ) {
    val spec: String get() = "${platform.name.lowercase()}/$instanceId"
  }

  class CliMcpException(message: String, cause: Throwable? = null) : Exception(message, cause)

  companion object {
    const val PROTOCOL_VERSION = "2025-11-25"
    const val CLIENT_NAME = "TrailblazeCLI"
    const val CONNECT_TIMEOUT_MS = 10_000L
    const val DEFAULT_REQUEST_TIMEOUT_MS = 180_000L // 3 minutes for agent operations
    const val CLEANUP_TIMEOUT_MS = 5_000L
    const val MCP_ACCEPT_HEADER_VALUE = "application/json, text/event-stream"
    const val MCP_SESSION_ID_HEADER = "mcp-session-id"
    /** Header carrying a human-readable description of where the session came from. */
    const val ORIGIN_HEADER = "X-Trailblaze-Origin"

    /**
     * Captured at CLI entry by [captureOrigin]; every [CliMcpClient] created
     * after that picks it up automatically as its `origin`. Tests can leave
     * this null and pass `origin` explicitly per construction.
     */
    @Volatile
    var capturedOrigin: String? = null
      private set

    /**
     * Records the current CLI invocation's argv so any [CliMcpClient]
     * constructed downstream sends it as `X-Trailblaze-Origin`. Call once
     * from `TrailblazeCli.run()` / `executeForDaemon()` with the user-visible
     * argv (subcommand + args, NOT internal flags like `--describe-commands`).
     */
    fun captureOrigin(args: Array<String>) {
      capturedOrigin = args
        .joinToString(" ")
        .trim()
        .take(200)
        .takeIf { it.isNotEmpty() }
    }
    private const val SESSION_FILE_PREFIX = "trailblaze-cli-session"
    private const val JSON_RPC_VERSION = "2.0"
    private const val TOOLS_CALL_METHOD = "tools/call"
    private const val SESSION_TOOL_NAME = "session"
    private const val SESSION_ACTION_STOP = "STOP"
    private const val TMP_DIR_PROPERTY = "java.io.tmpdir"

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    /**
     * Returns a CLI state file under the temp directory. The optional [sessionScope]
     * disambiguates otherwise-shared state when one logical workflow needs its own
     * reusable MCP session, such as `blaze` keeping per-device history for `--save`.
     */
    internal fun scopedStateFile(prefix: String, port: Int, sessionScope: String? = null): File {
      val fileName = buildString {
        append(prefix)
        append("-")
        append(port)
        normalizedSessionScopeSuffix(sessionScope)?.let { suffix ->
          append("-")
          append(suffix)
        }
      }
      return File(System.getProperty(TMP_DIR_PROPERTY), fileName)
    }

    /** Returns the MCP session file path for a given daemon port and optional scope. */
    fun sessionFile(port: Int, sessionScope: String? = null): File {
      return scopedStateFile(prefix = SESSION_FILE_PREFIX, port = port, sessionScope = sessionScope)
    }

    private fun normalizedSessionScopeSuffix(sessionScope: String?): String? {
      return sessionScope
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?.replace(Regex("[^A-Za-z0-9._-]+"), "_")
    }

    private fun sessionStopRequestJson(requestId: Int): String {
      return json.encodeToString(
        JsonRpcToolCallRequest.serializer(),
        JsonRpcToolCallRequest(
          jsonrpc = JSON_RPC_VERSION,
          id = requestId,
          method = TOOLS_CALL_METHOD,
          params = ToolCallParams(
            name = SESSION_TOOL_NAME,
            arguments = SessionActionArgs(action = SESSION_ACTION_STOP),
          ),
        ),
      )
    }

    @Serializable
    private data class JsonRpcToolCallRequest(
      val jsonrpc: String,
      val id: Int,
      val method: String,
      val params: ToolCallParams,
    )

    @Serializable
    private data class ToolCallParams(
      val name: String,
      val arguments: SessionActionArgs,
    )

    @Serializable
    private data class SessionActionArgs(
      val action: String,
    )

    /**
     * Connects to the daemon for a **one-shot CLI command** (`ask`, `verify`,
     * `snapshot`, `tool`).
     *
     * Creates a fresh MCP session, never reads or writes the session file, and
     * tears the MCP session down on [close]. Each invocation is fully isolated
     * from any other concurrent CLI command, so parallel one-shots on different
     * devices do not share state.
     *
     * @throws CliMcpException if connection fails
     */
    suspend fun connectOneShot(
      port: Int = TrailblazeDevicePort.TRAILBLAZE_DEFAULT_HTTP_PORT,
      toolProfile: String = "MINIMAL",
    ): CliMcpClient {
      val client = CliMcpClient(
        serverUrl = "http://localhost:$port/mcp",
        toolProfile = toolProfile,
      )
      try {
        client.terminateSessionOnClose = true
        client.initialize()
        return client
      } catch (e: Exception) {
        client.close()
        throw CliMcpException(
          "Failed to connect to Trailblaze daemon on port $port: ${e.message}",
          e,
        )
      }
    }

    /**
     * Connects to the daemon for a **stateful/reusable CLI workflow**
     * (`blaze`, `blaze --save`, `session …`).
     *
     * The MCP session is persisted in `/tmp/trailblaze-cli-session-{port}[-scope]`
     * so that follow-up commands (in this terminal or another) can reattach to
     * the same daemon-side state — that's how `blaze --save` finds the steps
     * recorded by earlier `blaze` calls and how `session info/save/stop` reach
     * the session opened by `session start`.
     *
     * Flow:
     * 1. Read session file. If empty/missing, create a fresh session and persist it.
     * 2. If the saved session is for a different target app, create a fresh session.
     * 3. Validate the saved session via `device(action=INFO)`. If the daemon
     *    doesn't recognize it (restart) or the call fails, create a fresh session.
     * 4. Otherwise, reuse the saved session.
     *
     * @param port The daemon's HTTP port.
     * @param targetAppId The current target app ID. When this differs from the
     *   session's stored target, the session is invalidated because different
     *   targets may use different drivers and different custom tool sets.
     * @param sessionScope Optional suffix for the persisted session file. This
     *   is how the CLI keeps one reusable MCP session per logical workflow
     *   (e.g. `cli-android/emulator-5554` vs `cli-ios/SIM-X`) instead of forcing all terminals
     *   on a daemon port to share a single session.
     * @return A connected client. Check [hasExistingDevice] to see if a device
     *   is already connected to the reused session.
     * @throws CliMcpException if connection fails
     */
    suspend fun connectReusable(
      port: Int = TrailblazeDevicePort.TRAILBLAZE_DEFAULT_HTTP_PORT,
      toolProfile: String = "MINIMAL",
      targetAppId: String? = null,
      sessionScope: String? = null,
    ): CliMcpClient {
      val client = CliMcpClient(
        serverUrl = "http://localhost:$port/mcp",
        toolProfile = toolProfile,
      )

      // Treat blank as missing so callers can pass a config value without
      // worrying about empty strings sneaking past the target-change check.
      val effectiveTargetAppId = targetAppId?.ifBlank { null }
      val file = sessionFile(port, sessionScope)

      // Try to reuse existing session — file format: "sessionId\ntargetAppId"
      val (savedSessionId, savedTargetAppId) = readSessionFile(file)

      if (savedSessionId != null) {
        // Target app changed — different targets use different drivers and tool sets,
        // so the entire session must be recreated (not just a driver reconnect).
        if (effectiveTargetAppId != null && savedTargetAppId != null && effectiveTargetAppId != savedTargetAppId) {
          Console.info("Target app changed ($savedTargetAppId → $effectiveTargetAppId) — creating new session.")
        } else {
          client.sessionId = savedSessionId
          try {
            val result = client.callTool("device", mapOf("action" to "INFO"))
            val sessionIsAlive = !result.isError || result.content.contains("No device connected")
            if (sessionIsAlive) {
              // Session is alive — reuse it
              client.hasExistingDevice = !result.isError
              return client
            }
            // Daemon responded but doesn't recognize our session — it was restarted
            Console.info("Daemon doesn't recognize the saved session — starting a new one.")
          } catch (_: Exception) {
            // Daemon probe failed mid-call — recreate below
            Console.info("Daemon probe failed — starting a new session.")
          }
          client.sessionId = null
        }
      }

      // Create fresh session
      client.sessionId = null
      try {
        client.initialize()
      } catch (e: Exception) {
        client.close()
        throw CliMcpException(
          "Failed to connect to Trailblaze daemon on port $port: ${e.message}",
          e,
        )
      }

      // Save session ID + target app for reuse
      writeSessionFile(file, client.sessionId, effectiveTargetAppId)

      return client
    }

    /** Deletes the CLI session file, called on app stop or session end. */
    fun clearSession(port: Int, sessionScope: String? = null) {
      try {
        sessionFile(port, sessionScope).delete()
      } catch (_: Exception) {
        // Ignore
      }
    }

    /**
     * Reads the session file. Format: line 1 = session ID, line 2 = target app ID (optional).
     * Backwards-compatible: old files with only a session ID still work.
     */
    internal fun readSessionFile(file: File): Pair<String?, String?> {
      return try {
        if (!file.exists()) return null to null
        val lines = file.readLines()
        val sessionId = lines.getOrNull(0)?.trim()?.ifEmpty { null }
        val targetAppId = lines.getOrNull(1)?.trim()?.ifEmpty { null }
        sessionId to targetAppId
      } catch (_: Exception) {
        null to null
      }
    }

    internal fun writeSessionFile(file: File, sessionId: String?, targetAppId: String?) {
      try {
        val content = buildString {
          append(sessionId ?: "")
          if (targetAppId != null) {
            append("\n")
            append(targetAppId)
          }
        }
        file.writeText(content)
      } catch (_: Exception) {
        // Non-fatal — session just won't persist
      }
    }

    // -- Parsing helpers (internal for testing) ----------------------------------

    /**
     * Parses device entries from the MCP device LIST tool response.
     *
     * Expected line format: `"  - emulator-5554 (Android) - Google Pixel 6"`.
     * The trailing ` - <description>` segment is optional; the daemon emits it
     * for entries that have a known device name and omits it otherwise.
     */
    internal fun parseDeviceList(content: String): List<DeviceListEntry> {
      return content.lines()
        .map { it.trim().removePrefix("- ") }
        .filter { it.isNotBlank() }
        .mapNotNull { line ->
          val platform = when {
            line.contains("(Android)") -> TrailblazeDevicePlatform.ANDROID
            line.contains("(iOS)") -> TrailblazeDevicePlatform.IOS
            line.contains("(Web") -> TrailblazeDevicePlatform.WEB
            else -> return@mapNotNull null
          }
          val instanceId = line.substringBefore(" (").trim()
          if (instanceId.isEmpty()) return@mapNotNull null
          val description = line.substringAfter(") - ", missingDelimiterValue = "")
            .trim()
            .takeIf { it.isNotEmpty() }
          DeviceListEntry(instanceId, platform, description)
        }
    }

    /** Extracts the connected instance ID from device tool response text. */
    internal fun parseConnectedInstanceId(text: String): String? {
      // INFO format: "Instance ID: emulator-5554"
      Regex("Instance ID: (.+)").find(text)?.let {
        return it.groupValues[1].trim()
      }
      // Connect format: "Connected to emulator-5554 (Android)"
      Regex("Connected to (.+?) \\(").find(text)?.let {
        return it.groupValues[1].trim()
      }
      return null
    }

    /** Extracts the device platform from device tool response text. */
    internal fun parseDevicePlatform(text: String): TrailblazeDevicePlatform? {
      // INFO format: "Platform: Android"
      val platformName = (
        Regex("Platform: (.+)").find(text)
          // Connect format: "Connected to emulator-5554 (Android)"
          ?: Regex("\\(([^)]+)\\)").find(text)
        )?.groupValues?.get(1)?.trim() ?: return null
      return TrailblazeDevicePlatform.entries.find {
        it.displayName.equals(platformName, ignoreCase = true)
      }
    }

    /** Extracts the ended-session identifier from the device tool response, if present. */
    internal fun parseEndedSessionInfo(text: String): String? {
      return Regex("\\(ended previous session: (.+?)\\)").find(text)?.groupValues?.get(1)
    }
  }
}
