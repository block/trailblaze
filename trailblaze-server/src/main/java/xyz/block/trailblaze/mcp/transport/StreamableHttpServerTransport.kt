package xyz.block.trailblaze.mcp.transport

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.contentType
import io.ktor.server.request.header
import io.ktor.server.request.receiveText
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.response.respondTextWriter
import io.modelcontextprotocol.kotlin.sdk.shared.AbstractTransport
import io.modelcontextprotocol.kotlin.sdk.shared.TransportSendOptions
import io.modelcontextprotocol.kotlin.sdk.types.JSONRPCMessage
import io.modelcontextprotocol.kotlin.sdk.types.JSONRPCNotification
import io.modelcontextprotocol.kotlin.sdk.types.JSONRPCRequest
import io.modelcontextprotocol.kotlin.sdk.types.JSONRPCResponse
import io.modelcontextprotocol.kotlin.sdk.types.McpJson
import io.modelcontextprotocol.kotlin.sdk.types.RequestId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import xyz.block.trailblaze.logs.client.TrailblazeJsonInstance
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Header name for MCP session ID in Streamable HTTP transport.
 */
const val MCP_SESSION_ID_HEADER = "Mcp-Session-Id"

/**
 * Server transport for Streamable HTTP: handles JSON-RPC messages over HTTP POST requests
 * with responses returned in the same HTTP connection.
 *
 * This transport replaces SSE with a simpler HTTP-based approach where:
 * - Client sends JSON-RPC request via HTTP POST
 * - Server processes the request and returns response in HTTP response body
 * - For long-running operations, responses can be streamed using chunked transfer encoding
 * - Session management is done via the Mcp-Session-Id header
 *
 * @see <a href="https://modelcontextprotocol.io/specification/2025-03-26/basic/transports#streamable-http">MCP Streamable HTTP Spec</a>
 */
@OptIn(ExperimentalAtomicApi::class)
class StreamableHttpServerTransport : AbstractTransport() {

  @OptIn(ExperimentalUuidApi::class)
  private val internalSessionId: String = Uuid.random().toString()

  /**
   * The session ID used for this transport. Can be overridden to use a client-provided ID.
   */
  var sessionId: String = internalSessionId
    private set

  /**
   * Sets the session ID to use for this transport.
   * Call this before handling requests if you want to use a client-provided session ID.
   */
  fun useSessionId(id: String) {
    sessionId = id
  }

  private val initialized: AtomicBoolean = AtomicBoolean(false)

  // Mutex for thread-safe operations
  private val sendMutex = Mutex()

  // Channel to hold pending responses for the current request
  private val pendingResponses = ConcurrentHashMap<String, Channel<JSONRPCMessage>>()

  // Shared flow for notifications that need to be sent to client
  private val notificationFlow = MutableSharedFlow<JSONRPCMessage>(extraBufferCapacity = 100)

  // Signal when the message handler is ready (set by Server.connect())
  private val messageHandlerReady = CompletableDeferred<Unit>()

  // Keep the transport alive until explicitly closed
  // Server.connect() will wait on this before calling _onClose
  private val transportClosed = CompletableDeferred<Unit>()

  /**
   * Wait for the transport to be ready to handle messages.
   * This should be called after Server.connect() is initiated to ensure handlers are set up.
   */
  suspend fun waitForReady(timeoutMs: Long = 5000): Boolean {
    return try {
      withTimeoutOrNull(timeoutMs) {
        messageHandlerReady.await()
      } != null
    } catch (e: Exception) {
      false
    }
  }

  override suspend fun start() {
    if (!initialized.compareAndSet(expectedValue = false, newValue = true)) {
      error("StreamableHttpServerTransport already started!")
    }

    // The _onMessage callback will be set by Server.connect() after start() returns
    // We use a delay to allow connect() time to wire up handlers
    CoroutineScope(Dispatchers.Default).launch {
      delay(200) // Allow time for connect() to wire up handlers
      if (!messageHandlerReady.isCompleted) {
        messageHandlerReady.complete(Unit)
      }
    }
  }

  /**
   * Handles an incoming HTTP POST request with JSON-RPC message(s).
   *
   * This method processes the request, invokes the message handler, and returns
   * the response(s) in the HTTP response body. Supports both single messages
   * and batched JSON-RPC requests.
   *
   * @param call The Ktor ApplicationCall representing the HTTP request/response
   * @return true if this was a new session initialization, false otherwise
   */
  suspend fun handleRequest(call: ApplicationCall): Boolean {
    // Wait for message handler to be ready (set by Server.connect())
    if (!messageHandlerReady.isCompleted) {
      val isReady = waitForReady(2000)
      if (!isReady) {
        println("Server not ready to handle requests. Please retry.")
        call.respondText(
          "Server not ready to handle requests. Please retry.",
          status = HttpStatusCode.ServiceUnavailable,
        )
        return false
      }
    }

    // Check content type
    val contentType = call.request.contentType()
    if (contentType != ContentType.Application.Json) {
      call.respondText(
        "Unsupported content-type: $contentType. Expected application/json",
        status = HttpStatusCode.UnsupportedMediaType,
      )
      println("Unsupported content-type: $contentType")
      return false
    }

    // Validate or assign session ID
    val clientSessionId = call.request.header(MCP_SESSION_ID_HEADER)
    val isNewSession = clientSessionId == null

    if (clientSessionId != null && clientSessionId != sessionId) {
      call.respondText(
        "Invalid session ID",
        status = HttpStatusCode.NotFound,
      )
      println("Invalid session ID")
      return false
    }

    // Always include session ID in response
    call.response.header(MCP_SESSION_ID_HEADER, sessionId)

    val body = try {
      call.receiveText()
    } catch (e: Exception) {
      println("Failed to read request body")
      call.respondText(
        "Failed to read request body: ${e.message}",
        status = HttpStatusCode.BadRequest,
      )
      _onError.invoke(e)
      return isNewSession
    }

    if (body.isBlank()) {
      call.respondText(
        "Empty request body",
        status = HttpStatusCode.BadRequest,
      )
      println("Empty request body")
      return isNewSession
    }

    try {
      // Parse as JSON to determine if it's a single message or batch
      val jsonElement = TrailblazeJsonInstance.parseToJsonElement(body)

      when (jsonElement) {
        is JsonArray -> {
          // Batch request
          handleBatchRequest(call, jsonElement)
        }
        is JsonObject -> {
          // Single request
          handleSingleRequest(call, body, jsonElement)
        }
        else -> {
          call.respondText(
            "Invalid JSON-RPC message format",
            status = HttpStatusCode.BadRequest,
          )
        }
      }
    } catch (e: Exception) {
      print("Error processing message: ${e.message}")
      call.respondText(
        "Error processing message: ${e.message}",
        status = HttpStatusCode.BadRequest,
      )
      _onError.invoke(e)
    }

    return isNewSession
  }

  private suspend fun handleSingleRequest(
    call: ApplicationCall,
    rawBody: String,
    jsonObject: JsonObject,
  ) {
    // Determine message type
    val hasMethod = jsonObject.containsKey("method")
    val hasId = jsonObject.containsKey("id")

    when {
      hasMethod && hasId -> {
        // It's a request - we need to wait for a response
        val requestId = jsonObject["id"]?.jsonPrimitive?.content ?: "unknown"

        val responseChannel = Channel<JSONRPCMessage>(1)
        pendingResponses[requestId] = responseChannel

        try {
          // Parse and invoke the message handler
          val message = McpJson.decodeFromString<JSONRPCMessage>(rawBody)
          _onMessage.invoke(message)

          // Wait for response with timeout
          val response = withTimeoutOrNull(30_000L) {
            responseChannel.receive()
          }

          if (response != null) {
            val responseJson = McpJson.encodeToString(response)
            call.response.header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            call.respondText(
              responseJson,
              contentType = ContentType.Application.Json,
              status = HttpStatusCode.OK,
            )
          } else {
            call.respondText(
              """{"jsonrpc":"2.0","id":"$requestId","error":{"code":-32603,"message":"Request timeout"}}""",
              contentType = ContentType.Application.Json,
              status = HttpStatusCode.RequestTimeout,
            )
          }
        } finally {
          pendingResponses.remove(requestId)
          responseChannel.close()
        }
      }

      hasMethod && !hasId -> {
        // It's a notification - no response expected
        val message = McpJson.decodeFromString<JSONRPCMessage>(rawBody)
        _onMessage.invoke(message)
        call.respond(HttpStatusCode.Accepted)
      }

      else -> {
        call.respondText(
          "Invalid JSON-RPC message: missing required fields",
          status = HttpStatusCode.BadRequest,
        )
      }
    }
  }

  private suspend fun handleBatchRequest(call: ApplicationCall, jsonArray: JsonArray) {
    val responses = mutableListOf<JSONRPCMessage>()
    val channels = mutableMapOf<String, Channel<JSONRPCMessage>>()

    try {
      // Process each message in the batch
      for (element in jsonArray) {
        val jsonObject = element.jsonObject
        val rawMessage = McpJson.encodeToString<JsonElement>(element)

        val hasMethod = jsonObject.containsKey("method")
        val hasId = jsonObject.containsKey("id")

        if (hasMethod && hasId) {
          // Request - set up channel for response
          val requestId = jsonObject["id"]?.jsonPrimitive?.content ?: "unknown"
          val responseChannel = Channel<JSONRPCMessage>(1)
          channels[requestId] = responseChannel
          pendingResponses[requestId] = responseChannel

          val message = McpJson.decodeFromString<JSONRPCMessage>(rawMessage)
          _onMessage.invoke(message)
        } else if (hasMethod) {
          // Notification - just invoke
          val message = McpJson.decodeFromString<JSONRPCMessage>(rawMessage)
          _onMessage.invoke(message)
        }
      }

      // Collect responses with timeout
      for ((requestId, channel) in channels) {
        val response = withTimeoutOrNull(30_000L) {
          channel.receive()
        }
        if (response != null) {
          responses.add(response)
        }
      }

      if (responses.isNotEmpty()) {
        call.response.header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
        val responseArray = responses.map { McpJson.encodeToString(it) }
        call.respondText(
          "[${responseArray.joinToString(",")}]",
          contentType = ContentType.Application.Json,
          status = HttpStatusCode.OK,
        )
      } else {
        call.respond(HttpStatusCode.Accepted)
      }
    } finally {
      channels.forEach { (id, channel) ->
        pendingResponses.remove(id)
        channel.close()
      }
    }
  }

  /**
   * Handles GET requests for server-to-client streaming (optional).
   * This can be used for long-polling or streaming notifications.
   */
  suspend fun handleStreamRequest(call: ApplicationCall) {
    val clientSessionId = call.request.header(MCP_SESSION_ID_HEADER)
    if (clientSessionId != sessionId) {
      call.respondText(
        "Invalid or missing session ID",
        status = HttpStatusCode.NotFound,
      )
      return
    }

    call.response.header(MCP_SESSION_ID_HEADER, sessionId)

    // Stream notifications using Server-Sent Events format within HTTP response
    call.respondTextWriter(contentType = ContentType.Text.EventStream) {
      notificationFlow.collect { message ->
        write("data: ${McpJson.encodeToString(message)}\n\n")
        flush()
      }
    }
  }

  /**
   * Extracts the string representation of a RequestId for matching with pending requests.
   * The JSON-RPC spec allows IDs to be strings or numbers, so we normalize to string.
   */
  private fun extractRequestIdString(id: RequestId): String {
    return when (id) {
      is RequestId.StringId -> id.value
      is RequestId.NumberId -> id.value.toString()
    }
  }

  override suspend fun send(message: JSONRPCMessage, options: TransportSendOptions?) {
    if (!initialized.load()) {
      error("Transport not started")
    }

    sendMutex.withLock {
      when (message) {
        is JSONRPCResponse -> {
          // Find the pending request channel and send the response
          val requestId = extractRequestIdString(message.id)
          val channel = pendingResponses[requestId]

          if (channel != null) {
            channel.send(message)
          } else {
            // Could be a notification response or late response - queue for streaming
            notificationFlow.emit(message)
          }
        }
        is JSONRPCRequest -> {
          // Server-initiated requests - queue for streaming
          notificationFlow.emit(message)
        }
        is JSONRPCNotification -> {
          // Notifications - queue for streaming
          notificationFlow.emit(message)
        }
        else -> {
          notificationFlow.emit(message)
        }
      }
    }
  }

  override suspend fun close() {
    if (initialized.load()) {
      initialized.store(false)
      pendingResponses.values.forEach { it.close() }
      pendingResponses.clear()
      _onClose.invoke()
    }
  }
}
