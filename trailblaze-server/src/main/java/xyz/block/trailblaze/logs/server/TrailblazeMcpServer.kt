package xyz.block.trailblaze.logs.server

import ai.koog.agents.core.tools.Tool
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.core.tools.ToolResult
import ai.koog.agents.core.tools.annotations.InternalAgentToolsApi
import ai.koog.agents.core.tools.reflect.asTools
import ai.koog.serialization.kotlinx.KotlinxSerializer
import ai.koog.serialization.kotlinx.toKoogJSONObject
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
import io.ktor.server.sse.heartbeat
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.asContextElement
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration.Companion.seconds
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDevicePort
import xyz.block.trailblaze.logs.client.TrailblazeJsonInstance
import xyz.block.trailblaze.logs.server.ServerEndpoints.logsServerKtorEndpoints
import xyz.block.trailblaze.logs.server.SslConfig.configureForSelfSignedSsl
import xyz.block.trailblaze.logs.server.endpoints.CliRunRequest
import xyz.block.trailblaze.logs.server.endpoints.CliRunResponse
import xyz.block.trailblaze.logs.server.endpoints.CliStatusResponse
import xyz.block.trailblaze.mcp.AgentImplementation
import xyz.block.trailblaze.mcp.DeviceClaimRegistry
import xyz.block.trailblaze.mcp.McpDeviceContext
import xyz.block.trailblaze.mcp.McpToolProfile
import xyz.block.trailblaze.mcp.TrailblazeMcpBridge
import xyz.block.trailblaze.mcp.TrailblazeMcpMode
import xyz.block.trailblaze.mcp.TrailblazeMcpSessionContext
import xyz.block.trailblaze.mcp.models.McpSessionId
import xyz.block.trailblaze.api.ScreenState
import xyz.block.trailblaze.mcp.newtools.ConfigToolSet
import xyz.block.trailblaze.mcp.newtools.SessionToolSet
import xyz.block.trailblaze.mcp.newtools.SnapshotToolSet
import xyz.block.trailblaze.mcp.newtools.StepToolSet
import xyz.block.trailblaze.mcp.resources.registerResources
import xyz.block.trailblaze.mcp.newtools.DeviceManagerToolSet
import xyz.block.trailblaze.mcp.newtools.TrailMcpTool
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
import xyz.block.trailblaze.llm.TrailblazeLlmModelList
import xyz.block.trailblaze.logs.client.LogEmitter
import xyz.block.trailblaze.logs.client.TrailblazeLog
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.logs.model.TraceId
import kotlinx.datetime.Clock
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import kotlin.coroutines.EmptyCoroutineContext

/**
 * Snapshot of a single MCP session for debug UI display.
 */
data class McpSessionSnapshot(
  val sessionId: String,
  val mode: TrailblazeMcpMode,
  val associatedDeviceId: TrailblazeDeviceId?,
  val clientName: String?,
  val toolProfile: McpToolProfile,
  val agentImplementation: AgentImplementation,
  val isRecording: Boolean,
  val currentTrailName: String?,
  val createdAtMillis: Long?,
)

/**
 * Observable state of the MCP server for debug UI.
 */
data class McpServerDebugState(
  val isRunning: Boolean = false,
  val serverStartTimeMillis: Long = 0L,
  val sessions: List<McpSessionSnapshot> = emptyList(),
)

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
  /** Provider for all supported LLM model lists (for the llm/providers resource). */
  val llmModelListsProvider: () -> Set<TrailblazeLlmModelList>,
) {
  /**
   * Default tool profile for new MCP sessions.
   *
   * Set to [McpToolProfile.MINIMAL] to only expose high-level tools (device, blaze, ask, trail)
   * to external MCP clients. Set via `--tool-profile MINIMAL` CLI flag.
   */
  var defaultToolProfile: McpToolProfile = McpToolProfile.FULL

  /**
   * Default operating mode for new MCP sessions.
   *
   * - [TrailblazeMcpMode.TRAILBLAZE_AS_AGENT]: Trailblaze handles LLM reasoning internally.
   *   Default for Block internal usage where an LLM is already configured.
   * - [TrailblazeMcpMode.MCP_CLIENT_AS_AGENT]: MCP client handles all LLM reasoning.
   *   Recommended for OSS usage — no Trailblaze-side LLM required.
   */
  var defaultMode: TrailblazeMcpMode = TrailblazeMcpMode.TRAILBLAZE_AS_AGENT

  /** Tracks exclusive device claims across MCP sessions. */
  val deviceClaimRegistry = DeviceClaimRegistry(
    isSessionAlive = { sessionId -> sessionContexts.containsKey(sessionId) },
  )

  companion object {
    /**
     * Maximum time an MCP tool call can execute before being cancelled.
     * Prevents indefinite hangs when a device session crashes mid-execution
     * (broken driver connection, dead Maestro session, etc.).
     * 5 minutes is generous enough for long operations like trail(action=RUN)
     * while still ensuring the MCP client always gets a response.
     */
    private const val MCP_TOOL_EXECUTION_TIMEOUT_MS = 5 * 60 * 1000L

    /** Timeout for ending a session during disconnect cleanup to prevent blocking the close callback. */
    private const val SESSION_END_TIMEOUT_MS = 5_000L

    /** Interval between SSE keepalive comments to prevent idle connection timeouts. */
    private val SSE_HEARTBEAT_PERIOD = 15.seconds

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
     * Tracks the most recent active MCP session ID for STDIO transport only.
     * For HTTP multi-session, the thread-local [currentMcpSessionId] is authoritative.
     * This must NOT be used as a fallback in multi-session HTTP mode because it would
     * route tool execution to the wrong session under concurrency.
     *
     * NOTE: This is the MCP HTTP session ID, NOT the Trailblaze automation session ID.
     */
    @Volatile
    var lastActiveMcpSessionId: String? = null
  }

  // Per-session progress token tracking - use String keys for reliable ConcurrentHashMap behavior
  private val sessionContexts = ConcurrentHashMap<String, TrailblazeMcpSessionContext>()

  // Track sessions by their MCP server session object (needed for per-session notifications)
  private val sessionServerSessions = ConcurrentHashMap<String, ServerSession>()

  // Custom SSE notification channels per session - bypasses SDK transport limitations
  // When client opens GET /mcp for notifications, we store the SSE session here
  // and forward notifications directly to it (in addition to SDK's notification())
  private val sseNotificationChannels = ConcurrentHashMap<String, Channel<String>>()

  // Track session creation time for timing diagnostics
  private val sessionCreationTimes = ConcurrentHashMap<String, Long>()

  // Track server startup time for uptime reporting
  private var serverStartTimeMillis: Long = 0L

  // Observable debug state for the Dev Debug UI
  private val _mcpServerDebugStateFlow = MutableStateFlow(McpServerDebugState())
  val mcpServerDebugStateFlow: StateFlow<McpServerDebugState> =
    _mcpServerDebugStateFlow.asStateFlow()

  private fun emitDebugState() {
    val sessions =
      sessionContexts.map { (id, ctx) ->
        McpSessionSnapshot(
          sessionId = id,
          mode = ctx.mode,
          associatedDeviceId = ctx.associatedDeviceId,
          clientName = ctx.mcpClientName,
          toolProfile = ctx.toolProfile,
          agentImplementation = ctx.agentImplementation,
          isRecording = ctx.isRecordingActive(),
          currentTrailName = ctx.getCurrentTrailName(),
          createdAtMillis = sessionCreationTimes[id],
        )
      }
    val newState =
      McpServerDebugState(
        isRunning = serverStartTimeMillis > 0,
        serverStartTimeMillis = serverStartTimeMillis,
        sessions = sessions,
      )
    _mcpServerDebugStateFlow.value = newState
    Console.log(
      "[MCP DEBUG] State updated: running=${newState.isRunning}, " +
        "sessions=${sessions.size} [${sessions.joinToString { "${it.clientName ?: "?"} -> ${it.associatedDeviceId?.instanceId ?: "no-device"}" }}]"
    )
  }

  private val hostMcpToolRegistryBySession = ConcurrentHashMap<String, ToolRegistry>()

  // Track registered TrailblazeTool names per MCP session so mode changes can clean up expanded toolsets
  private val registeredTrailblazeToolNamesBySession = ConcurrentHashMap<String, MutableSet<String>>()

  fun getSessionContext(mcpSessionId: McpSessionId): TrailblazeMcpSessionContext? =
    sessionContexts[mcpSessionId.sessionId]

  /**
   * Terminates an MCP session and releases all its resources.
   *
   * Called when force-claiming a device from another session to ensure the displaced
   * session is properly cleaned up instead of lingering indefinitely.
   *
   * @return the client name of the terminated session, or null if session not found
   */
  fun terminateSession(sessionId: String): String? {
    val sessionContext = sessionContexts[sessionId] ?: return null
    val clientName = sessionContext.mcpClientName

    Console.log("Terminating MCP session $sessionId (client: ${clientName ?: "unknown"})")

    // Cancel any running automation on the associated device
    sessionContext.associatedDeviceId?.let { deviceId ->
      try {
        mcpBridge.cancelAutomation(deviceId)
      } catch (e: Exception) {
        Console.error("Error cancelling automation during session termination: ${e.message}")
      }
      mcpBridge.releasePersistentDeviceConnection(deviceId)
    }

    // Release device claims
    deviceClaimRegistry.releaseAllForSession(sessionId)

    // Run custom cleanup and close coroutine scope
    sessionContext.onSessionClosed?.invoke()
    sessionContext.close()

    // Remove from tracking maps
    sessionContexts.remove(sessionId)
    sessionCreationTimes.remove(sessionId)
    sessionServerSessions.remove(sessionId)
    sseNotificationChannels.remove(sessionId)?.close()
    registeredTrailblazeToolNamesBySession.remove(sessionId)
    hostMcpToolRegistryBySession.remove(sessionId)

    emitDebugState()
    return clientName
  }

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
    hostMcpToolRegistryBySession[mcpSessionId.sessionId] = newToolRegistry

    Console.log("[MCP] Registering ${newToolRegistry.tools.size} tools from Koog registry")
    newToolRegistry.tools.forEach { tool: Tool<*, *> ->
      try {
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

        // Look up the correct MCP session context using the current MCP session ID.
        // This is set by the HTTP handler when a request comes in via asContextElement().
        // For STDIO transport, the thread-local may not be set, so we fall back to
        // lastActiveMcpSessionId (safe because STDIO has exactly one session).
        // We do NOT use lastActiveMcpSessionId for HTTP multi-session because it could
        // route to the wrong session under concurrency.
        val threadLocalSession = currentMcpSessionId.get()
        val currentMcpSession = threadLocalSession ?: lastActiveMcpSessionId
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

        // Lazily populate client name from SDK handshake (covers STDIO path
        // and any HTTP race conditions where onSessionInitialized fires
        // before handleInitialize sets clientVersion)
        if (activeMcpSessionContext?.mcpClientName == null) {
          activeMcpSessionContext?.mcpServerSession?.clientVersion?.name?.let { name ->
            activeMcpSessionContext.mcpClientName = name
            Console.log("[MCP] Client identified: $name (session=$currentMcpSession)")
            emitDebugState()
          }
        }

        // Build a coroutine context element that propagates the per-session device ID
        // to the IO thread during tool execution. asContextElement sets the ThreadLocal when
        // the coroutine resumes on the IO thread and restores it on suspension/completion,
        // preventing races between concurrent HTTP sessions.
        // NOTE: Do NOT call mcpBridge.selectDeviceForSession() here — that would set the
        // ThreadLocal on the handler thread (Netty event loop), which races with other sessions.
        val associatedDevice = activeMcpSessionContext?.associatedDeviceId
        val deviceIdContext = associatedDevice?.let { deviceId ->
          McpDeviceContext.currentDeviceId.asContextElement(deviceId)
        } ?: EmptyCoroutineContext

        val toolName = tool.descriptor.name

        @Suppress("UNCHECKED_CAST")
        val koogTool = newToolRegistry.getTool(toolName)
          ?: return@addTool CallToolResult(
            content = mutableListOf(TextContent("Error: Tool '$toolName' not found in registry")),
            isError = true,
          )

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

        // Resolve the Trailblaze session ID used for logging (must precede request log).
        // Wrap in deviceIdContext so ensureSessionAndGetId resolves the correct device
        // for this MCP session (the ThreadLocal isn't set yet on the handler thread).
        val sessionIdForLog = withContext(deviceIdContext) {
          resolveSessionIdForLog(
            toolName = toolName,
            args = argumentsJsonObject,
            fallbackMcpSessionId = mcpSessionIdForLog,
          )
        }

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
          val koogToolArgs = koogTool.decodeArgs(
            argumentsJsonObject.toKoogJSONObject(),
            KotlinxSerializer(TrailblazeJsonInstance),
          )

          // Execute tool in background thread to prevent UI blocking.
          // deviceIdContext propagates the per-session device ID ThreadLocal across
          // the coroutine context switch, preventing cross-session device races.
          // Timeout prevents indefinite hangs when a device session crashes mid-execution
          // (e.g., device driver dies, Maestro session fails). Without this, a broken device
          // connection can leave the MCP call blocked forever, confusing the MCP client.
          val toolResponse = withTimeout(MCP_TOOL_EXECUTION_TIMEOUT_MS) {
            withContext(Dispatchers.IO + deviceIdContext) {
              @OptIn(InternalAgentToolsApi::class)
              koogTool.executeUnsafe(args = koogToolArgs)
            }
          }

          val toolResponseMessage = when (toolResponse) {
            is ToolResult -> toolResponse.toStringDefault()
            else -> toolResponse.toString()
          }

          val durationMs = System.currentTimeMillis() - startTime

          // Detect error responses from tools that return error strings
          // rather than throwing exceptions (e.g., "Error: No Android device available")
          // Also detect structured JSON errors (e.g., {"success":false,"error":"..."})
          val isToolError = toolResponseMessage.startsWith("Error:") ||
            toolResponseMessage.startsWith("Failed:") ||
            hasJsonErrorField(toolResponseMessage)

          // Log RESPONSE (after processing complete)
          val resultPreview = toolResponseMessage.take(200)
          val statusTag = if (isToolError) "TOOL_ERROR" else "OK"
          Console.log("[MCP] <- $toolName $statusTag (${durationMs}ms, trace=${traceId.traceId}) $resultPreview${if (toolResponseMessage.length > 200) "..." else ""}")

          // Save RESPONSE log to disk
          saveToolCallResponseLog(
            toolName = toolName,
            mcpSessionId = mcpSessionIdForLog,
            sessionIdForLog = sessionIdForLog,
            traceId = traceId,
            successful = !isToolError,
            resultSummary = toolResponseMessage.take(2000).toJsonElement(),
            errorMessage = if (isToolError) toolResponseMessage else null,
            durationMs = durationMs,
          )

          CallToolResult(
            content = mutableListOf(
              TextContent(toolResponseMessage),
            ),
            isError = isToolError,
          )
        } catch (e: Throwable) {
          // Catch Throwable (not just Exception) to also handle Error subclasses
          // (OutOfMemoryError, StackOverflowError, etc.) that would otherwise
          // propagate to the MCP SDK and close the transport connection.
          val durationMs = System.currentTimeMillis() - startTime

          // Log CancellationException specifically — these can indicate the STDIO
          // transport died or the coroutine scope was cancelled externally.
          if (e is kotlinx.coroutines.CancellationException) {
            Console.error("[MCP] ← $toolName CANCELLED (${durationMs}ms, trace=${traceId.traceId}) — coroutine was cancelled. Cause: ${e.cause?.let { "${it::class.simpleName}: ${it.message}" } ?: "no cause"}")
            Console.error("[MCP]   Stack: ${e.stackTraceToString().take(1000)}")
          }

          // Unwrap reflection wrappers (InvocationTargetException) to surface the
          // actual root cause. Koog's ToolSet.executeUnsafe uses Kotlin reflection
          // (KCallables.callSuspendBy) which wraps thrown exceptions.
          // Log the full exception chain for debugging
          Console.error("[MCP] Exception chain: ${generateSequence(e) { it.cause }.joinToString(" -> ") { "${it::class.qualifiedName ?: it::class.simpleName}: ${it.message}" }}")
          Console.error("[MCP] Stack trace: ${e.stackTraceToString().take(2000)}")
          val rootCause = generateSequence(e) { it.cause }
            .firstOrNull { it::class.simpleName != "InvocationTargetException" }
            ?: e

          // Return error result instead of throwing - allows LLM to see the error
          // Include exception class name for better debugging when message is null
          val errorMessage = rootCause.message?.takeIf { it.isNotBlank() }
            ?: "${rootCause::class.simpleName ?: "Unknown exception"} (no message)"

          // Log RESPONSE (error case) — log both wrapper and root cause for full picture
          Console.error("[MCP] ← $toolName ERROR (${durationMs}ms, trace=${traceId.traceId}) ${e::class.simpleName}: ${e.message}" +
            if (rootCause !== e) " [caused by ${rootCause::class.simpleName}: ${rootCause.message}]" else "")

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
      Console.log("[MCP] Registered tool: ${tool.descriptor.name}")
      } catch (e: Exception) {
        Console.error("[MCP] FAILED to register tool '${tool.descriptor.name}': ${e::class.simpleName}: ${e.message}")
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
    emitDebugState()
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
    suspend fun createSessionForClient(
      toolProfileOverride: McpToolProfile? = null,
    ): StreamableHttpServerTransport {
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
        StreamableHttpServerTransport.Configuration(enableJsonResponse = true),
      )

      // Variable to hold the ServerSession after createSession returns.
      // The callback fires asynchronously when the client sends 'initialize', so we need to
      // make the ServerSession available to the callback via this captured variable.
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
          mcpServerSession = serverSessionHolder,
          mcpSessionId = mcpSessionId,
          toolProfile = toolProfileOverride ?: defaultToolProfile,
          mode = defaultMode,
        )

        // Populate client name from the SDK's initialize handshake.
        // clientVersion is set by handleInitialize() before onSessionInitialized fires.
        serverSessionHolder?.clientVersion?.name?.let { name ->
          sessionContext.mcpClientName = name
          Console.log("[MCP SESSION] Client identified: $name")
        }

        sessionContexts[generatedSessionId] = sessionContext
        sessionCreationTimes[generatedSessionId] = System.currentTimeMillis()

        // Wire up mode change callback for this session — re-register tools when mode changes
        sessionContext.onModeChanged = { newMode ->
          Console.log("[MCP SESSION] Mode changed to ${newMode.name} for session $generatedSessionId — re-registering tools")
          registerTools(mcpServer, mcpSessionId, sessionContext)
          emitDebugState()
        }

        // Register tools with the REAL per-session context.
        // This ensures DeviceManagerToolSet, StepToolSet, and TrailTool all operate on
        // the actual session (device association, recording, cleanup) rather than a
        // throwaway placeholder. Safe to call here because the client can only invoke
        // tools after the initialize handshake completes.
        registerTools(mcpServer, mcpSessionId, sessionContext)

        // Auto-connect to single mobile device (non-blocking)
        sessionContext.sendProgressNotificationsScope.launch {
          autoConnectSingleDevice(sessionContext)
          emitDebugState()
        }

        emitDebugState()
      }

      transport.setOnSessionClosed { closedSessionId ->
        Console.log("MCP session closed: $closedSessionId")

        // If terminateSession() already cleaned up this session, short-circuit.
        // This prevents double-cleanup when a device is force-claimed (terminateSession
        // runs first, then the transport closes and fires this callback).
        val sessionContext = sessionContexts.remove(closedSessionId)
        if (sessionContext == null) {
          Console.log("Session $closedSessionId already cleaned up (e.g., by terminateSession)")
          activeTransports.remove(closedSessionId)
          emitDebugState()
          return@setOnSessionClosed
        }

        // End the Trailblaze session gracefully and cancel running automation
        sessionContext.associatedDeviceId?.let { deviceId ->
          cleanupDeviceOnSessionClose(deviceId, "MCP session closure")
        }

        // Release device claims for this session
        deviceClaimRegistry.releaseAllForSession(closedSessionId)

        // Invoke session closed callback for any custom cleanup
        sessionContext.onSessionClosed?.invoke()

        // Cancel the session's coroutine scope to prevent leaks
        sessionContext.close()

        // Clean up remaining session state
        sessionCreationTimes.remove(closedSessionId)
        sessionServerSessions.remove(closedSessionId)
        activeTransports.remove(closedSessionId)
        sseNotificationChannels.remove(closedSessionId)?.close()
        registeredTrailblazeToolNamesBySession.remove(closedSessionId)
        hostMcpToolRegistryBySession.remove(closedSessionId)

        emitDebugState()
      }

      // Connect the server to the transport — this enables the transport to process requests.
      // The onSessionInitialized callback fires when the client sends 'initialize',
      // at which point tools are registered with the real session context.
      val serverSession = mcpServer.createSession(transport)

      // Store the ServerSession so the callback can access it when it fires
      serverSessionHolder = serverSession

      return transport
    }

    val server = embeddedServer(
      factory = Netty,
      configure = {
        // Increase from 8KB default to handle large proxy headers (e.g. X-Forwarded-Client-Cert)
        maxHeaderSize = 65536
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
              // Check for tool profile override header (sent by MCP proxy for external clients)
              val profileOverride = call.request.headers["X-Tool-Profile"]?.let { header ->
                try {
                  McpToolProfile.valueOf(header.uppercase())
                } catch (_: IllegalArgumentException) {
                  null
                }
              }
              Console.log("[MCP] Creating new session for client (profileOverride=$profileOverride)...")
              createSessionForClient(toolProfileOverride = profileOverride)
            }

            // Set the current MCP session ID for tools to look up the correct session context.
            // asContextElement() propagates the ThreadLocal across coroutine context switches.
            // NOTE: We do NOT set lastActiveMcpSessionId here — that's only for STDIO transport.
            // In HTTP multi-session mode, the thread-local is the only safe mechanism.
            val effectiveMcpSessionId = sessionIdHeader ?: transport.sessionId ?: "unknown"

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

          // Send periodic SSE comment keepalives to prevent idle connection timeouts
          // (Ktor's default ~45s) from killing the stream. The heartbeat sends `: heartbeat`
          // comments which are ignored by SSE clients but keep the TCP connection alive.
          heartbeat {
            period = SSE_HEARTBEAT_PERIOD
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
    Console.log("[MCP] registerTools called. Profile: ${sessionContext.toolProfile}, isMinimal: $isMinimalProfile")
    val toolSetCatalog = TrailblazeToolSetCatalog.defaultEntries(setOfMarkEnabled = true)

    // Get or create the per-session set of registered TrailblazeTool names.
    // Using instance-scoped tracking ensures mode changes can clean up previously expanded toolsets.
    val registeredTrailblazeToolNames = registeredTrailblazeToolNamesBySession
      .getOrPut(mcpSessionId.sessionId) { mutableSetOf() }

    // On re-registration (mode change), remove previously registered TrailblazeTools first
    if (registeredTrailblazeToolNames.isNotEmpty()) {
      mcpServer.removeTools(registeredTrailblazeToolNames.toList())
      registeredTrailblazeToolNames.clear()
    }

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
          deviceClaimRegistry = deviceClaimRegistry,
          toolSetCatalog = toolSetCatalog,
          onActiveToolSetsChanged = onActiveToolSetsChanged,
          onTerminateSession = ::terminateSession,
        ).asTools(),
      )

      // Trail management tool (for test authoring persona)
      val trailLogEmitter = LogEmitter { log ->
        try {
          logsRepo.saveLogToDisk(log)
        } catch (e: Exception) {
          Console.log("[MCP] Warning: Failed to save trail objective log: ${e.message}")
        }
      }
      val activeSessionIdProvider = { mcpBridge.getActiveSessionId() }
      tools(
        TrailMcpTool(
          sessionContext = sessionContext,
          mcpBridge = mcpBridge,
          logEmitter = trailLogEmitter,
          logsRepo = logsRepo,
          sessionIdProvider = activeSessionIdProvider,
        ).asTools(),
      )

      // Configuration tool (query/update settings via MCP)
      tools(
        ConfigToolSet(
          sessionContext = sessionContext,
          mcpBridge = mcpBridge,
        ).asTools(),
      )

      // Session management tool (start/stop with capture, save, browse)
      tools(
        SessionToolSet(
          sessionContext = sessionContext,
          mcpBridge = mcpBridge,
          logsRepo = logsRepo,
          sessionIdProvider = activeSessionIdProvider,
        ).asTools(),
      )

      // Snapshot tool — raw screenshot + view hierarchy, no LLM needed
      val snapshotScreenStateProvider: () -> ScreenState? = {
        val deviceId = McpDeviceContext.currentDeviceId.get()
        try {
          runBlocking(
            deviceId?.let { McpDeviceContext.currentDeviceId.asContextElement(it) }
              ?: EmptyCoroutineContext
          ) {
            ScreenStateCaptureUtil.captureScreenState(mcpBridge)
          }
        } catch (e: Throwable) {
          Console.error("[MCP] snapshot screenStateProvider: FAILED — ${e::class.simpleName}: ${e.message}")
          null
        }
      }
      tools(
        SnapshotToolSet(
          screenStateProvider = snapshotScreenStateProvider,
          sessionContext = sessionContext,
          driverStatusProvider = { mcpBridge.getDriverConnectionStatus() },
          logsRepo = logsRepo,
          sessionIdProvider = activeSessionIdProvider,
          mcpBridge = mcpBridge,
        ).asTools(),
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
        // Note: Inner agent ALWAYS needs tools, even when includePrimitiveTools=false.
        // For WEB/Playwright devices, the bridge returns Playwright-native tool classes
        // (navigate, click, type, etc.) instead of the Maestro-based toolset. This lambda
        // is evaluated per blaze() call so it picks up device changes mid-session.
        val innerAgentToolsProvider = {
          val driverType = mcpBridge.getDriverType()
          // Ask the bridge for device-appropriate built-in tools.
          // For WEB/Playwright: returns Playwright-native toolset (complete replacement for Maestro).
          // For Android/iOS: returns empty → falls back to ALL Maestro tools so that
          // availableToolsProvider() delivers the complete toolset to StepToolSet.
          val bridgeToolClasses = mcpBridge.getInnerAgentBuiltInToolClasses()
          val builtInToolClasses = bridgeToolClasses.ifEmpty {
            ToolSetCategoryMapping.getToolClasses(ToolSetCategory.ALL)
          }
          val customToolClasses = try {
            val appTargetId = mcpBridge.getCurrentAppTargetId()
            val appTarget = if (appTargetId != null) {
              mcpBridge.getAvailableAppTargets().firstOrNull { it.id == appTargetId }
            } else null
            val tools = if (appTarget != null && driverType != null) {
              appTarget.getCustomToolsForDriver(driverType)
            } else emptySet()
            Console.log("[TrailblazeMcpServer] Custom tools for $appTargetId/$driverType: ${tools.map { it.simpleName }}")
            tools
          } catch (e: Exception) {
            Console.log("[TrailblazeMcpServer] Custom tool loading failed: ${e.message}")
            emptySet()
          }
          // Custom tools first so the LLM sees app-specific tools (e.g., sign-in)
          // before generic alternatives (e.g., launchApp), improving selection.
          val allTools = (customToolClasses + builtInToolClasses)
            .mapNotNull { it.toKoogToolDescriptor()?.toTrailblazeToolDescriptor() }
          Console.log("[TrailblazeMcpServer] Inner agent total tools: ${allTools.size} (driver=$driverType, custom: ${customToolClasses.size})")
          allTools
        }
        Console.log("[TrailblazeMcpServer] Inner agent tools provider registered")

        tools(
          StepToolSet(
            screenAnalyzer = screenAnalyzer,
            executor = uiActionExecutor,
            screenStateProvider = {
              // Capture the device ID from the current thread's context before entering
              // runBlocking, which creates a new coroutine scope that doesn't inherit
              // the parent's ThreadLocal-based context element.
              val deviceId = McpDeviceContext.currentDeviceId.get()
              try {
                runBlocking(
                  deviceId?.let { McpDeviceContext.currentDeviceId.asContextElement(it) }
                    ?: EmptyCoroutineContext
                ) {
                  ScreenStateCaptureUtil.captureScreenState(mcpBridge)
                }
              } catch (e: Throwable) {
                Console.error("[MCP] screenStateProvider: FAILED — ${e::class.simpleName}: ${e.message}")
                null
              }
            },
            sessionContext = sessionContext,
            availableToolsProvider = innerAgentToolsProvider,
            logEmitter = trailLogEmitter,
            sessionIdProvider = activeSessionIdProvider,
            driverStatusProvider = { mcpBridge.getDriverConnectionStatus() },
            toolClassesOverrideProvider = { mcpBridge.getInnerAgentToolClasses() },
          ).asTools(),
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
      val sessionRegistry = hostMcpToolRegistryBySession[mcpSessionId.sessionId]
      val allRegisteredNames = (sessionRegistry?.tools ?: emptyList()).map { it.descriptor.name }.toSet()
      val toolsToRemove = allRegisteredNames - allowedTools
      if (toolsToRemove.isNotEmpty()) {
        mcpServer.removeTools(toolsToRemove.toList())
        Console.log("[MCP] MINIMAL profile: removed ${toolsToRemove.size} tools (${toolsToRemove.joinToString()}), keeping: $allowedTools")
      }
    }

    // Register MCP resources
    registerResources(mcpServer, sessionContext, mcpBridge, trailsDirProvider, llmModelListsProvider)

    mcpServer.onClose { }
  }

  /**
   * Auto-connects to a device if there is exactly one Android or iOS device available.
   * Called during session initialization to skip the manual device() handshake.
   *
   * Web browser is always available but not auto-connected — it's a fallback the LLM
   * can choose explicitly via device(action=WEB).
   */
  private suspend fun autoConnectSingleDevice(
    sessionContext: TrailblazeMcpSessionContext,
  ) {
    // Skip if the session already has a device (explicit device() call won the race)
    if (sessionContext.associatedDeviceId != null) {
      Console.log("[MCP] Auto-connect skipped: session already has device ${sessionContext.associatedDeviceId?.instanceId}")
      return
    }
    try {
      val devices = mcpBridge.getAvailableDevices()
      val mobileDevices = devices
        .filter {
          it.platform == TrailblazeDevicePlatform.ANDROID ||
            it.platform == TrailblazeDevicePlatform.IOS
        }
        // Deduplicate by instanceId+platform (multiple driver types for same device)
        .groupBy { it.instanceId to it.platform }
        .map { (_, variants) ->
          val platform = variants.first().platform
          val configuredType = mcpBridge.getConfiguredDriverType(platform)
          if (configuredType != null) {
            variants.find { it.trailblazeDriverType == configuredType } ?: variants.first()
          } else {
            variants.first()
          }
        }

      if (mobileDevices.size == 1) {
        val device = mobileDevices.first()
        Console.log("[MCP] Auto-connecting to single ${device.platform.displayName} device: ${device.instanceId}")

        val deviceId = device.trailblazeDeviceId
        val mcpSessionId = sessionContext.mcpSessionId.sessionId
        deviceClaimRegistry.claim(deviceId, mcpSessionId, force = false)

        try {
          mcpBridge.selectDevice(deviceId)
        } catch (e: Exception) {
          deviceClaimRegistry.release(deviceId, mcpSessionId)
          throw e
        }

        sessionContext.setAssociatedDevice(deviceId)
        // Session creation deferred to first blaze/ask call for meaningful naming.
        sessionContext.startImplicitRecording()
        Console.log("[MCP] Auto-connected to ${device.instanceId} (${device.platform.displayName})")
      } else {
        Console.log("[MCP] Auto-connect skipped: ${mobileDevices.size} mobile devices found (need exactly 1)")
      }
    } catch (e: Exception) {
      Console.log("[MCP] Auto-connect failed (non-fatal): ${e::class.simpleName}: ${e.message}")
    }
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
   *       "args": ["mcp"]
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
      mode = defaultMode,
    )
    sessionContexts[mcpSessionId.sessionId] = sessionContext
    sessionCreationTimes[mcpSessionId.sessionId] = System.currentTimeMillis()

    // Register tools BEFORE creating the session/transport to avoid a race condition.
    // The MCP client can send tool calls immediately after the initialize handshake,
    // so tools must already be registered when the session goes live.
    // (The HTTP server avoids this by registering in onSessionInitialized, which fires
    // after handshake but before tool calls are accepted.)
    lastActiveMcpSessionId = mcpSessionId.sessionId
    try {
      registerTools(mcpServer, mcpSessionId, sessionContext)
    } catch (e: Exception) {
      Console.error("[MCP STDIO] FATAL: Failed to register tools: ${e::class.simpleName}: ${e.message}")
      throw e
    }

    // Auto-connect to single mobile device before going live
    autoConnectSingleDevice(sessionContext)

    val transport = StdioServerTransport(
      System.`in`.asSource().buffered(),
      stdout.asSink().buffered(),
    )

    val session = mcpServer.createSession(transport)
    sessionContext.mcpServerSession = session

    Console.log("STDIO MCP server ready — waiting for JSON-RPC on stdin")

    val done = Job()
    session.onClose {
      Console.error("[MCP STDIO] Session closing — STDIO transport connection ended")
      Console.error("[MCP STDIO]   Session: ${mcpSessionId.sessionId}")
      Console.error("[MCP STDIO]   Associated device: ${sessionContext.associatedDeviceId?.instanceId ?: "none"}")
      Thread.currentThread().let { t ->
        Console.error("[MCP STDIO]   Close triggered on thread: ${t.name} (id=${t.id})")
      }
      // End session gracefully and cancel running automation for this STDIO session
      sessionContext.associatedDeviceId?.let { deviceId ->
        cleanupDeviceOnSessionClose(deviceId, "STDIO session closure")
      }
      deviceClaimRegistry.releaseAllForSession(mcpSessionId.sessionId)

      sessionContext.onSessionClosed?.invoke()
      sessionContext.close()

      sessionContexts.remove(mcpSessionId.sessionId)
      sessionCreationTimes.remove(mcpSessionId.sessionId)
      registeredTrailblazeToolNamesBySession.remove(mcpSessionId.sessionId)
      hostMcpToolRegistryBySession.remove(mcpSessionId.sessionId)
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

  private fun cleanupDeviceOnSessionClose(
    deviceId: TrailblazeDeviceId,
    closeReason: String,
  ) {
    Console.log("Ending session and cancelling automation on device ${deviceId.instanceId} due to $closeReason")

    // End the Trailblaze session first so it gets a clean Ended status in the report.
    // Bind the per-session device ID so getActiveSessionId/endSession target the right device.
    val deviceContextElement = McpDeviceContext.currentDeviceId.asContextElement(deviceId)
    try {
      runBlocking(deviceContextElement) {
        // Only end the session if one was actually created (session creation is deferred
        // to the first blaze/ask call, so a connect-then-disconnect has no session to end).
        if (mcpBridge.getActiveSessionId() != null) {
          withTimeout(SESSION_END_TIMEOUT_MS) { mcpBridge.endSession() }
        }
      }
    } catch (e: Exception) {
      Console.error("Error ending session: ${e.message}")
    }

    try {
      mcpBridge.cancelAutomation(deviceId)
    } catch (e: Exception) {
      Console.error("Error cancelling automation: ${e.message}")
    }

    mcpBridge.releasePersistentDeviceConnection(deviceId)
  }

  private suspend fun resolveSessionIdForLog(
    toolName: String,
    args: JsonObject,
    fallbackMcpSessionId: String,
  ): SessionId {
    // Defer session creation to the first blaze/ask call so the session is named
    // after the first objective (e.g., "Tap the login button") rather than a generic name.
    val shouldEnsureSession = toolName == McpToolProfile.TOOL_BLAZE ||
      toolName == McpToolProfile.TOOL_ASK

    val sessionId = if (shouldEnsureSession) {
      val testName = when (toolName) {
        McpToolProfile.TOOL_BLAZE ->
          (args["goal"] as? JsonPrimitive)?.content?.trim()?.take(80)
        McpToolProfile.TOOL_ASK ->
          (args["question"] as? JsonPrimitive)?.content?.trim()?.take(80)
        else -> null
      }?.takeIf { it.isNotBlank() }
      // This must run BEFORE the request log so the session start log is first.
      mcpBridge.ensureSessionAndGetId(testName)
    } else {
      mcpBridge.getActiveSessionId()
    }

    return sessionId ?: SessionId(fallbackMcpSessionId)
  }

  /**
   * Detects structured JSON error payloads from tools that return JSON with an "error" field
   * (e.g., ConfigToolSet returns `{"success":false,"error":"Unknown config key"}`).
   * Returns false for non-JSON strings or JSON without a non-null "error" field.
   */
  private fun hasJsonErrorField(message: String): Boolean {
    if (!message.startsWith("{")) return false
    return try {
      val obj = Json.parseToJsonElement(message) as? JsonObject ?: return false
      val errorValue = obj["error"]?.jsonPrimitive?.contentOrNull
      !errorValue.isNullOrEmpty()
    } catch (_: Exception) {
      false
    }
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
      "WEB" -> devices.find { it.platform == TrailblazeDevicePlatform.WEB }
      else -> null
    }

    if (targetDevice != null) {
      mcpBridge.selectDevice(targetDevice.trailblazeDeviceId)
    }
  }
}
