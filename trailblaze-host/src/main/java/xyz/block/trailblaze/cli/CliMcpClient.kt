package xyz.block.trailblaze.cli

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.headers
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
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

  /** Whether this client reconnected to a session that already has a device. */
  var hasExistingDevice: Boolean = false
    internal set

  val isInitialized: Boolean get() = sessionId != null

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
   *
   * @return null on success, or an error message on failure
   */
  suspend fun ensureDevice(deviceSpec: String? = null): String? {
    // When reusing a session that already has a device, skip the expensive driver reconnect.
    // The daemon's Maestro driver persists across CLI invocations within the same MCP session,
    // so force-reconnecting on every call just adds latency (~2-5s on iOS).
    if (hasExistingDevice) {
      if (deviceSpec == null) return null // same session, same device — ready to go

      // Explicit device spec — check if it matches what's already connected
      val infoResult = callTool("device", mapOf("action" to "INFO"))
      if (!infoResult.isError) {
        val currentInstanceId = parseConnectedInstanceId(infoResult)
        val currentPlatform = parseDevicePlatform(infoResult)
        if (currentPlatform != null) {
          val currentSpec = "${currentPlatform.name.lowercase()}${if (currentInstanceId != null) "/$currentInstanceId" else ""}"
          val platformName = currentPlatform.name.lowercase()
          val specMatchesFull = currentInstanceId != null && deviceSpec.equals(currentSpec, ignoreCase = true)
          val specMatchesPlatformOnly = deviceSpec.equals(platformName, ignoreCase = true)
          if (specMatchesFull || specMatchesPlatformOnly) return null // same device
        }
      }
      Console.info("Switching device — starting new session.")
    }

    if (deviceSpec != null) {
      // Support multiple formats:
      //   "android/emulator-5554"  → platform + instance
      //   "android"                → platform only
      //   "emulator-5554"          → instance ID only (resolve platform from device list)
      val parts = deviceSpec.split("/", limit = 2)
      val platform = TrailblazeDevicePlatform.fromString(parts[0])

      if (platform != null) {
        // First part is a known platform
        val instanceId = parts.getOrNull(1)
        return connectToDevice(platform, instanceId)
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
  ): String? {
    val listResult = callTool("device", mapOf("action" to "LIST"))
    if (!listResult.isError) {
      val platformDevices = parseDeviceList(listResult.content)
        .filter { it.platform == platform }

      if (instanceId == null && platformDevices.size > 1) {
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
            // Playwright targets (e.g. "playwright-chromium") aren't enumerated in
            // the device LIST the same way mobile devices are, so we skip ID
            // validation and let the daemon accept or reject the target.
          }
        }
      }
    }

    Console.info("Connecting to ${platform.displayName} device${if (instanceId != null) " ($instanceId)" else ""}...")
    if (platform == TrailblazeDevicePlatform.IOS) {
      Console.info("First connection may take 10–30s while the driver is set up on the device. The driver is cached and immediately available for subsequent connections.")
    }
    val args = mutableMapOf<String, Any?>("action" to platform.name, "force" to true)
    if (instanceId != null) args[DeviceManagerToolSet.PARAM_DEVICE_ID] = instanceId
    val result = callTool("device", args)
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
    }

    // Echo the full device spec so the agent knows what was connected
    val connectedId = parseConnectedInstanceId(result) ?: instanceId
    Console.info("Connected: ${platform.name.lowercase()}/${connectedId ?: "default"}")
    printDeviceSessionInfo(result)
    return null
  }

  /**
   * Resolves a bare instance ID (e.g., "emulator-5554") by looking it up in the device list.
   */
  private suspend fun connectToDeviceByInstanceId(instanceId: String): String? {
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
    httpClient.close()
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
  ) {
    val spec: String get() = "${platform.name.lowercase()}/$instanceId"
  }

  class CliMcpException(message: String, cause: Throwable? = null) : Exception(message, cause)

  companion object {
    const val PROTOCOL_VERSION = "2025-11-25"
    const val CLIENT_NAME = "TrailblazeCLI"
    const val CONNECT_TIMEOUT_MS = 10_000L
    const val DEFAULT_REQUEST_TIMEOUT_MS = 180_000L // 3 minutes for agent operations

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    /** Returns the session file path for a given daemon port. */
    fun sessionFile(port: Int): File =
      File(System.getProperty("java.io.tmpdir"), "trailblaze-cli-session-$port")

    /**
     * Connects to the daemon, reusing the existing CLI session if available.
     *
     * One CLI session is shared across all terminals for a given daemon port.
     * The session file (`/tmp/trailblaze-cli-session-{port}`) stores both the
     * session ID and the target app ID that the session was created for.
     *
     * Flow:
     * 1. If session file exists, try to reuse that session
     * 2. Validate with a lightweight call (device INFO)
     * 3. If the target app changed (different custom driver/tools), create a fresh session
     * 4. If stale (daemon restarted), create a fresh session
     * 5. If no session file, create a fresh session
     *
     * @param port The daemon's HTTP port
     * @param targetAppId The current target app ID from CLI config. When this differs from the
     *   session's stored target, the session is invalidated because different targets may use
     *   different drivers and different custom tool sets.
     * @return A connected client. Check [hasExistingDevice] to see if a device is already connected.
     * @throws CliMcpException if connection fails
     */
    suspend fun connectToDaemon(
      port: Int = TrailblazeDevicePort.TRAILBLAZE_DEFAULT_HTTP_PORT,
      toolProfile: String = "MINIMAL",
      targetAppId: String? = null,
    ): CliMcpClient {
      val client = CliMcpClient(serverUrl = "http://localhost:$port/mcp", toolProfile = toolProfile)
      val file = sessionFile(port)

      // Try to reuse existing session — file format: "sessionId\ntargetAppId"
      val (savedSessionId, savedTargetAppId) = readSessionFile(file)

      if (savedSessionId != null) {
        // Target app changed — different targets use different drivers and tool sets,
        // so the entire session must be recreated (not just a driver reconnect).
        if (targetAppId != null && savedTargetAppId != null && targetAppId != savedTargetAppId) {
          Console.info("Target app changed ($savedTargetAppId → $targetAppId) — creating new session.")
        } else {
          client.sessionId = savedSessionId
          try {
            val result = client.callTool("device", mapOf("action" to "INFO"))
            if (!result.isError || result.content.contains("No device connected")) {
              // Session is alive — reuse it
              client.hasExistingDevice = !result.isError
              return client
            }
            // Daemon responded but doesn't recognize our session — it was restarted
            Console.info("Creating new session.")
          } catch (_: Exception) {
            // Daemon unreachable — will be recreated below
            Console.info("Creating new session.")
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
      writeSessionFile(file, client.sessionId, targetAppId)

      return client
    }

    /** Deletes the CLI session file, called on app stop or session end. */
    fun clearSession(port: Int) {
      try {
        sessionFile(port).delete()
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
     * Expected line format: `"  - emulator-5554 (Android) - Google Pixel 6"`
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
          DeviceListEntry(instanceId, platform)
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
