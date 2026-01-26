package xyz.block.trailblaze.logs.server

import ai.koog.agents.core.tools.Tool
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.core.tools.ToolResult
import ai.koog.agents.core.tools.annotations.InternalAgentToolsApi
import ai.koog.agents.core.tools.reflect.asTools
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.McpJson
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.server.StreamableHttpServerTransport
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.RequestId
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
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

    // Create MCP server and transport BEFORE starting HTTP server
    val mcpServer: Server = configureMcpServer()

    // Create official SDK transport with JSON response mode (Streamable HTTP)
    val transport = StreamableHttpServerTransport(
      enableJsonResponse = true,
    )

    // Session context will be created when we get the real session ID from the SDK
    var sessionContext: TrailblazeMcpSessionContext? = null
    var mcpSessionId: McpSessionId? = null

    // Set up session callbacks to capture and track the session
    transport.setOnSessionInitialized { generatedSessionId ->
      mcpSessionId = McpSessionId(generatedSessionId)
      sessionContext = TrailblazeMcpSessionContext(
        mcpServerSession = null,
        mcpSessionId = mcpSessionId!!,
      )
      sessionContexts[generatedSessionId] = sessionContext!!
      sessionCreationTimes[generatedSessionId] = System.currentTimeMillis()
    }

    transport.setOnSessionClosed { closedSessionId ->
      sessionContexts.remove(closedSessionId)
      sessionCreationTimes.remove(closedSessionId)
    }

    // Create server session BEFORE starting the HTTP server
    runBlocking {
      val mcpServerSession = mcpServer.createSession(transport)

      val ctx = sessionContext
      val sessId = mcpSessionId
      if (ctx != null && sessId != null) {
        ctx.mcpServerSession = mcpServerSession
        registerTools(mcpServer, sessId, ctx)
      } else {
        // Fallback: create session context with transport's session ID
        val fallbackSessionId = transport.sessionId ?: "fallback-${System.currentTimeMillis()}"
        val fallbackMcpSessionId = McpSessionId(fallbackSessionId)
        val fallbackContext = TrailblazeMcpSessionContext(
          mcpServerSession = mcpServerSession,
          mcpSessionId = fallbackMcpSessionId,
        )
        sessionContexts[fallbackSessionId] = fallbackContext
        sessionCreationTimes[fallbackSessionId] = System.currentTimeMillis()
        registerTools(mcpServer, fallbackMcpSessionId, fallbackContext)
      }
    }
    val server = embeddedServer(
      factory = Netty,
      configure = {
        configureForSelfSignedSsl(
          requestedHttpPort = port,
          requestedHttpsPort = 8443,
        )
      },
    ) {
      // Install ContentNegotiation with McpJson for MCP type serialization
      install(ContentNegotiation) {
        json(McpJson)
      }

      logsServerKtorEndpoints(logsRepo, homeCallbackHandler = homeCallbackHandler, installContentNegotiation = false)
      routing {
        // POST /mcp - Main JSON-RPC request endpoint
        post("/mcp") {
          withContext(Dispatchers.IO) {
            transport.handlePostRequest(null, call)
          }
        }

        // GET /mcp - Streaming endpoint
        get("/mcp") {
          withContext(Dispatchers.IO) {
            transport.handleGetRequest(null, call)
          }
        }

        // DELETE /mcp - Session termination
        delete("/mcp") {
          withContext(Dispatchers.IO) {
            transport.handleDeleteRequest(call)
          }
        }
      }
    }.start(wait = wait)
    return server
  }

  /**
   * Registers all tools with the MCP server.
   */
  @OptIn(InternalAgentToolsApi::class)
  private fun registerTools(
    mcpServer: Server,
    mcpSessionId: McpSessionId,
    sessionContext: TrailblazeMcpSessionContext,
  ) {
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
    } + additionalToolsProvider(sessionContext, mcpServer)

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

    mcpServer.onClose { }
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
