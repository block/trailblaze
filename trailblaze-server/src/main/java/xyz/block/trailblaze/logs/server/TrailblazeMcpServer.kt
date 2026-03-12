package xyz.block.trailblaze.logs.server

import ai.koog.agents.core.tools.Tool
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.core.tools.ToolResult
import ai.koog.agents.core.tools.annotations.InternalAgentToolsApi
import ai.koog.agents.core.tools.reflect.asTools
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.response.respondText
import io.ktor.server.routing.delete
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.sse.SSE
import io.ktor.server.sse.sse
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.server.ServerSession
import io.modelcontextprotocol.kotlin.sdk.server.StdioServerTransport
import io.modelcontextprotocol.kotlin.sdk.server.StreamableHttpServerTransport
import io.modelcontextprotocol.kotlin.sdk.types.McpJson
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.RequestId
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.asContextElement
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import xyz.block.trailblaze.devices.TrailblazeDevicePort
import xyz.block.trailblaze.logs.client.TrailblazeJsonInstance
import xyz.block.trailblaze.logs.server.ServerEndpoints.logsServerKtorEndpoints
import xyz.block.trailblaze.logs.server.SslConfig.configureForSelfSignedSsl
import xyz.block.trailblaze.logs.server.endpoints.CliRunRequest
import xyz.block.trailblaze.logs.server.endpoints.CliRunResponse
import xyz.block.trailblaze.logs.server.endpoints.CliStatusResponse
import xyz.block.trailblaze.mcp.McpToolProfile
import xyz.block.trailblaze.mcp.TrailblazeMcpBridge
import xyz.block.trailblaze.mcp.TrailblazeMcpSessionContext
import xyz.block.trailblaze.mcp.models.McpSessionId
import xyz.block.trailblaze.mcp.newtools.StepToolSet
import xyz.block.trailblaze.mcp.newtools.DeviceManagerToolSet
import xyz.block.trailblaze.mcp.newtools.TrailTool
import xyz.block.trailblaze.mcp.agent.BridgeUiActionExecutor
import xyz.block.trailblaze.mcp.utils.ScreenStateCaptureUtil
import xyz.block.trailblaze.agent.InnerLoopScreenAnalyzer
import xyz.block.trailblaze.mcp.sampling.LocalLlmSamplingSource
import xyz.block.trailblaze.mcp.toolsets.ToolSetCategory
import xyz.block.trailblaze.mcp.toolsets.ToolSetCategoryMapping
import xyz.block.trailblaze.mcp.utils.KoogToMcpExt.toMcpJsonSchemaObject
import xyz.block.trailblaze.toolcalls.toKoogToolDescriptor
import xyz.block.trailblaze.toolcalls.TrailblazeKoogTool.Companion.toTrailblazeToolDescriptor
import xyz.block.trailblaze.mcp.utils.TrailblazeToolToMcpBridge
import xyz.block.trailblaze.model.TrailblazeHostAppTarget
import xyz.block.trailblaze.report.utils.LogsRepo
import xyz.block.trailblaze.toolcalls.ToolSetCatalogEntry
import xyz.block.trailblaze.toolcalls.TrailblazeToolSetCatalog
import xyz.block.trailblaze.toolcalls.toolName
import xyz.block.trailblaze.util.Console
import java.io.File
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap
import ai.koog.prompt.executor.clients.LLMClient
import xyz.block.trailblaze.llm.TrailblazeLlmModel
import xyz.block.trailblaze.logs.client.TrailblazeLog
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.logs.model.TraceId
import kotlinx.datetime.Clock
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform

class TrailblazeMcpServer(
  val logsRepo: LogsRepo,
  val mcpBridge: TrailblazeMcpBridge,
  val trailsDirProvider: () -> File,
  val targetTestAppProvider: () -> TrailblazeHostAppTarget,
  val homeCallbackHandler: ((parameters: Map<String, List<String>>) -> Result<String>)? = null,
  val additionalToolsProvider: (TrailblazeMcpSessionContext, Server) -> ToolRegistry = { _, _ -> ToolRegistry {} },
  /** Optional callback to show the desktop window (for CLI integration) */
  var onShowWindowRequest: (() -> Unit)? = null,
  /** Optional callback to shutdown the application (for CLI integration) */
  var onShutdownRequest: (() -> Unit)? = null,
  /** Optional callback to handle CLI run requests (for CLI integration) */
  var onRunRequest: (suspend (CliRunRequest) -> CliRunResponse)? = null,
  /**
   * Optional provider for the LLM client to use for local sampling.
   * When provided, enables the Koog agent to use Trailblaze's configured LLM
   * instead of requiring MCP client sampling support.
   *
   * If null, the agent will only work with MCP clients that support sampling.
   */
  val llmClientProvider: (() -> LLMClient?)? = null,
  /**
   * Optional provider for the LLM model configuration.
   * Required alongside [llmClientProvider] for local LLM sampling.
   */
  val llmModelProvider: (() -> TrailblazeLlmModel?)? = null,
) {
  /**
   * Default tool profile for new MCP sessions.
   *
   * Set to [McpToolProfile.MINIMAL] to only expose high-level tools (device, blaze, verify, ask, trail)
   * to external MCP clients. Set via `--tool-profile MINIMAL` CLI flag.
   */
  var defaultToolProfile: McpToolProfile = McpToolProfile.FULL
  // Per-session progress token tracking - use String keys for reliable ConcurrentHashMap behavior
  private val sessionContexts = ConcurrentHashMap<String, TrailblazeMcpSessionContext>()

  // Track sessions by their MCP server session object (needed for per-session notifications)
  private val sessionServerSessions = ConcurrentHashMap<String, ServerSession>()

  // Custom SSE notification channels per session - bypasses SDK transport limitations
  // When client opens GET /mcp for notifications, we store the SSE session here
  // and forward notifications directly to it (in addition to SDK's notification())
  private val sseNotificationChannels = ConcurrentHashMap<String, Channel<String>>()

  companion object {
    /**
     * Thread-local to track the current MCP session ID during request processing.
     * This allows tools to look up the correct session context for sending notifications.
     *
     * NOTE: This is the MCP HTTP session ID, NOT the Trailblaze automation session ID.
     *
     * Propagated across coroutine context switches via [asContextElement] in the POST handler.
     */
    val currentMcpSessionId = ThreadLocal<String>()

    /**
     * Tracks the most recent active MCP session ID as a fallback for code paths that
     * run outside our coroutine scope (e.g., MCP SDK internal callbacks).
     *
     * NOTE: This is the MCP HTTP session ID, NOT the Trailblaze automation session ID.
     */
    @Volatile
    var lastActiveMcpSessionId: String? = null
  }

  // Track session creation time for timing diagnostics
  private val sessionCreationTimes = ConcurrentHashMap<String, Long>()

  // Track server startup time for uptime reporting
  private var serverStartTimeMillis: Long = 0L

  var hostMcpToolRegistry = ToolRegistry.Companion {}

  fun getSessionContext(mcpSessionId: McpSessionId): TrailblazeMcpSessionContext? =
    sessionContexts[mcpSessionId.sessionId]

  @OptIn(InternalAgentToolsApi::class)
  fun configureMcpServer(): Server {
    val server = Server(
      Implementation(
        name = "Trailblaze MCP server",
        version = "0.1.0",
      ),
      ServerOptions(
        capabilities = ServerCapabilities(
          prompts = ServerCapabilities.Prompts(listChanged = true),
          resources = ServerCapabilities.Resources(subscribe = true, listChanged = true),
          tools = ServerCapabilities.Tools(listChanged = true),
          logging = ServerCapabilities.Logging, // Enable logging notifications
        ),
      ),
    )

    return server
  }

  fun addToolsAsMcpToolsFromRegistry(
    newToolRegistry: ToolRegistry,
    mcpServer: Server,
    mcpSessionId: McpSessionId,
  ) {
    hostMcpToolRegistry = hostMcpToolRegistry.plus(newToolRegistry)

    newToolRegistry.tools.forEach { tool: Tool<*, *> ->
      // Build properties JsonObject directly (following Koog pattern)
      val properties = buildJsonObject {
        (tool.descriptor.requiredParameters + tool.descriptor.optionalParameters).forEach { param ->
          put(param.name, param.toMcpJsonSchemaObject())
        }
      }

      val required = tool.descriptor.requiredParameters.map { it.name }

      // Always provide properties (even if empty) - Goose client expects properties to be present
      // Previously we used ToolSchema() for empty tools, but this omits the "properties" field
      // which causes Goose to fail with "Cannot convert undefined or null to object"
      val inputSchema = ToolSchema(properties, required)

      mcpServer.addTool(
        name = tool.descriptor.name,
        description = tool.descriptor.description,
        inputSchema = inputSchema,
      ) { request: CallToolRequest ->

        // Extract MCP progress token from request metadata (_meta.progressToken)
        // This token is sent by the client to correlate progress notifications with the request
        val mcpProgressToken = request.meta?.get("progressToken")?.let { progressTokenValue ->
          when (progressTokenValue) {
            is JsonPrimitive -> RequestId.StringId(progressTokenValue.content)
            else -> null
          }
        }

        // Look up the correct MCP session context using the current MCP session ID
        // This is set by the HTTP handler when a request comes in via asContextElement()
        val currentMcpSession = currentMcpSessionId.get()
          ?: lastActiveMcpSessionId?.also {
            Console.log("[MCP] Warning: Thread-local session ID not set for tool ${tool.descriptor.name}, falling back to lastActive=$it")
          }
        val activeMcpSessionContext = currentMcpSession?.let { sessionContexts[it] }

        // Store MCP progress token for this session (used for notifications/progress)
        activeMcpSessionContext?.mcpProgressToken = mcpProgressToken

        // Also update the mcpServerSession if it's not set (race condition fix)
        if (activeMcpSessionContext?.mcpServerSession == null && currentMcpSession != null) {
          val serverSession = sessionServerSessions[currentMcpSession]
          if (serverSession != null) {
            activeMcpSessionContext?.mcpServerSession = serverSession
          }
        }

        val toolName = tool.descriptor.name

        @Suppress("UNCHECKED_CAST")
        val koogTool = newToolRegistry.getTool(toolName)

        // Convert request arguments to JsonObject
        val argumentsJsonObject = when (val args = request.arguments) {
          null -> JsonObject(emptyMap())
          else -> args
        }

        // ════════════════════════════════════════════════════════════════════════════════
        // MCP TOOL CALL LOGGING - Separate request/response for full trace visibility
        // TraceId correlates: request → inner loop activity → response
        // This maps cleanly to OpenTelemetry spans when we add that later
        // ════════════════════════════════════════════════════════════════════════════════
        val startTime = System.currentTimeMillis()
        val traceId = TraceId.generate(TraceId.Companion.TraceOrigin.MCP)
        val mcpSessionIdForLog = currentMcpSession ?: "unknown"

        // Resolve the Trailblaze session ID used for logging (must precede request log)
        val sessionIdForLog = resolveSessionIdForLog(
          toolName = toolName,
          args = argumentsJsonObject,
          fallbackMcpSessionId = mcpSessionIdForLog,
        )

        // Log REQUEST immediately (before any processing)
        val argsPreview = argumentsJsonObject.toString().take(500)
        Console.log("[MCP] -> $toolName (trace=${traceId.traceId}, session=$mcpSessionIdForLog) args=$argsPreview${if (argumentsJsonObject.toString().length > 500) "..." else ""}")


        // Save REQUEST log to disk (before processing starts)
        saveToolCallRequestLog(
          toolName = toolName,
          toolArgs = argumentsJsonObject,
          mcpSessionId = mcpSessionIdForLog,
          sessionIdForLog = sessionIdForLog,
          traceId = traceId,
        )

        try {
          val koogToolArgs = TrailblazeJsonInstance.decodeFromJsonElement(
            koogTool.argsSerializer,
            argumentsJsonObject,
          )

          // Execute tool in background thread to prevent UI blocking
          val toolResponse = withContext(Dispatchers.IO) {
            @OptIn(InternalAgentToolsApi::class)
            koogTool.executeUnsafe(args = koogToolArgs)
          }

          val toolResponseMessage = when (toolResponse) {
            is ToolResult -> toolResponse.toStringDefault()
            else -> toolResponse.toString()
          }

          val durationMs = System.currentTimeMillis() - startTime

          // Log RESPONSE (after processing complete)
          val resultPreview = toolResponseMessage.take(200)
          Console.log("[MCP] <- $toolName OK (${durationMs}ms, trace=${traceId.traceId}) $resultPreview${if (toolResponseMessage.length > 200) "..." else ""}")

          // Save RESPONSE log to disk
          saveToolCallResponseLog(
            toolName = toolName,
            mcpSessionId = mcpSessionIdForLog,
            sessionIdForLog = sessionIdForLog,
            traceId = traceId,
            successful = true,
            resultSummary = toolResponseMessage.take(2000).toJsonElement(),
            errorMessage = null,
            durationMs = durationMs,
          )

          CallToolResult(
            content = mutableListOf(
              TextContent(toolResponseMessage),
            ),
            isError = false,  // Explicitly set to false for success (some MCP clients require this)
          )
        } catch (e: Exception) {
          val durationMs = System.currentTimeMillis() - startTime

          // Return error result instead of throwing - allows LLM to see the error
          // Include exception class name for better debugging when message is null
          val errorMessage = e.message?.takeIf { it.isNotBlank() }
            ?: "${e::class.simpleName ?: "Unknown exception"} (no message)"

          // Log RESPONSE (error case)
          Console.error("[MCP] ← $toolName ERROR (${durationMs}ms, trace=${traceId.traceId}) ${e::class.simpleName}: ${e.message}")

          // Save RESPONSE log to disk (error case)
          saveToolCallResponseLog(
            toolName = toolName,
            mcpSessionId = mcpSessionIdForLog,
            sessionIdForLog = sessionIdForLog,
            traceId = traceId,
            successful = false,
            resultSummary = JsonPrimitive("Error: $errorMessage"),
            errorMessage = errorMessage,
            durationMs = durationMs,
          )

          CallToolResult(
            content = mutableListOf(
              TextContent("Error: $errorMessage"),
            ),
            isError = true,
          )
        }
      }
    }
  }

  /**
   * MCP Server using Streamable HTTP transport with multi-session support.
   *
   * This replaces the previous SSE-based implementation with a simpler HTTP-based approach:
   * - Client sends JSON-RPC requests via HTTP POST to /mcp
   * - Server creates session on-demand when clients connect (not pre-created at startup)
   * - Session management via Mcp-Session-Id header
   * - Optional GET /mcp for server-to-client streaming (notifications)
   *
   * Multi-session architecture:
   * - Each MCP client (Firebender, Claude Desktop, etc.) gets its own transport + session
   * - The StreamableHttpServerTransport SDK is single-session, so we create one per client
   * - When a request arrives without a session ID, a new transport is created
   * - Subsequent requests with a session ID are routed to the correct transport
   *
   * @param port The HTTP port to listen on
   * @param httpsPort The HTTPS port to listen on
   * @param wait Whether to block until server stops (default: false)
   * @return The embedded server instance
   */
  fun startStreamableHttpMcpServer(
    port: Int = TrailblazeDevicePort.TRAILBLAZE_DEFAULT_HTTP_PORT,
    httpsPort: Int = TrailblazeDevicePort.TRAILBLAZE_DEFAULT_HTTPS_PORT,
    wait: Boolean = false,
  ): EmbeddedServer<*, *> {
    serverStartTimeMillis = System.currentTimeMillis()
    Console.log("═══════════════════════════════════════════════════════════")
    Console.log("Starting Trailblaze MCP Server (Streamable HTTP)")
    Console.log("  HTTP Port:  $port")
    Console.log("  HTTPS Port: $httpsPort")
    Console.log("  MCP endpoint: http://localhost:$port/mcp")
    Console.log("")
    Console.log("  Transport: Streamable HTTP (MCP Spec 2025-11-25)")
    Console.log("  - POST /mcp → SSE stream with JSON-RPC responses")
    Console.log("  - GET /mcp  → SSE stream for progress notifications (custom)")
    Console.log("  - DELETE /mcp → Session termination")
    Console.log("")
    Console.log("Connection flow:")
    Console.log("  1. Client sends POST to /mcp with JSON-RPC request")
    Console.log("  2. Server creates session (if new) and returns Mcp-Session-Id header")
    Console.log("  3. Client includes Mcp-Session-Id header in subsequent requests")
    Console.log("  4. Optional: GET /mcp for server-to-client streaming")
    Console.log("")
    Console.log("  ✅ Progress notifications SUPPORTED via custom SSE channel!")
    Console.log("═══════════════════════════════════════════════════════════")
    Console.log("Will Wait: $wait")

    // Track active transports by session ID - one transport per client
    val activeTransports = ConcurrentHashMap<String, StreamableHttpServerTransport>()

    /**
     * Creates a new MCP server, transport, and session for a connecting client.
     * Each client gets its own server + transport combination to enable multi-session support.
     */
    suspend fun createSessionForClient(): StreamableHttpServerTransport {
      // Create a fresh MCP server for this client
      val mcpServer: Server = configureMcpServer()

      // Create a fresh transport for this client
      // enableJsonResponse = true means POST returns JSON responses directly.
      //
      // Per MCP Spec: "the server MUST either return Content-Type: text/event-stream,
      // to initiate an SSE stream, or Content-Type: application/json"
      // We're choosing JSON for POST responses (simpler, more compatible).
      //
      // GET/SSE for server-to-client notifications is handled separately.
      val transport = StreamableHttpServerTransport(
        enableJsonResponse = true,
      )

      // Variable to hold the ServerSession after createSession returns
      // The callback fires asynchronously when the client sends 'initialize', so we need to
      // make the ServerSession available to the callback via this captured variable
      var serverSessionHolder: ServerSession? = null

      // Set up session callbacks for this transport
      transport.setOnSessionInitialized { generatedSessionId ->
        Console.log("[MCP SESSION] onSessionInitialized callback fired for: $generatedSessionId")

        // Store the transport so subsequent requests can find it
        activeTransports[generatedSessionId] = transport

        // Store the ServerSession (captured from createSession result)
        serverSessionHolder?.let { serverSession ->
          sessionServerSessions[generatedSessionId] = serverSession
        }

        val mcpSessionId = McpSessionId(generatedSessionId)
        // Create session context WITH the mcpServerSession (available via serverSessionHolder)
        val sessionContext = TrailblazeMcpSessionContext(
          mcpServerSession = serverSessionHolder, // Now set from the captured variable!
          mcpSessionId = mcpSessionId,
          toolProfile = defaultToolProfile,
        )
        sessionContexts[generatedSessionId] = sessionContext
        sessionCreationTimes[generatedSessionId] = System.currentTimeMillis()

        // Wire up mode change callback for this session
        sessionContext.onModeChanged = { newMode ->
          Console.log("[MCP SESSION] Mode changed to ${newMode.name} for session $generatedSessionId")
        }
      }

      transport.setOnSessionClosed { closedSessionId ->
        Console.log("MCP session closed: $closedSessionId")

        // Clear the last active session fallback if it matches the closing session
        // to prevent stale session IDs from being used by future tool calls
        if (lastActiveMcpSessionId == closedSessionId) {
          lastActiveMcpSessionId = null
        }

        // Cancel any running automation on the associated device
        val sessionContext = sessionContexts[closedSessionId]
        sessionContext?.associatedDeviceId?.let { deviceId ->
          Console.log("Cancelling automation on device ${deviceId.instanceId} due to MCP session closure")
          try {
            mcpBridge.cancelAutomation(deviceId)
          } catch (e: Exception) {
            Console.error("Error cancelling automation: ${e.message}")
          }
        }

        // Invoke session closed callback for any custom cleanup
        sessionContext?.onSessionClosed?.invoke()

        // Cancel the session's coroutine scope to prevent leaks
        sessionContext?.close()

        // Clean up session state
        sessionContexts.remove(closedSessionId)
        sessionCreationTimes.remove(closedSessionId)
        sessionServerSessions.remove(closedSessionId)
        activeTransports.remove(closedSessionId)
        sseNotificationChannels.remove(closedSessionId)?.close()
      }

      // Register tools for this server
      val globalSessionContext = TrailblazeMcpSessionContext(
        mcpServerSession = null,
        mcpSessionId = McpSessionId("session-tools"),
        toolProfile = defaultToolProfile,
      )
      registerTools(mcpServer, McpSessionId("session-tools"), globalSessionContext)

      // Connect the server to the transport - this enables the transport to process requests
      // Note: The onSessionInitialized callback fires asynchronously when the client sends 'initialize'.
      // We store the ServerSession in serverSessionHolder so the callback can access it.
      val serverSession = mcpServer.createSession(transport)

      // Store the ServerSession so the callback can access it when it fires
      serverSessionHolder = serverSession

      // Defensive fix-up: if onSessionInitialized fired during createSession() (before
      // serverSessionHolder was set), any session context created would have mcpServerSession=null.
      // Patch it now that we have the real ServerSession.
      for ((sid, ctx) in sessionContexts) {
        if (ctx.mcpServerSession == null) {
          ctx.mcpServerSession = serverSession
          sessionServerSessions[sid] = serverSession
        }
      }

      return transport
    }

    val server = embeddedServer(
      factory = Netty,
      configure = {
        configureForSelfSignedSsl(
          requestedHttpPort = port,
          requestedHttpsPort = httpsPort,
        )
      },
    ) {
      // Install ContentNegotiation with McpJson for MCP type serialization
      install(ContentNegotiation) {
        json(McpJson)
      }

      // Install SSE plugin for the legacy SSE transport (supports notifications!)
      install(SSE)

      logsServerKtorEndpoints(
        logsRepo = logsRepo,
        homeCallbackHandler = homeCallbackHandler,
        installContentNegotiation = false,
        // Always register CLI callbacks - onShowWindowRequest is checked dynamically
        cliCallbacks = CliEndpointCallbacks(
          onRunRequest = { request ->
            onRunRequest?.invoke(request)
              ?: CliRunResponse(success = false, error = "Run handler not configured")
          },
          onShutdownRequest = {
            // Call the shutdown callback if set, otherwise fall back to System.exit
            onShutdownRequest?.invoke() ?: System.exit(0)
          },
          onShowWindowRequest = {
            // Call the callback if it's set (it's set by the UI after startup)
            onShowWindowRequest?.invoke()
          },
          statusProvider = {
            val deviceCount = try {
              kotlinx.coroutines.runBlocking { mcpBridge.getAvailableDevices().size }
            } catch (_: Exception) {
              0
            }
            val uptimeSeconds = if (serverStartTimeMillis > 0) {
              (System.currentTimeMillis() - serverStartTimeMillis) / 1000
            } else {
              0
            }
            CliStatusResponse(
              running = true,
              port = port,
              connectedDevices = deviceCount,
              uptimeSeconds = uptimeSeconds,
            )
          },
        ),
      )
      routing {
        // STREAMABLE HTTP TRANSPORT ENDPOINT (at /mcp)
        // STREAMABLE HTTP TRANSPORT (per MCP Spec 2025-11-25)
        // https://modelcontextprotocol.io/specification/2025-11-25/basic/transports
        // - POST /mcp: Returns JSON responses (per spec: server chooses JSON or SSE)
        // - GET /mcp: Returns SSE stream for server-to-client notifications
        //
        // POST /mcp - JSON-RPC request endpoint with JSON response
        post("/mcp") {
          withContext(Dispatchers.IO) {
            // Check for existing session
            val sessionIdHeader = call.request.headers["mcp-session-id"]

            val transport = if (sessionIdHeader != null) {
              // Existing session — single atomic lookup avoids TOCTOU race with DELETE/close
              activeTransports[sessionIdHeader] ?: run {
                Console.log(
                  "[MCP] Unknown session ID: $sessionIdHeader - returning 404 per MCP spec. " +
                    "Client should re-initialize. If using Firebender/Cursor, click 'Refresh All'.",
                )
                call.response.status(HttpStatusCode.NotFound)
                call.respondText(
                  """{"jsonrpc":"2.0","error":{"code":-32000,"message":"Session not found. Please re-initialize (Firebender/Cursor: click Refresh All)."}}""",
                  ContentType.Application.Json,
                )
                return@withContext
              }
            } else {
              // No session ID - new client, create session
              Console.log("[MCP] Creating new session for client...")
              createSessionForClient()
            }

            // Set the current MCP session ID for tools to look up the correct session context.
            // asContextElement() propagates the ThreadLocal across coroutine context switches.
            val effectiveMcpSessionId = sessionIdHeader ?: transport.sessionId ?: "unknown"
            lastActiveMcpSessionId = effectiveMcpSessionId

            withContext(currentMcpSessionId.asContextElement(effectiveMcpSessionId)) {
              // Process the request - SDK handles JSON response
              transport.handlePostRequest(null, call)
            }
          }
        }

        // GET /mcp - SSE stream for server-to-client notifications
        // Per spec: "The client MAY issue an HTTP GET to the MCP endpoint. This can be
        // used to open an SSE stream, allowing the server to communicate to the client,
        // without the client first sending data via HTTP POST."
        //
        // GET /mcp - Custom SSE stream for server-to-client notifications
        // The SDK's handleGetRequest rejects GET with enableJsonResponse=true,
        // so we implement our own SSE endpoint that bypasses the SDK.
        //
        // Note: sse() handler is defined first but Ktor may still route to get() for non-SSE
        sse("/mcp") {
          val sessionIdHeader = call.request.headers["mcp-session-id"]
          if (sessionIdHeader == null) {
            Console.log("[MCP GET/SSE] No session ID header - closing SSE")
            close()
            return@sse
          }

          val sessionContext = sessionContexts[sessionIdHeader]
          if (sessionContext == null) {
            Console.log("[MCP GET/SSE] No active session for: $sessionIdHeader - closing SSE")
            close()
            return@sse
          }

          Console.log("[MCP GET/SSE] Opening custom notification stream for session: $sessionIdHeader")

          // Create a channel for this SSE connection.
          // DROP_OLDEST keeps the latest events when buffer is full (newest are most relevant).
          // onUndeliveredElement logs drops so operators have visibility.
          val notificationChannel = Channel<String>(
            capacity = 256,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
            onUndeliveredElement = { dropped ->
              Console.log("[MCP GET/SSE] Dropped notification for session $sessionIdHeader (buffer full): ${dropped.take(100)}...")
            },
          )
          sseNotificationChannels[sessionIdHeader] = notificationChannel

          // Register the sender callback on the session context
          sessionContext.customSseNotificationSender = { jsonRpcMessage ->
            notificationChannel.trySend(jsonRpcMessage)
          }

          try {
            // Stream notifications as SSE events
            // Note: Using default event type (no explicit "event:" field) for broader compatibility
            // Some clients only parse "data:" lines and ignore/mishandle event types
            for (message in notificationChannel) {
              send(data = message)
            }
          } finally {
            // Cleanup when SSE connection closes
            Console.log("[MCP GET/SSE] Closing notification stream for session: $sessionIdHeader")
            sessionContext.customSseNotificationSender = null
            sseNotificationChannels.remove(sessionIdHeader)
            notificationChannel.close()
          }
        }

        // DELETE /mcp - Session termination
        delete("/mcp") {
          withContext(Dispatchers.IO) {
            val sessionIdHeader = call.request.headers["mcp-session-id"]
            val transport = sessionIdHeader?.let { activeTransports[it] }

            if (transport != null) {
              transport.handleDeleteRequest(call)
            } else {
              call.response.status(HttpStatusCode.NotFound)
              call.respondText("{\"error\": \"Session not found\"}")
            }
          }
        }
      }
    }.start(wait = wait)

    Console.log("MCP server started - ready for connections")
    return server
  }

  /**
   * Registers all tools with the MCP server.
   *
   * Device control tools (TrailblazeTools) use progressive disclosure: only the core
   * toolset is registered initially. The LLM can request additional toolsets via the
   * `setActiveToolSets` MCP tool.
   */
  @OptIn(InternalAgentToolsApi::class)
  private fun registerTools(
    mcpServer: Server,
    mcpSessionId: McpSessionId,
    sessionContext: TrailblazeMcpSessionContext,
  ) {
    val isMinimalProfile = sessionContext.toolProfile == McpToolProfile.MINIMAL
    val toolSetCatalog = TrailblazeToolSetCatalog.defaultEntries(setOfMarkEnabled = true)

    // Track currently registered TrailblazeTool names so we can remove them on toolset changes
    val registeredTrailblazeToolNames = mutableSetOf<String>()

    // Create the TrailblazeTool bridge for device control tools
    val trailblazeToolBridge = TrailblazeToolToMcpBridge(
      mcpBridge = mcpBridge,
      sessionContext = sessionContext,
    )

    // Callback for when the LLM requests a toolset change via setActiveToolSets
    val onActiveToolSetsChanged: (List<String>, List<ToolSetCatalogEntry>) -> Unit =
      { activeToolSetIds, catalog ->
        val newToolClasses = TrailblazeToolSetCatalog.resolve(activeToolSetIds, catalog)

        // Remove previously registered TrailblazeTool MCP tools
        if (registeredTrailblazeToolNames.isNotEmpty()) {
          mcpServer.removeTools(registeredTrailblazeToolNames.toList())
          registeredTrailblazeToolNames.clear()
        }

        // Register the new set of TrailblazeTools
        trailblazeToolBridge.registerTrailblazeTools(
          toolClasses = newToolClasses,
          mcpServer = mcpServer,
          mcpSessionId = mcpSessionId,
        )
        registeredTrailblazeToolNames.addAll(newToolClasses.map { it.toolName().toolName })

        Console.log("Active toolsets changed to: $activeToolSetIds (${newToolClasses.size} tools)")
      }

    val initialToolRegistry = ToolRegistry.Companion {
      // Minimal default tools: device management and session control only
      // ObservationToolSet (getScreenState, viewHierarchy) NOT registered by default
      // to keep large screen payloads out of the primary context window.
      // Add via additionalToolsProvider if needed for manual inspection.
      // Device connection tool (quick connect for device control persona)
      tools(
        DeviceManagerToolSet(
          sessionContext = sessionContext,
          mcpBridge = mcpBridge,
          toolSetCatalog = toolSetCatalog,
          onActiveToolSetsChanged = onActiveToolSetsChanged,
        ).asTools(TrailblazeJsonInstance),
      )

      // Trail management tool (for test authoring persona)
      tools(
        TrailTool(
          sessionContext = sessionContext,
          mcpBridge = mcpBridge,
        ).asTools(TrailblazeJsonInstance),
      )

      // Two-tier agent MCP tools (conditional on LLM being configured)
      // These tools enable external agents to use Trailblaze's inner agent for screen analysis
      val llmClient = llmClientProvider?.invoke()
      val llmModel = llmModelProvider?.invoke()
      if (llmClient != null && llmModel != null) {
        // Create inner agent components for the two-tier tools
        val innerSamplingSource = LocalLlmSamplingSource(
          llmClient = llmClient,
          llmModel = llmModel,
          logsRepo = logsRepo,
          sessionIdProvider = { mcpBridge.getActiveSessionId() },
        )
        val screenAnalyzer = InnerLoopScreenAnalyzer(
          samplingSource = innerSamplingSource,
          model = llmModel,
        )
        val uiActionExecutor = BridgeUiActionExecutor(mcpBridge)

        // Primary automation tools - step, verify, ask (with recording support)
        // Note: Inner agent ALWAYS needs tools, even when includePrimitiveTools=false
        // We use ToolSetCategoryMapping directly to get core tools for the inner agent.
        val innerAgentTools = ToolSetCategoryMapping.getToolClasses(ToolSetCategory.STANDARD)
          .mapNotNull { it.toKoogToolDescriptor()?.toTrailblazeToolDescriptor() }
        Console.log("[TrailblazeMcpServer] Inner agent has ${innerAgentTools.size} tools available")

        tools(
          StepToolSet(
            screenAnalyzer = screenAnalyzer,
            executor = uiActionExecutor,
            screenStateProvider = {
              runBlocking { ScreenStateCaptureUtil.captureScreenState(mcpBridge) }
            },
            sessionContext = sessionContext,
            availableToolsProvider = { innerAgentTools },
          ).asTools(TrailblazeJsonInstance),
        )
        Console.log("[TrailblazeMcpServer] blaze(), verify(), ask() tools registered")
      } else {
        Console.log("[TrailblazeMcpServer] Two-tier agent tools NOT registered - LLM not configured")
      }
    } + if (isMinimalProfile) ToolRegistry {} else additionalToolsProvider(sessionContext, mcpServer)

    addToolsAsMcpToolsFromRegistry(
      newToolRegistry = initialToolRegistry,
      mcpServer = mcpServer,
      mcpSessionId = mcpSessionId,
    )

    if (!isMinimalProfile) {
      // Register core TrailblazeTools (progressive disclosure) — skipped in MINIMAL mode
      val coreToolClasses = TrailblazeToolSetCatalog.resolve(emptyList(), toolSetCatalog)
      trailblazeToolBridge.registerTrailblazeTools(
        toolClasses = coreToolClasses,
        mcpServer = mcpServer,
        mcpSessionId = mcpSessionId,
      )
      registeredTrailblazeToolNames.addAll(coreToolClasses.map { it.toolName().toolName })
    }

    if (isMinimalProfile) {
      // In MINIMAL mode, remove all tools except the allowed set.
      // Tools come from DeviceManagerToolSet (device + extras we don't want),
      // StepTool (blaze/verify/ask), and TrailTool (trail).
      val allowedTools = McpToolProfile.MINIMAL_TOOL_NAMES
      val allRegisteredNames = hostMcpToolRegistry.tools.map { it.descriptor.name }.toSet()
      val toolsToRemove = allRegisteredNames - allowedTools
      if (toolsToRemove.isNotEmpty()) {
        mcpServer.removeTools(toolsToRemove.toList())
        Console.log("[MCP] MINIMAL profile: removed ${toolsToRemove.size} tools (${toolsToRemove.joinToString()}), keeping: $allowedTools")
      }
    }

    mcpServer.onClose { }
  }

  /**
   * Saves an MCP tool call REQUEST log to disk.
   *
   * Logged IMMEDIATELY when a request arrives, before any processing.
   * The traceId correlates this with the response and any inner loop activity.
   */
  private fun saveToolCallRequestLog(
    toolName: String,
    toolArgs: JsonObject,
    mcpSessionId: String,
    sessionIdForLog: SessionId,
    traceId: TraceId,
  ) {
    val log = TrailblazeLog.McpToolCallRequestLog(
      toolName = toolName,
      toolArgs = toolArgs,
      mcpSessionId = mcpSessionId,
      traceId = traceId,
      session = sessionIdForLog,
      timestamp = Clock.System.now(),
    )

    try {
      logsRepo.saveLogToDisk(log)
    } catch (e: Exception) {
      Console.log("[MCP] Warning: Failed to save request log: ${e.message}")
    }
  }

  /**
   * Saves an MCP tool call RESPONSE log to disk.
   *
   * Logged AFTER processing completes, capturing what we sent back.
   * The traceId correlates this with the original request and inner loop activity.
   * Duration captures the full request→response time (like an OTel span).
   */
  private fun saveToolCallResponseLog(
    toolName: String,
    mcpSessionId: String,
    sessionIdForLog: SessionId,
    traceId: TraceId,
    successful: Boolean,
    resultSummary: JsonElement,
    errorMessage: String?,
    durationMs: Long,
  ) {
    val log = TrailblazeLog.McpToolCallResponseLog(
      toolName = toolName,
      mcpSessionId = mcpSessionId,
      traceId = traceId,
      successful = successful,
      resultSummary = resultSummary,
      errorMessage = errorMessage,
      durationMs = durationMs,
      session = sessionIdForLog,
      timestamp = Clock.System.now(),
    )

    try {
      logsRepo.saveLogToDisk(log)
    } catch (e: Exception) {
      Console.log("[MCP] Warning: Failed to save response log: ${e.message}")
    }
  }

  /**
   * MCP Server using STDIO transport (stdin/stdout for JSON-RPC).
   *
   * This is the preferred transport for MCP client integrations (e.g., Claude Desktop,
   * Firebender, Goose) where the MCP client launches Trailblaze as a subprocess and
   * communicates via stdin/stdout.
   *
   * **Important:** Call [Console.useStdErr] before this method to redirect all console
   * output to stderr, keeping stdout clean for the JSON-RPC protocol stream. Because
   * [Console.useStdErr] redirects `System.out` to stderr, callers must capture the
   * original stdout **before** that call and pass it as [stdout].
   *
   * Usage in MCP client configuration:
   * ```json
   * {
   *   "mcpServers": {
   *     "trailblaze": {
   *       "command": "./trailblaze",
   *       "args": ["mcp", "--stdio"]
   *     }
   *   }
   * }
   * ```
   *
   * @param stdout The output stream for JSON-RPC responses. Pass the original
   *   `System.out` captured **before** [Console.useStdErr] was called, so that
   *   the transport writes to the real stdout (fd 1) rather than the redirected stderr.
   *
   * This method blocks until the session is closed by the client.
   */
  suspend fun startStdioMcpServer(
    stdout: OutputStream = System.out,
    toolProfile: McpToolProfile = defaultToolProfile,
  ) {
    Console.log("Starting Trailblaze MCP Server (STDIO)")

    val mcpServer: Server = configureMcpServer()

    val mcpSessionId = McpSessionId("stdio-${System.currentTimeMillis()}")
    val sessionContext = TrailblazeMcpSessionContext(
      mcpServerSession = null,
      mcpSessionId = mcpSessionId,
      toolProfile = toolProfile,
    )
    sessionContexts[mcpSessionId.sessionId] = sessionContext
    sessionCreationTimes[mcpSessionId.sessionId] = System.currentTimeMillis()

    val transport = StdioServerTransport(
      System.`in`.asSource().buffered(),
      stdout.asSink().buffered(),
    )

    val session = mcpServer.createSession(transport)
    sessionContext.mcpServerSession = session
    registerTools(mcpServer, mcpSessionId, sessionContext)

    Console.log("STDIO MCP server ready — waiting for JSON-RPC on stdin")

    val done = Job()
    session.onClose {
      sessionContexts.remove(mcpSessionId.sessionId)
      sessionCreationTimes.remove(mcpSessionId.sessionId)
      done.complete()
    }
    done.join()
  }

  /**
   * @deprecated Use [startStreamableHttpMcpServer] instead. SSE transport has been deprecated by LLM providers.
   */
  @Deprecated(
    message = "SSE transport has been deprecated. Use startStreamableHttpMcpServer() instead.",
    replaceWith = ReplaceWith("startStreamableHttpMcpServer(port, wait)"),
  )
  fun startSseMcpServer(
    port: Int = TrailblazeDevicePort.TRAILBLAZE_DEFAULT_HTTP_PORT,
    httpsPort: Int = TrailblazeDevicePort.TRAILBLAZE_DEFAULT_HTTPS_PORT,
    wait: Boolean = false,
  ): EmbeddedServer<*, *> = startStreamableHttpMcpServer(port, httpsPort, wait)

  /**
   * Converts a string to a JsonElement for log storage.
   * If the string is valid JSON, returns the parsed JsonElement.
   * Otherwise, returns the string wrapped in a JsonPrimitive.
   */
  private fun String.toJsonElement(): JsonElement {
    return try {
      TrailblazeJsonInstance.parseToJsonElement(this)
    } catch (e: Exception) {
      JsonPrimitive(this)
    }
  }

  private suspend fun resolveSessionIdForLog(
    toolName: String,
    args: JsonObject,
    fallbackMcpSessionId: String,
  ): SessionId {
    val action = (args["action"] as? JsonPrimitive)?.content?.uppercase()
    val shouldEnsureSession = toolName == "device" &&
      (action == "CONNECT" || action == "ANDROID" || action == "IOS")

    val sessionId = if (shouldEnsureSession) {
      // This must run BEFORE the request log so the session start log is first.
      preselectDeviceForSession(action, args)
      mcpBridge.ensureSessionAndGetId()
    } else {
      mcpBridge.getActiveSessionId()
    }

    return sessionId ?: SessionId(fallbackMcpSessionId)
  }

  private suspend fun preselectDeviceForSession(action: String?, args: JsonObject) {
    val devices = mcpBridge.getAvailableDevices()
    if (devices.isEmpty()) return

    val targetDevice = when (action) {
      "CONNECT" -> {
        val deviceId = (args["deviceId"] as? JsonPrimitive)?.content
        devices.find { it.instanceId == deviceId }
      }
      "ANDROID" -> devices.find { it.platform == TrailblazeDevicePlatform.ANDROID }
      "IOS" -> devices.find { it.platform == TrailblazeDevicePlatform.IOS }
      else -> null
    }

    if (targetDevice != null) {
      mcpBridge.selectDevice(targetDevice.trailblazeDeviceId)
    }
  }
}
