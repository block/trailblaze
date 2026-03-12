package xyz.block.trailblaze.mcp.executor

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
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import xyz.block.trailblaze.devices.TrailblazeDevicePort
import xyz.block.trailblaze.toolcalls.TrailblazeToolDescriptor
import xyz.block.trailblaze.toolcalls.TrailblazeToolParameterDescriptor
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicInteger

/**
 * Executes tools via MCP protocol over HTTP (Streamable HTTP transport).
 *
 * This is the "self-connection" path - the agent connects to Trailblaze's own MCP server
 * via HTTP and executes tools through the full MCP protocol stack.
 *
 * This provides architectural symmetry (internal agent uses same path as external clients)
 * at the cost of HTTP overhead. Use [DirectMcpToolExecutor] for production performance.
 *
 * @param serverUrl MCP server URL (default: http://localhost:52525/mcp)
 * @param clientName Client identifier for MCP session
 * @param requestTimeoutMs Timeout for tool execution (default: 3 minutes for long operations)
 */
class HttpMcpToolExecutor(
  private val serverUrl: String = DEFAULT_MCP_URL,
  private val clientName: String = "trailblaze-agent",
  private val requestTimeoutMs: Long = 180_000L,
) : McpToolExecutor, AutoCloseable {

  companion object {
    const val DEFAULT_MCP_URL = TrailblazeDevicePort.DEFAULT_MCP_URL
    private val json = Json {
      ignoreUnknownKeys = true
      prettyPrint = false
    }
  }

  private val httpClient = HttpClient(OkHttp) {
    install(HttpTimeout) {
      requestTimeoutMillis = requestTimeoutMs
      connectTimeoutMillis = 10_000
      socketTimeoutMillis = requestTimeoutMs
    }
  }

  @Volatile private var sessionId: String? = null
  private val requestId = AtomicInteger(0)
  @Volatile private var cachedTools: List<TrailblazeToolDescriptor>? = null
  private val initMutex = Mutex()
  @Volatile private var initialized = false

  /**
   * Initializes the MCP session. Called lazily on first tool execution.
   * Uses a Mutex to ensure only one coroutine initializes at a time,
   * and other callers wait for the result rather than returning early.
   */
  private suspend fun ensureInitialized() {
    if (initialized) return
    initMutex.withLock {
      if (initialized) return // Double-check after acquiring lock

      val response = sendRequest(
        method = "initialize",
        params = buildJsonObject {
          put("protocolVersion", "2025-11-25")
          putJsonObject("capabilities") {}
          putJsonObject("clientInfo") {
            put("name", clientName)
            put("version", "1.0.0")
          }
        },
      )

      // Check for error
      if (response["error"] != null) {
        throw IllegalStateException("Failed to initialize MCP session: ${response["error"]}")
      }

      // Also notify server we're initialized (required by MCP spec)
      sendNotification(
        method = "notifications/initialized",
        params = buildJsonObject {},
      )

      initialized = true
    }
  }

  override suspend fun executeToolByName(
    toolName: String,
    args: JsonObject,
  ): ToolExecutionResult {
    try {
      ensureInitialized()

      val response = sendRequest(
        method = "tools/call",
        params = buildJsonObject {
          put("name", toolName)
          put("arguments", args)
        },
      )

      // Check for JSON-RPC error
      response["error"]?.jsonObject?.let { error ->
        val errorMessage = error["message"]?.jsonPrimitive?.content ?: "Unknown MCP error"
        val errorCode = error["code"]?.jsonPrimitive?.content
        return if (errorCode == "-32602" && errorMessage.contains("not found", ignoreCase = true)) {
          ToolExecutionResult.ToolNotFound(
            requestedTool = toolName,
            availableTools = getAvailableToolNames().toList(),
          )
        } else {
          ToolExecutionResult.Failure(
            error = "MCP error: $errorMessage",
            toolName = toolName,
          )
        }
      }

      // Extract result
      val result = response["result"]?.jsonObject
      val content = result?.get("content")?.jsonArray?.firstOrNull()?.jsonObject
      val text = content?.get("text")?.jsonPrimitive?.content ?: response.toString()
      val isError = result?.get("isError")?.jsonPrimitive?.content?.toBoolean() ?: false

      return if (isError) {
        ToolExecutionResult.Failure(
          error = text,
          toolName = toolName,
        )
      } else {
        ToolExecutionResult.Success(
          output = text,
          toolName = toolName,
        )
      }
    } catch (e: Exception) {
      return ToolExecutionResult.Failure(
        error = "HTTP MCP execution failed: ${e.message}",
        toolName = toolName,
      )
    }
  }

  override fun getAvailableTools(): List<TrailblazeToolDescriptor> {
    // Return cached tools or empty list (tools are fetched lazily)
    return cachedTools ?: emptyList()
  }

  /**
   * Fetches available tools from the MCP server.
   * This should be called after initialization to populate the tool cache.
   */
  suspend fun fetchAvailableTools(): List<TrailblazeToolDescriptor> {
    ensureInitialized()

    val response = sendRequest("tools/list", buildJsonObject {})
    val tools = response["result"]?.jsonObject?.get("tools")?.jsonArray
      ?: return emptyList()

    cachedTools = tools.mapNotNull { tool ->
      val obj = tool.jsonObject
      val name = obj["name"]?.jsonPrimitive?.content ?: return@mapNotNull null
      val description = obj["description"]?.jsonPrimitive?.content ?: ""
      val inputSchema = obj["inputSchema"]?.jsonObject

      // Parse parameters from JSON schema
      val properties = inputSchema?.get("properties")?.jsonObject ?: buildJsonObject {}
      val required = inputSchema?.get("required")?.jsonArray
        ?.mapNotNull { it.jsonPrimitive.content }
        ?.toSet()
        ?: emptySet()

      val requiredParams = mutableListOf<TrailblazeToolParameterDescriptor>()
      val optionalParams = mutableListOf<TrailblazeToolParameterDescriptor>()

      properties.forEach { (paramName, schema) ->
        val schemaObj = schema.jsonObject
        val param = TrailblazeToolParameterDescriptor(
          name = paramName,
          type = schemaObj["type"]?.jsonPrimitive?.content ?: "string",
          description = schemaObj["description"]?.jsonPrimitive?.content ?: "",
        )
        if (paramName in required) {
          requiredParams.add(param)
        } else {
          optionalParams.add(param)
        }
      }

      TrailblazeToolDescriptor(
        name = name,
        description = description,
        requiredParameters = requiredParams,
        optionalParameters = optionalParams,
      )
    }

    return cachedTools!!
  }

  /**
   * Sends a JSON-RPC notification to the MCP server (no `id` field per JSON-RPC 2.0 spec).
   */
  private suspend fun sendNotification(method: String, params: JsonObject) {
    val body = buildJsonObject {
      put("jsonrpc", "2.0")
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
  }

  /**
   * Sends a JSON-RPC request to the MCP server.
   */
  private suspend fun sendRequest(method: String, params: JsonObject): JsonObject {
    val id = requestId.getAndIncrement()
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
}
