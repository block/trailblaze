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
import io.ktor.server.request.queryString
import io.ktor.server.response.respond
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.sse.SSE
import io.ktor.server.sse.sse
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.server.ServerSession
import io.modelcontextprotocol.kotlin.sdk.server.SseServerTransport
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.RequestId
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import xyz.block.trailblaze.logs.client.TrailblazeJsonInstance
import xyz.block.trailblaze.logs.server.ServerEndpoints.logsServerKtorEndpoints
import xyz.block.trailblaze.logs.server.SslConfig.configureForSelfSignedSsl
import xyz.block.trailblaze.mcp.TrailblazeMcpSseSessionContext
import xyz.block.trailblaze.mcp.models.McpSseSessionId
import xyz.block.trailblaze.mcp.newtools.AndroidOnDeviceFromHostToolSet
import xyz.block.trailblaze.mcp.newtools.TrailFilesToolSet
import xyz.block.trailblaze.mcp.utils.KoogToMcpExt.toMcpJsonSchemaObject
import xyz.block.trailblaze.model.TrailblazeHostAppTarget
import xyz.block.trailblaze.report.utils.LogsRepo
import java.io.File
import java.util.concurrent.ConcurrentHashMap

class TrailblazeMcpServer(
  val logsRepo: LogsRepo,
  val trailsDirProvider: () -> File,
  val targetTestAppProvider: () -> TrailblazeHostAppTarget,
  val homeCallbackHandler: ((parameters: Map<String, List<String>>) -> Result<String>)? = null,
  val additionalToolsProvider: (TrailblazeMcpSseSessionContext, Server) -> ToolRegistry = { _, _ -> ToolRegistry {} },
) {
  // Per-session progress token tracking (multiplatform compatible)
  private val sessionContexts = ConcurrentHashMap<McpSseSessionId, TrailblazeMcpSseSessionContext>()

  // Track session creation time for timing diagnostics
  private val sessionCreationTimes = ConcurrentHashMap<McpSseSessionId, Long>()

  var hostMcpToolRegistry = ToolRegistry.Companion {}

  fun getSessionContext(mcpSseSessionId: McpSseSessionId): TrailblazeMcpSseSessionContext? =
    sessionContexts[mcpSseSessionId]

  @OptIn(InternalAgentToolsApi::class)
  fun configureMcpServer(): Server {
    println("configureMcpServer()")
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
    mcpSseSessionId: McpSseSessionId,
  ) {
    println("Adding These Tools: ${newToolRegistry.tools.map { it.descriptor.name }}")
    hostMcpToolRegistry = hostMcpToolRegistry.plus(newToolRegistry)

    newToolRegistry.tools.forEach { tool: Tool<*, *> ->
      // Build properties JsonObject directly (following Koog pattern)
      val properties = buildJsonObject {
        (tool.descriptor.requiredParameters + tool.descriptor.optionalParameters).forEach { param ->
          put(param.name, param.toMcpJsonSchemaObject())
        }
      }

      val required = tool.descriptor.requiredParameters.map { it.name }

      println("Registering ${tool.descriptor.name} for session $mcpSseSessionId")
      println("  Properties: $properties")
      println("  Required: $required")

      // Use empty ToolSchema for tools with no parameters (following Koog pattern)
      val inputSchema = if (properties.isEmpty()) {
        ToolSchema()
      } else {
        ToolSchema(properties, required)
      }

      mcpServer.addTool(
        name = tool.descriptor.name,
        description = tool.descriptor.description,
        inputSchema = inputSchema,
      ) { request: CallToolRequest ->

        // In 0.8.x, CallToolRequest.meta is nullable and accessed via the meta property
        val progressToken = request.meta?.get("progressToken")?.let { progressTokenValue ->
          when (progressTokenValue) {
            is JsonPrimitive -> {
              val tokenString = progressTokenValue.content
              println("progressToken for session $mcpSseSessionId = $tokenString")
              println("progressToken isString = ${progressTokenValue.isString}")
              RequestId.StringId(tokenString)
            }

            else -> null
          }
        }

        // Store progress token for this session (multiplatform compatible)
        sessionContexts[mcpSseSessionId]?.progressToken = progressToken

        val toolName = tool.descriptor.name
        println("Tool Called: $toolName")

        @Suppress("UNCHECKED_CAST")
        val koogTool = newToolRegistry.getTool(toolName)

        // In 0.8.x, request.arguments is JsonElement?, not JsonObject?
        // Safely convert to JsonObject, handling null and non-object types
        val argumentsJsonObject = when (val args = request.arguments) {
          null -> JsonObject(emptyMap())
          else -> args
        }

        println("Deserializing arguments for tool: $toolName")
        println("  Arguments JSON: $argumentsJsonObject")
        println("  Serializer: ${koogTool.argsSerializer}")

        val koogToolArgs = try {
          println("  → Attempting deserialization...")
          val result = TrailblazeJsonInstance.decodeFromJsonElement(
            koogTool.argsSerializer,
            argumentsJsonObject,
          )
          println("  ✓ Deserialization successful")
          println("  Deserialized arguments: $result")
          result
        } catch (e: NoSuchFieldError) {
          println("✗ KOTLIN REFLECTION ERROR during deserialization!")
          println("  This indicates a Kotlin version mismatch between runtime and reflection libraries")
          println("  Error: ${e.message}")
          println("  Serializer type: ${koogTool.argsSerializer::class.qualifiedName}")
          println("  Kotlin runtime version: ${KotlinVersion.CURRENT}")
          println()
          println("  The error 'CONTEXT' field not found suggests:")
          println("    - kotlin-reflect library version doesn't match Kotlin runtime")
          println("    - CONTEXT parameters were added in Kotlin 1.6.20")
          println("    - Check your Gradle dependencies for version alignment")
          e.printStackTrace()
          throw e
        } catch (e: Exception) {
          println("✗ ERROR deserializing arguments for tool $toolName")
          println("  Arguments: $argumentsJsonObject")
          println("  Expected type: ${koogTool.argsSerializer.descriptor}")
          println("  Error type: ${e::class.qualifiedName}")
          println("  Error message: ${e.message}")
          e.printStackTrace()
          throw e
        }

        println("Executing tool: $toolName with arguments: $koogToolArgs")

        // Execute tool in background thread to prevent UI blocking
        val toolResponse = withContext(Dispatchers.IO) {
          @OptIn(InternalAgentToolsApi::class)
          koogTool.executeUnsafe(args = koogToolArgs)
        }

        val toolResponseMessage = when (toolResponse) {
          is ToolResult -> toolResponse.toStringDefault()
          else -> toolResponse.toString()
        }
        println("Tool result toolResponseMessage: $toolResponseMessage")

        CallToolResult(
          content = mutableListOf(
            TextContent(toolResponseMessage),
          ),
        )
      }
    }
  }

  /** MCP Server using Koog [io.modelcontextprotocol.kotlin.sdk.Tool]s */
  fun startSseMcpServer(
    port: Int = 52525,
    wait: Boolean = false,
  ): EmbeddedServer<*, *> {
    println("═══════════════════════════════════════════════════════════")
    println("Starting Trailblaze MCP SSE Server")
    println("  Port: $port")
    println("  SSE endpoint: http://localhost:$port/sse")
    println("  Message endpoint: http://localhost:$port/message?sessionId=<session_id>")
    println()
    println("Connection flow:")
    println("  1. Client connects to /sse endpoint")
    println("  2. Server creates session and sends session ID via SSE")
    println("  3. Client uses session ID for /message endpoint")
    println("  4. Session remains valid while SSE connection is active")
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
      install(SSE)
      routing {
        sse("/sse") {
          val sseServerSession = this
          // Job to keep the SSE connection alive until the session is closed
          val sessionJob = kotlinx.coroutines.Job()

          // Declare session ID outside try block so it's accessible in finally
          var mcpSseSessionId: McpSseSessionId? = null

          withContext(Dispatchers.IO) {
            try {
              val mcpSseServer: Server = configureMcpServer()
              val transport = SseServerTransport("/message", sseServerSession)
              mcpSseSessionId = McpSseSessionId(transport.sessionId)
              println("═══════════════════════════════════════════════════════════")
              println("✓ NEW SSE Connection established")
              println("  Session ID: $mcpSseSessionId")
              println("  Message endpoint: /message?sessionId=${transport.sessionId}")
              println("  Query string: ${sseServerSession.call.request.queryString()}")
              println("═══════════════════════════════════════════════════════════")
              // For SSE, you can also add prompts/tools/resources if needed:
              // server.addTool(...), server.addPrompt(...), server.addResource(...)

              // Pre-create and register session context BEFORE connect() sends SSE events
              // Store the transport immediately so POSTs can be handled during connect()
              println("→ Pre-registering session context (before connect sends SSE events)")
              val sessionStartTime = System.currentTimeMillis()
              sessionCreationTimes[mcpSseSessionId] = sessionStartTime

              // Create a temporary ServerSession-like object that holds the transport
              // This allows POST requests to be processed while connect() is still running
              val tempSessionContext = TrailblazeMcpSseSessionContext(
                mcpServerSession = null as ServerSession?, // Will be set after connect
                mcpSseSessionId = mcpSseSessionId,
              )
              // Store the transport reference for immediate use
              tempSessionContext.sseTransport = transport

              sessionContexts[mcpSseSessionId] = tempSessionContext
              println("✓ Session pre-registered with transport: $mcpSseSessionId (Total active sessions: ${sessionContexts.size})")

              // Launch connect() asynchronously to avoid blocking the SSE handler
              // This prevents deadlock where connect() waits for events on the same dispatcher we're blocking
              println("→ Launching MCP server connection asynchronously for session: $mcpSseSessionId")
              val connectStartTime = System.currentTimeMillis()

              kotlinx.coroutines.CoroutineScope(Dispatchers.Default).launch {
                try {
                  println("  → connect() starting in background coroutine...")

                  // Add timeout to detect if connect() hangs indefinitely
                  val mcpServerSession = kotlinx.coroutines.withTimeout(10000) {
                    mcpSseServer.connect(transport)
                  }

                  val connectDuration = System.currentTimeMillis() - connectStartTime
                  println("  → connect() completed in ${connectDuration}ms, updating session context...")

                  // Update the session context with the actual ServerSession (this makes it available to POST handler)
                  tempSessionContext.mcpServerSession = mcpServerSession
                  println("✓ Session context fully initialized: $mcpSseSessionId")

                  if (connectDuration > 100) {
                    println("⚠️  WARNING: connect() took ${connectDuration}ms (>100ms). Client may have timed out.")
                  }
                } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                  println("✗ TIMEOUT in connect() for session $mcpSseSessionId after 10 seconds")
                  println("  This indicates connect() is waiting for something that never arrives.")
                  println("  Possible causes: waiting for client response, deadlock, or slow initialization")
                  // Remove failed session
                  sessionContexts.remove(mcpSseSessionId)
                  sessionCreationTimes.remove(mcpSseSessionId)
                } catch (e: Exception) {
                  println("✗ ERROR in connect() for session $mcpSseSessionId: ${e.message}")
                  e.printStackTrace()
                  // Remove failed session
                  sessionContexts.remove(mcpSseSessionId)
                  sessionCreationTimes.remove(mcpSseSessionId)
                }
              }

              println("✓ Session setup launched, connect() running in background")

              val initialToolRegistry = ToolRegistry.Companion {
                tools(
                  TrailFilesToolSet(
                    trailsDirProvider = trailsDirProvider,
                  ).asTools(TrailblazeJsonInstance)
                )
                tools(
                  AndroidOnDeviceFromHostToolSet(
                    sessionContext = getSessionContext(mcpSseSessionId),
                    toolRegistryUpdated = { updatedToolRegistry ->
                      addToolsAsMcpToolsFromRegistry(
                        newToolRegistry = updatedToolRegistry,
                        mcpServer = mcpSseServer,
                        mcpSseSessionId = mcpSseSessionId,
                      )
                    },
                    targetTestAppProvider = targetTestAppProvider,
                  ).asTools(TrailblazeJsonInstance),
                )
              } + additionalToolsProvider(
                getSessionContext(mcpSseSessionId)!!,
                mcpSseServer,
              )

              addToolsAsMcpToolsFromRegistry(
                newToolRegistry = initialToolRegistry,
                mcpServer = mcpSseServer,
                mcpSseSessionId = mcpSseSessionId,
              )

              println("✓ Session $mcpSseSessionId is fully initialized and ready for use")
              println("  Active sessions: ${sessionContexts.size}")

              mcpSseServer.onClose {
                println("═══════════════════════════════════════════════════════════")
                println("✗ SSE Connection closed for session: $mcpSseSessionId")
                println("  Reason: onClose callback triggered")
                val wasRemoved = sessionContexts.remove(mcpSseSessionId) != null
                sessionCreationTimes.remove(mcpSseSessionId) // Clean up timing map
                println("  Session was${if (wasRemoved) "" else " NOT"} in sessionContexts")
                println("  Remaining active sessions: ${sessionContexts.size}")
                if (sessionContexts.isNotEmpty()) {
                  println("  Active session IDs: ${sessionContexts.keys.joinToString(", ")}")
                }
                println("═══════════════════════════════════════════════════════════")

                // Signal that the session is closed so SSE handler can complete
                sessionJob.complete()
              }
            } catch (e: Exception) {
              println("✗ ERROR during SSE connection setup: ${e.message}")
              e.printStackTrace()
              sessionJob.completeExceptionally(e)
              throw e
            }

            // Keep the SSE connection alive until the session is closed
            // This prevents Ktor from closing the SSE channel while connect() is still running
            println("→ SSE handler waiting for session to close...")
            sessionJob.join()
            println("✓ SSE handler completed for session: $mcpSseSessionId")
          }
        }
        post("/message") {
          val sessionId: String? = call.request.queryParameters["sessionId"]

          if (sessionId == null) {
            val errorMsg = "Missing sessionId query parameter"
            println("✗ POST /message failed: $errorMsg")
            call.respond(HttpStatusCode.BadRequest, errorMsg)
            return@post
          }

          val mcpSseSessionId = McpSseSessionId(sessionId)
          val timeSinceCreation = sessionCreationTimes[mcpSseSessionId]?.let {
            System.currentTimeMillis() - it
          }
          val timingInfo = timeSinceCreation?.let { " (${it}ms after session creation)" } ?: ""

          println("→ Received POST /message for session: $sessionId$timingInfo")
          println("  Currently active sessions: ${sessionContexts.size}")
          if (sessionContexts.isNotEmpty()) {
            println("  Active session IDs: ${sessionContexts.keys.joinToString(", ")}")
          }

          val sessionContext = sessionContexts[mcpSseSessionId]

          if (sessionContext == null) {
            val errorMsg = "Session not found: $sessionId. " +
                if (sessionContexts.isEmpty()) {
                  "No active sessions available. The SSE connection may have been closed."
                } else {
                  "Available sessions: ${sessionContexts.keys.joinToString(", ")}"
                }
            println("✗ $errorMsg")
            call.respond(HttpStatusCode.NotFound, errorMsg)
            return@post
          }

          // Use the transport directly - it's available immediately, even before connect() completes
          // This allows the MCP initialization handshake to proceed during connect()
          val sseServerTransport = sessionContext.sseTransport
          if (sseServerTransport == null) {
            // This should never happen since we set sseTransport immediately after creating the session
            val errorMsg =
              "Transport not available for session: $sessionId. This is an internal error."
            println("✗ $errorMsg")
            call.respond(HttpStatusCode.InternalServerError, errorMsg)
            return@post
          }

          println("✓ Processing message for valid session: $sessionId")
          withContext(Dispatchers.IO) {
            sseServerTransport.handlePostMessage(call)
          }
        }
      }
    }.start(wait = wait)
    println("Server starting...")
    return server
  }
}
