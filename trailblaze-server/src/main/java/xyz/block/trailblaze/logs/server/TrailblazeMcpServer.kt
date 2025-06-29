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
import io.ktor.util.toMap
import io.modelcontextprotocol.kotlin.sdk.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.Implementation
import io.modelcontextprotocol.kotlin.sdk.RequestId
import io.modelcontextprotocol.kotlin.sdk.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.TextContent
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.server.SseServerTransport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import xyz.block.trailblaze.logs.client.TrailblazeJsonInstance
import xyz.block.trailblaze.logs.server.ServerEndpoints.logsServerKtorEndpoints
import xyz.block.trailblaze.logs.server.SslConfig.configureForSelfSignedSsl
import xyz.block.trailblaze.mcp.TrailblazeMcpSseSessionContext
import xyz.block.trailblaze.mcp.models.McpSseSessionId
import xyz.block.trailblaze.mcp.newtools.AndroidOnDeviceToolSet
import xyz.block.trailblaze.mcp.utils.KoogToMcpExt.toJSONSchema
import xyz.block.trailblaze.mcp.utils.McpDirectToolCalls
import xyz.block.trailblaze.report.utils.LogsRepo
import java.util.concurrent.ConcurrentHashMap

class TrailblazeMcpServer(val logsRepo: LogsRepo) {

  // Per-session progress token tracking (multiplatform compatible)
  private val sessionContexts = ConcurrentHashMap<McpSseSessionId, TrailblazeMcpSseSessionContext>()

  var hostMcpToolRegistry = ToolRegistry.Companion {}

  fun setSessionContext(mcpSseSessionId: McpSseSessionId, server: Server) {
    // Create session context for this session
    val sessionContext = TrailblazeMcpSseSessionContext(
      mcpServer = server,
      mcpSseSessionId = mcpSseSessionId,
    )
    sessionContexts[mcpSseSessionId] = sessionContext
  }

  fun getSessionContext(mcpSseSessionId: McpSseSessionId): TrailblazeMcpSseSessionContext? = sessionContexts[mcpSseSessionId]

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
      // Convert Koog tool to MCP tool
      val toolDescriptorData: JsonObject = tool.descriptor.toJSONSchema()

      val inputSchema: io.modelcontextprotocol.kotlin.sdk.Tool.Input =
        TrailblazeJsonInstance.decodeFromJsonElement(toolDescriptorData)

      println("Registering ${tool.descriptor.name} for session $mcpSseSessionId with input schema: $inputSchema")
      mcpServer.addTool(
        name = tool.descriptor.name,
        description = tool.descriptor.description,
        inputSchema = inputSchema,
      ) { request: CallToolRequest ->

        val progressTokenJsonObject: JsonPrimitive? = request._meta["progressToken"] as JsonPrimitive?
        val progressToken = progressTokenJsonObject?.content?.let {
          println("progressToken for session $mcpSseSessionId = $it")
          println("progressToken isString = ${progressTokenJsonObject.isString}")
          RequestId.StringId(it)
        }

        // Store progress token for this session (multiplatform compatible)
        sessionContexts[mcpSseSessionId]?.progressToken = progressToken

        @Suppress("UNCHECKED_CAST")
        val koogTool: Tool<Tool.Args, ToolResult> =
          newToolRegistry.getTool(tool.descriptor.name) as Tool<Tool.Args, ToolResult>

        val koogToolArgs: Tool.Args =
          TrailblazeJsonInstance.decodeFromJsonElement(koogTool.argsSerializer, request.arguments)

        println("Executing tool: \\${koogTool.descriptor.name} with arguments: \\$koogToolArgs")

        val (_: ToolResult, toolResponseMessage: String) =
          @OptIn(InternalAgentToolsApi::class)
          koogTool.executeAndSerialize(
            args = koogToolArgs,
            enabler = McpDirectToolCalls,
          )

        println("Tool result toolResponseMessage: \\$toolResponseMessage")

        CallToolResult(
          mutableListOf(
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
    println("Starting sse server on port $port. ")
    println("Use inspector to connect to the http://localhost:$port/sse")

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
      logsServerKtorEndpoints(logsRepo)
      install(SSE)
      routing {
        sse("/sse") {
          val sseServerSession = this
          withContext(Dispatchers.IO) {
            val mcpServer = configureMcpServer()
            val transport = SseServerTransport("/message", sseServerSession)
            val mcpSseSessionId = McpSseSessionId(transport.sessionId)
            println("NEW SSE Connection ${sseServerSession.call.request.queryString()} ${sseServerSession.call.request.headers.toMap()} $mcpSseSessionId")
            // For SSE, you can also add prompts/tools/resources if needed:
            // server.addTool(...), server.addPrompt(...), server.addResource(...)
            setSessionContext(mcpSseSessionId, mcpServer)

            val initialToolRegistry = ToolRegistry.Companion {
              tools(
                AndroidOnDeviceToolSet(
                  sessionContext = getSessionContext(mcpSseSessionId),
                  toolRegistryUpdated = { updatedToolRegistry ->
                    addToolsAsMcpToolsFromRegistry(
                      newToolRegistry = updatedToolRegistry,
                      mcpServer = mcpServer,
                      mcpSseSessionId = mcpSseSessionId,
                    )
                  },
                ).asTools(TrailblazeJsonInstance),
              )
            }

            addToolsAsMcpToolsFromRegistry(
              newToolRegistry = initialToolRegistry,
              mcpServer = mcpServer,
              mcpSseSessionId = mcpSseSessionId,
            )

            mcpServer.onClose {
              println("Server closed")
              sessionContexts.remove(McpSseSessionId(transport.sessionId)) // Clear session context for this session
            }

            mcpServer.connect(transport)
          }
        }
        post("/message") {
          val sessionId: String = call.request.queryParameters["sessionId"]!!
          println("Received Message for Session $sessionId")

          val mcpServer = sessionContexts[McpSseSessionId(sessionId)]?.mcpServer
          val sseServerTransport = mcpServer?.transport as? SseServerTransport
          if (sseServerTransport == null) {
            call.respond(HttpStatusCode.Companion.NotFound, "Session not found")
            return@post
          }
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
