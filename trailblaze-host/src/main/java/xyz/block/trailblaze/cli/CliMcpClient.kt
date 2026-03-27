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
        // Use minimal tool profile for CLI sessions to reduce overhead
        if (sessionId == null) {
          append("X-Tool-Profile", "MINIMAL")
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
   * Ensures a device is connected for this session.
   *
   * If [devicePlatform] is specified, connects to that platform (ANDROID, IOS, WEB).
   * Otherwise, lists available devices and auto-connects if exactly one mobile device
   * is found. Returns an error message if no devices or multiple devices are available.
   *
   * @return null on success, or an error message on failure
   */
  suspend fun ensureDevice(devicePlatform: String? = null): String? {
    // If reusing an existing session that already has a device, skip unless
    // the user explicitly asked for a different platform.
    if (hasExistingDevice && devicePlatform == null) return null

    // CLI always force-claims — if another session holds the device, take it over
    // and terminate that session cleanly.
    if (devicePlatform != null) {
      val platform = TrailblazeDevicePlatform.fromString(devicePlatform)
        ?: return "Unknown platform: $devicePlatform. Use ${TrailblazeDevicePlatform.entries.joinToString { it.name }}"
      return connectToDevice(platform)
    }

    // No explicit device — check what's available and auto-select
    val listResult = callTool("device", mapOf("action" to "LIST"))
    if (listResult.isError) return "Error listing devices: ${listResult.content}"

    // Parse the device list to find mobile devices (exclude web — always available)
    val lines = listResult.content.lines().filter { it.trimStart().startsWith("- ") }
    val mobileDevices = lines.filter { line ->
      val lower = line.lowercase()
      lower.contains("android") || lower.contains("ios")
    }

    return when {
      mobileDevices.size == 1 -> {
        // Auto-connect to the only mobile device
        val platform =
          if (mobileDevices[0].lowercase().contains("android")) TrailblazeDevicePlatform.ANDROID
          else TrailblazeDevicePlatform.IOS
        connectToDevice(platform)
      }
      mobileDevices.isEmpty() -> {
        "No mobile devices found. Connect an Android device/emulator or start an iOS simulator.\n" +
          "Or specify a platform: -d ANDROID, -d IOS, or -d WEB"
      }
      else -> {
        "Multiple devices found. Specify which to use with -d:\n${listResult.content}"
      }
    }
  }

  private suspend fun connectToDevice(platform: TrailblazeDevicePlatform): String? {
    Console.info("Connecting to ${platform.displayName} device...")
    if (platform == TrailblazeDevicePlatform.IOS) {
      Console.info("First connection may take a moment while the driver is set up on the device.")
    }
    val result = callTool("device", mapOf("action" to platform.name, "force" to true))
    if (result.isError) return "Error connecting to device: ${result.content}"
    printDeviceSessionInfo(result)
    return null
  }

  private fun printDeviceSessionInfo(result: ToolResult) {
    // If the server response mentions ending a previous session, surface it to the user
    if (result.content.contains("ended previous session")) {
      val match = Regex("\\(ended previous session: (.+?)\\)").find(result.content)
      val sessionInfo = match?.groupValues?.get(1) ?: "unknown"
      Console.info("Ended previous session ($sessionInfo) to claim device.")
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
     * The session ID is persisted in `/tmp/trailblaze-cli-session-{port}`.
     *
     * Flow:
     * 1. If session file exists, try to reuse that session
     * 2. Validate with a lightweight call (device INFO)
     * 3. If stale (daemon restarted), create a fresh session
     * 4. If no session file, create a fresh session
     *
     * @param port The daemon's HTTP port
     * @return A connected client. Check [hasExistingDevice] to see if a device is already connected.
     * @throws CliMcpException if connection fails
     */
    suspend fun connectToDaemon(port: Int = TrailblazeDevicePort.TRAILBLAZE_DEFAULT_HTTP_PORT): CliMcpClient {
      val client = CliMcpClient(serverUrl = "http://localhost:$port/mcp")
      val file = sessionFile(port)

      // Try to reuse existing session
      val savedSessionId = try {
        if (file.exists()) file.readText().trim().ifEmpty { null } else null
      } catch (_: Exception) {
        null
      }

      if (savedSessionId != null) {
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

      // Save session ID for reuse
      try {
        file.writeText(client.sessionId ?: "")
      } catch (_: Exception) {
        // Non-fatal — session just won't persist
      }

      Console.info("New session: ${client.sessionId}")

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
  }
}
