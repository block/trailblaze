package xyz.block.trailblaze.mcp.integration

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.headers
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.modelcontextprotocol.kotlin.sdk.types.ListToolsResult
import io.modelcontextprotocol.kotlin.sdk.types.McpJson
import io.modelcontextprotocol.kotlin.sdk.types.Tool
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

/**
 * A Kotlin MCP client for integration testing.
 *
 * This client connects to Trailblaze's MCP server and provides a clean API
 * for testing MCP tools. It handles:
 * - Session management (initialize, session ID tracking)
 * - JSON-RPC protocol details
 * - SSE response parsing
 *
 * ## MCP Specification References
 *
 * When updating this client for newer MCP protocol versions, refer to:
 *
 * - **MCP Specification**: https://modelcontextprotocol.io/specification/2025-11-25
 * - **Changelog**: https://modelcontextprotocol.io/specification/2025-11-25/changelog
 * - **Lifecycle/Versioning**: https://modelcontextprotocol.io/specification/2025-11-25/basic/lifecycle
 * - **Tools**: https://modelcontextprotocol.io/specification/2025-11-25/server/tools
 * - **Streamable HTTP Transport**: https://modelcontextprotocol.io/specification/2025-11-25/basic/transports
 * - **MCP Kotlin SDK**: https://github.com/modelcontextprotocol/kotlin-sdk (v0.8.3 supports 2025-11-25)
 *
 * Current protocol version: `2025-11-25`
 *
 * ## Usage
 *
 * ```kotlin
 * val client = McpTestClient("http://localhost:52525/mcp")
 * client.initialize()
 * val tools = client.listTools()
 * val result = client.callTool("getSessionConfig", emptyMap())
 * client.close()
 * ```
 *
 * @see <a href="https://modelcontextprotocol.io/specification/2025-11-25">MCP Specification</a>
 * @see <a href="https://github.com/modelcontextprotocol/kotlin-sdk">MCP Kotlin SDK</a>
 */
class McpTestClient(
  private val serverUrl: String = DEFAULT_MCP_URL,
  private val clientName: String = "McpTestClient",
  private val clientVersion: String = "1.0.0",
  private val requestTimeoutMs: Long = 180_000L, // 3 minutes for agent operations
) : AutoCloseable {

  companion object {
    const val DEFAULT_MCP_URL = "http://localhost:52525/mcp"

    /**
     * MCP protocol version used by this client.
     *
     * This should match the server's protocol version. The MCP Kotlin SDK 0.8.3
     * supports the 2025-11-25 spec (SDK released December 4, 2025).
     *
     * Version history:
     * - 2024-11-05: Original MCP spec
     * - 2025-03-26: Streamable HTTP transport
     * - 2025-11-25: Latest spec with async tasks, OAuth improvements (current)
     *
     * See: https://modelcontextprotocol.io/specification/2025-11-25/basic/lifecycle
     * Changelog: https://modelcontextprotocol.io/specification/2025-11-25/changelog
     */
    const val PROTOCOL_VERSION = "2025-11-25"

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
  }

  private val httpClient = HttpClient(CIO) {
    install(HttpTimeout) {
      requestTimeoutMillis = requestTimeoutMs
      connectTimeoutMillis = 10_000
      socketTimeoutMillis = requestTimeoutMs
    }
  }

  private var sessionId: String? = null
  private var requestId = 0

  /** Server info returned after initialization */
  var serverInfo: JsonObject? = null
    private set

  /** Whether the client has successfully initialized a session */
  val isInitialized: Boolean get() = sessionId != null

  /**
   * Initializes an MCP session with the server.
   *
   * Implements the MCP initialization handshake as specified in:
   * https://modelcontextprotocol.io/specification/2025-11-25/basic/lifecycle#initialization
   *
   * @throws McpClientException if initialization fails
   * @see <a href="https://modelcontextprotocol.io/specification/2025-11-25/basic/lifecycle">Lifecycle</a>
   */
  suspend fun initialize() {
    val response = sendRequest(
      method = "initialize",
      params = buildJsonObject {
        // Protocol version - update when MCP spec changes
        // See: https://spec.modelcontextprotocol.io/specification/basic/lifecycle/#versioning
        put("protocolVersion", PROTOCOL_VERSION)
        putJsonObject("capabilities") {}
        putJsonObject("clientInfo") {
          put("name", clientName)
          put("version", clientVersion)
        }
      },
    )

    val result = response["result"]?.jsonObject
      ?: throw McpClientException("Initialize failed: no result in response")

    serverInfo = result["serverInfo"]?.jsonObject
  }

  /**
   * Lists all available tools from the server.
   *
   * Implements `tools/list` as specified in:
   * https://modelcontextprotocol.io/specification/2025-11-25/server/tools#listing-tools
   *
   * @return List of tool names
   */
  suspend fun listTools(): List<String> {
    val response = sendRequest("tools/list", buildJsonObject {})
    val tools = response["result"]?.jsonObject?.get("tools")?.jsonArray
      ?: return emptyList()
    return tools.mapNotNull { it.jsonObject["name"]?.jsonPrimitive?.content }
  }

  /**
   * Lists all available tools with their full descriptors.
   *
   * Uses the MCP SDK's [Tool] type for type-safe tool representation.
   *
   * @return List of tools from the MCP SDK
   */
  suspend fun listToolDescriptors(): List<Tool> {
    val response = sendRequest("tools/list", buildJsonObject {})
    val result = response["result"]?.jsonObject ?: return emptyList()
    val listToolsResult = McpJson.decodeFromJsonElement(ListToolsResult.serializer(), result)
    return listToolsResult.tools
  }

  /**
   * Calls a tool by name with the given arguments.
   *
   * @param name Tool name
   * @param arguments Map of argument name to value
   * @return Tool result as a string
   * @throws McpClientException if tool execution fails
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
   * Helper to recursively add any value to a JsonObjectBuilder.
   */
  private fun JsonObjectBuilder.putAny(key: String, value: Any?) {
    when (value) {
      null -> {} // Skip null values
      is String -> put(key, value)
      is Number -> put(key, value)
      is Boolean -> put(key, value)
      is Map<*, *> -> put(
        key,
        buildJsonObject {
          @Suppress("UNCHECKED_CAST")
          (value as Map<String, Any?>).forEach { (k, v) ->
            putAny(k, v)
          }
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
                  (item as Map<String, Any?>).forEach { (k, v) ->
                    putAny(k, v)
                  }
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
   * Calls a tool by name with JSON arguments.
   *
   * Implements `tools/call` as specified in:
   * https://modelcontextprotocol.io/specification/2025-11-25/server/tools#calling-tools
   *
   * @param name Tool name
   * @param arguments JsonObject of arguments
   * @return Tool result
   */
  suspend fun callTool(name: String, arguments: JsonObject): ToolResult {
    val response = sendRequest(
      method = "tools/call",
      params = buildJsonObject {
        put("name", name)
        put("arguments", arguments)
      },
    )

    // Check for JSON-RPC error
    response["error"]?.jsonObject?.let { error ->
      val errorMessage = error["message"]?.jsonPrimitive?.content ?: "Unknown error"
      return ToolResult(
        content = errorMessage,
        isError = true,
      )
    }

    val result = response["result"]?.jsonObject
    val content = result?.get("content")?.jsonArray?.firstOrNull()?.jsonObject
    val text = content?.get("text")?.jsonPrimitive?.content ?: response.toString()
    val isError = result?.get("isError")?.jsonPrimitive?.content?.toBoolean() ?: false

    return ToolResult(
      content = text,
      isError = isError,
    )
  }

  /**
   * Sends a raw JSON-RPC request to the server.
   *
   * @param method The JSON-RPC method name
   * @param params The parameters object
   * @return The JSON response
   */
  suspend fun sendRequest(method: String, params: JsonObject): JsonObject {
    val id = ++requestId
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
      }
      setBody(body.toString())
    }

    // Capture session ID from response headers
    response.headers["mcp-session-id"]?.let { sessionId = it }

    val jsonText = parseSSEResponse(response.bodyAsText())
    return try {
      json.decodeFromString<JsonObject>(jsonText)
    } catch (e: Exception) {
      buildJsonObject { put("error", e.message ?: "Parse error") }
    }
  }

  /**
   * Parses SSE response format into JSON.
   * Handles both direct JSON and SSE "data:" prefixed formats.
   *
   * See Streamable HTTP transport spec:
   * https://modelcontextprotocol.io/specification/2025-11-25/basic/transports#streamable-http
   */
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

  override fun close() {
    httpClient.close()
  }

  /**
   * Result of a tool call.
   */
  data class ToolResult(
    val content: String,
    val isError: Boolean = false,
  ) {
    val isSuccess: Boolean get() = !isError
  }

  /**
   * Exception thrown when MCP client operations fail.
   */
  class McpClientException(message: String, cause: Throwable? = null) : Exception(message, cause)
}
