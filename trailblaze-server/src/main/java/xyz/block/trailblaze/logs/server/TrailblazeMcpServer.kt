package xyz.block.trailblaze.logs.server

import ai.koog.agents.core.tools.Tool
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.core.tools.ToolResult
import ai.koog.agents.core.tools.annotations.InternalAgentToolsApi
import ai.koog.agents.core.tools.reflect.asTools
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.install
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.request.header
import io.ktor.server.response.respond
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.server.ServerSession
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.RequestId
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import xyz.block.trailblaze.logs.client.TrailblazeJsonInstance
import xyz.block.trailblaze.logs.server.ServerEndpoints.logsServerKtorEndpoints
import xyz.block.trailblaze.logs.server.SslConfig.configureForSelfSignedSsl
import xyz.block.trailblaze.mcp.TrailblazeMcpBridge
import xyz.block.trailblaze.mcp.TrailblazeMcpSessionContext
import xyz.block.trailblaze.mcp.models.McpSessionId
import xyz.block.trailblaze.mcp.newtools.DeviceManagerToolSet
import xyz.block.trailblaze.mcp.newtools.TrailFilesToolSet
import xyz.block.trailblaze.mcp.transport.MCP_SESSION_ID_HEADER
import xyz.block.trailblaze.mcp.transport.StreamableHttpServerTransport
import xyz.block.trailblaze.mcp.utils.KoogToMcpExt.toMcpJsonSchemaObject
import xyz.block.trailblaze.mcp.utils.TrailblazeToolToMcpBridge
import xyz.block.trailblaze.model.TrailblazeHostAppTarget
import xyz.block.trailblaze.report.utils.LogsRepo
import xyz.block.trailblaze.toolcalls.TrailblazeToolSet
import java.io.File
import java.util.concurrent.ConcurrentHashMap

class TrailblazeMcpServer(
  val logsRepo: LogsRepo,
  val mcpBridge: TrailblazeMcpBridge,
  val trailsDirProvider: () -> File,
  val targetTestAppProvider: () -> TrailblazeHostAppTarget,
  val homeCallbackHandler: ((parameters: Map<String, List<String>>) -> Result<String>)? = null,
  val additionalToolsProvider: (TrailblazeMcpSessionContext, Server) -> ToolRegistry = { _, _ -> ToolRegistry {} },
) {
  // Per-session progress token tracking - use String keys for reliable ConcurrentHashMap behavior
  private val sessionContexts = ConcurrentHashMap<String, TrailblazeMcpSessionContext>()

  // Track session creation time for timing diagnostics
  private val sessionCreationTimes = ConcurrentHashMap<String, Long>()

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

        // Extract progress token from request metadata
        val progressToken = request.meta?.get("progressToken")?.let { progressTokenValue ->
          when (progressTokenValue) {
            is JsonPrimitive -> RequestId.StringId(progressTokenValue.content)
            else -> null
          }
        }

        // Store progress token for this session
        sessionContexts[mcpSessionId.sessionId]?.progressToken = progressToken

        val toolName = tool.descriptor.name

        @Suppress("UNCHECKED_CAST")
        val koogTool = newToolRegistry.getTool(toolName)

        // Convert request arguments to JsonObject
        val argumentsJsonObject = when (val args = request.arguments) {
          null -> JsonObject(emptyMap())
          else -> args
        }

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

          CallToolResult(
            content = mutableListOf(
              TextContent(toolResponseMessage),
            ),
            isError = false,  // Explicitly set to false for success (some MCP clients require this)
          )
        } catch (e: Exception) {
          // Return error result instead of throwing - allows LLM to see the error
          CallToolResult(
            content = mutableListOf(
              TextContent("Error: ${e.message}"),
            ),
            isError = true,
          )
        }
      }
    }
  }

  /**
   * MCP Server using Streamable HTTP transport.
   *
   * This replaces the previous SSE-based implementation with a simpler HTTP-based approach:
   * - Client sends JSON-RPC requests via HTTP POST to /mcp
   * - Server processes request and returns response in the same HTTP connection
   * - Session management via Mcp-Session-Id header
   * - Optional GET /mcp for server-to-client streaming (notifications)
   *
   * @param port The port to listen on (default: 52525)
   * @param wait Whether to block until server stops (default: false)
   * @return The embedded server instance
   */
  fun startStreamableHttpMcpServer(
    port: Int = 52525,
    wait: Boolean = false,
  ): EmbeddedServer<*, *> {
    println("═══════════════════════════════════════════════════════════")
    println("Starting Trailblaze MCP Server (Streamable HTTP)")
    println("  Port: $port")
    println("  MCP endpoint: http://localhost:$port/mcp")
    println()
    println("Connection flow:")
    println("  1. Client sends POST to /mcp with JSON-RPC request")
    println("  2. Server creates session (if new) and returns Mcp-Session-Id header")
    println("  3. Client includes Mcp-Session-Id header in subsequent requests")
    println("  4. Optional: GET /mcp for server-to-client streaming")
    println("═══════════════════════════════════════════════════════════")
    println("Will Wait: $wait")

    val server = embeddedServer(
      factory = Netty,
      configure = {
        configureForSelfSignedSsl(
          requestedHttpPort = port,
          requestedHttpsPort = 8443, // Default HTTPS port, can be changed
        )
      },
    ) {
      logsServerKtorEndpoints(logsRepo, homeCallbackHandler = homeCallbackHandler)
      routing {
        // Main MCP endpoint for JSON-RPC requests
        post("/mcp") {

          println("==============================================================")
          println("======================= POST /mcp =======================")
          println("==============================================================")
          println("Server instance: ${this@TrailblazeMcpServer.hashCode()}")
          println("sessionContexts instance: ${sessionContexts.hashCode()}, size: ${sessionContexts.size}")
          println("sessionContexts keys: ${sessionContexts.keys}")
          val clientSessionId = call.request.header(MCP_SESSION_ID_HEADER)
          println("Client provided session ID: '$clientSessionId'")

          // Check if this is an existing session or new session
          // For Streamable HTTP, the client may provide its own session ID
          val (mcpSessionId, sessionContext, isNewSession) = if (clientSessionId != null) {
            val existingContext = sessionContexts[clientSessionId]
            println("Lookup result for '$clientSessionId': ${if (existingContext != null) "FOUND" else "NOT FOUND"}")

            if (existingContext != null) {
              println("✓ Reusing existing session: $clientSessionId")
              Triple(McpSessionId(clientSessionId), existingContext, false)
            } else {
              // Client sent a session ID but we don't have it - create new session with that ID
              println("⚠ Client provided unknown session ID: '$clientSessionId' - creating new session")
              println("  Known sessions: ${sessionContexts.keys.map { "'$it'" }}")
              val (newSessionId, newContext) = createNewSession(clientSessionId)
              Triple(newSessionId, newContext, true)
            }
          } else {
            // New session - create transport and context with server-generated ID
            println("→ No session ID provided - creating new session")
            val (newSessionId, newContext) = createNewSession(null)
            Triple(newSessionId, newContext, true)
          }

          if (isNewSession) {
            println("✓ New MCP session created: ${mcpSessionId.sessionId}")
          }

          val transport = sessionContext.transport
          if (transport == null) {
            call.respond(HttpStatusCode.InternalServerError, "Transport not initialized")
            return@post
          }

          // Handle the request through the transport
          withContext(Dispatchers.IO) {
            println("Handling request in IO context")
            transport.handleRequest(call)
          }
        }

        // Optional GET endpoint for server-to-client streaming (notifications)
        get("/mcp") {
          println("==============================================================")
          println("======================= GET /mcp =======================")
          println("==============================================================")
          val clientSessionId = call.request.header(MCP_SESSION_ID_HEADER)

          println("==============================================================")
          println("Client provided session ID: $clientSessionId")
          println("==============================================================")
          if (clientSessionId == null) {
            call.respond(HttpStatusCode.BadRequest, "Missing $MCP_SESSION_ID_HEADER header")
            println("Missing $MCP_SESSION_ID_HEADER header")
            return@get
          }

          val sessionContext = sessionContexts[clientSessionId]

          if (sessionContext == null) {
            call.respond(HttpStatusCode.NotFound, "Session not found: $clientSessionId")
            println("Session not found: $clientSessionId")
            return@get
          }

          val transport = sessionContext?.transport
          if (transport == null) {
            call.respond(HttpStatusCode.InternalServerError, "Transport not initialized")
            println("Transport not initialized")
            return@get
          }

          // Stream notifications to client
          withContext(Dispatchers.IO) {
            println("Handling stream request in IO context")
            transport.handleStreamRequest(call)
          }
        }

        // DELETE endpoint for session termination
        delete("/mcp") {
          val clientSessionId = call.request.header(MCP_SESSION_ID_HEADER)

          if (clientSessionId == null) {
            call.respond(HttpStatusCode.BadRequest, "Missing $MCP_SESSION_ID_HEADER header")
            return@delete
          }

          val sessionContext = sessionContexts.remove(clientSessionId)
          sessionCreationTimes.remove(clientSessionId)

          if (sessionContext != null) {
            sessionContext.transport?.close()
            println("✓ Session terminated: $clientSessionId")
            call.respond(HttpStatusCode.OK, "Session terminated")
          } else {
            call.respond(HttpStatusCode.NotFound, "Session not found: $clientSessionId")
          }
        }
      }
    }.start(wait = wait)
    println("Server starting...")
    return server
  }

  /**
   * Creates a new MCP session with transport and registers tools.
   * For Streamable HTTP, we set up the session synchronously so it's ready to handle requests immediately.
   *
   * @param clientProvidedSessionId Optional session ID provided by the client. If null, uses the transport's generated ID.
   */
  private suspend fun createNewSession(clientProvidedSessionId: String?): Pair<McpSessionId, TrailblazeMcpSessionContext> {
    val mcpServer: Server = configureMcpServer()
    val transport = StreamableHttpServerTransport()

    // Use client-provided session ID if available, otherwise use transport's generated ID
    val sessionIdString = clientProvidedSessionId ?: transport.sessionId

    // If client provided a session ID, configure the transport to use it
    if (clientProvidedSessionId != null) {
      transport.useSessionId(clientProvidedSessionId)
    }

    val mcpSessionId = McpSessionId(sessionIdString)

    val sessionStartTime = System.currentTimeMillis()
    sessionCreationTimes[sessionIdString] = sessionStartTime

    // Create session context
    val sessionContext = TrailblazeMcpSessionContext(
      mcpServerSession = null as ServerSession?, // Will be set after connect
      mcpSessionId = mcpSessionId,
    )
    sessionContext.transport = transport

    sessionContexts[sessionIdString] = sessionContext
    println("  → Session stored in sessionContexts with key: $sessionIdString")
    println("  → Current sessions: ${sessionContexts.keys}")

    // Launch connect() in background - it wires up message handlers and runs for session lifetime
    // Note: Do NOT call transport.start() here - mcpServer.connect() calls it internally via Protocol.connect()
    CoroutineScope(Dispatchers.Default).launch {
      try {
        val mcpServerSession = mcpServer.connect(transport)
        sessionContext.mcpServerSession = mcpServerSession
        println("  ✓ Session $sessionIdString fully initialized")
      } catch (e: Exception) {
        // Remove failed session
        println("  ✗ Session $sessionIdString failed to initialize: ${e.message}")
        e.printStackTrace()
        sessionContexts.remove(sessionIdString)
        sessionCreationTimes.remove(sessionIdString)
      }
    }

    // Wait for the transport to be ready to handle messages
    transport.waitForReady(2000)

    // Register initial tools - use sessionContext directly instead of getSessionContext()
    // to avoid potential ConcurrentHashMap lookup issues with value class keys
    val initialToolRegistry = ToolRegistry.Companion {
      tools(
        TrailFilesToolSet(
          trailsDirProvider = trailsDirProvider,
        ).asTools(TrailblazeJsonInstance),
      )
      tools(
        DeviceManagerToolSet(
          sessionContext = sessionContext,
          toolRegistryUpdated = { updatedToolRegistry ->
            addToolsAsMcpToolsFromRegistry(
              newToolRegistry = updatedToolRegistry,
              mcpServer = mcpServer,
              mcpSessionId = mcpSessionId,
            )
          },
          targetTestAppProvider = targetTestAppProvider,
          mcpBridge = mcpBridge,
        ).asTools(TrailblazeJsonInstance),
      )
    } + additionalToolsProvider(
      sessionContext,
      mcpServer,
    )

    addToolsAsMcpToolsFromRegistry(
      newToolRegistry = initialToolRegistry,
      mcpServer = mcpServer,
      mcpSessionId = mcpSessionId,
    )

    // Register TrailblazeTools (low-level device control tools) via the bridge
    val trailblazeToolBridge = TrailblazeToolToMcpBridge(
      mcpBridge = mcpBridge,
      sessionContext = sessionContext,
    )
    trailblazeToolBridge.registerTrailblazeToolSet(
      trailblazeToolSet = TrailblazeToolSet.DeviceControlTrailblazeToolSet,
      mcpServer = mcpServer,
      mcpSessionId = mcpSessionId,
    )

    // For Streamable HTTP, onClose is called when Server.connect() completes.
    // We don't remove the session here - sessions are managed via DELETE /mcp endpoint.
    mcpServer.onClose { }

    return Pair(mcpSessionId, sessionContext)
  }

  /**
   * @deprecated Use [startStreamableHttpMcpServer] instead. SSE transport has been deprecated by LLM providers.
   */
  @Deprecated(
    message = "SSE transport has been deprecated. Use startStreamableHttpMcpServer() instead.",
    replaceWith = ReplaceWith("startStreamableHttpMcpServer(port, wait)"),
  )
  fun startSseMcpServer(
    port: Int = 52525,
    wait: Boolean = false,
  ): EmbeddedServer<*, *> = startStreamableHttpMcpServer(port, wait)
}
