package xyz.block.trailblaze.mcp

import ai.koog.agents.core.tools.Tool
import ai.koog.agents.core.tools.Tool.Args
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.core.tools.ToolResult
import ai.koog.agents.core.tools.annotations.InternalAgentToolsApi
import ai.koog.agents.mcp.ToolArgs
import ai.koog.prompt.executor.clients.openai.OpenAILLMClient
import ai.koog.prompt.executor.clients.openai.OpenAIModels
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.httpMethod
import io.ktor.server.request.queryString
import io.ktor.server.request.receive
import io.ktor.server.request.uri
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
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
import xyz.block.trailblaze.AndroidMaestroTrailblazeAgent
import xyz.block.trailblaze.agent.TrailblazeRunner
import xyz.block.trailblaze.agent.model.TestObjective
import xyz.block.trailblaze.agent.model.TrailblazePromptStep
import xyz.block.trailblaze.android.InstrumentationArgUtil
import xyz.block.trailblaze.android.uiautomator.AndroidOnDeviceUiAutomatorScreenState
import xyz.block.trailblaze.api.TestAgentRunner
import xyz.block.trailblaze.logs.client.TrailblazeJsonInstance
import xyz.block.trailblaze.mcp.android.ondevice.rpc.models.ListToolSets
import xyz.block.trailblaze.mcp.android.ondevice.rpc.models.McpPromptRequestData
import xyz.block.trailblaze.mcp.android.ondevice.rpc.models.SelectToolSet
import xyz.block.trailblaze.mcp.models.McpSseSessionId
import xyz.block.trailblaze.mcp.utils.KoogToMcpExt.toJSONSchema
import xyz.block.trailblaze.mcp.utils.McpDirectToolCalls
import xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext
import xyz.block.trailblaze.toolcalls.TrailblazeToolRepo
import xyz.block.trailblaze.toolcalls.TrailblazeToolSet
import java.util.concurrent.ConcurrentHashMap

/**
 * NOTE: This is an experiment to run a Trailblaze MCP server on an Android device.
 */
object TrailblazeAndroidOnDeviceMcpServer {

  val availableToolSets = TrailblazeToolSet.AllBuiltInTrailblazeToolSets

  private val trailblazeAgent = AndroidMaestroTrailblazeAgent()

  val trailblazeToolRepo = TrailblazeToolRepo(
    TrailblazeToolSet.SetOfMarkTrailblazeToolSet,
  )

  // Per-session progress token tracking (multiplatform compatible)
  private val sessionContexts = ConcurrentHashMap<McpSseSessionId, TrailblazeMcpSseSessionContext>()

  var hostMcpToolRegistry = ToolRegistry {}

  val screenStateProvider = {
    AndroidOnDeviceUiAutomatorScreenState(
      filterViewHierarchy = true,
      setOfMarkEnabled = true,
      includeScreenshot = true,
    )
  }

  private val trailblazeRunner: TestAgentRunner = TrailblazeRunner(
    trailblazeToolRepo = trailblazeToolRepo,
    llmClient = OpenAILLMClient(InstrumentationArgUtil.getApiKeyFromInstrumentationArg()),
    llmModel = OpenAIModels.Chat.GPT4_1,
    screenStateProvider = screenStateProvider,
    agent = trailblazeAgent,
  )

  fun addToolsAsMcpToolsFromRegistry(
    newToolRegistry: ToolRegistry,
    mcpServer: Server,
    mcpSseSessionId: McpSseSessionId,
  ) {
    println("Adding These Tools: ${newToolRegistry.tools.map { it.descriptor.name }}")
    hostMcpToolRegistry = hostMcpToolRegistry.plus(newToolRegistry)

    newToolRegistry.tools.forEach { tool ->
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
        val koogTool: Tool<ToolArgs, ToolResult> =
          newToolRegistry.getTool(tool.descriptor.name) as Tool<ToolArgs, ToolResult>

        val koogToolArgs: Args =
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

  fun runSseMcpServerWithPlainConfiguration(port: Int, wait: Boolean = true): EmbeddedServer<*, *> {
    println("Starting On-Device Trailblaze Server on port $port. ")

    println("Will Wait: $wait")

    val server = embeddedServer(
      factory = CIO,
      port = port,
    ) {
      install(ContentNegotiation) {
        json(TrailblazeJsonInstance)
      }
      install(SSE)
      routing {
        sse("/sse") {
          val sseServerSession = this
          withContext(Dispatchers.IO) {
            val mcpServer = Server(
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
            val transport = SseServerTransport("/message", sseServerSession)
            val mcpSseSessionId = McpSseSessionId(transport.sessionId)
            println("NEW SSE Connection ${sseServerSession.call.request.queryString()} ${sseServerSession.call.request.headers.toMap()} $mcpSseSessionId")
            // For SSE, you can also add prompts/tools/resources if needed:
            // server.addTool(...), server.addPrompt(...), server.addResource(...)

            val initialToolRegistry = trailblazeToolRepo.asToolRegistry {
              TrailblazeToolExecutionContext(
                screenState = screenStateProvider(),
                llmResponseId = null,
                trailblazeAgent = trailblazeAgent,
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
            call.respond(HttpStatusCode.NotFound, "Session not found")
            return@post
          }
          withContext(Dispatchers.IO) {
            sseServerTransport.handlePostMessage(call)
          }
        }
        post(ListToolSets.URL_PATH) {
          try {
            val toolSetInfos = availableToolSets.map {
              ListToolSets.ToolSetInfo(
                name = it.name,
              )
            }
            call.respond(HttpStatusCode.OK, TrailblazeJsonInstance.encodeToString(toolSetInfos))
          } catch (e: Exception) {
            println("Exception in /prompt handler: ${e.message}")
            call.respond(HttpStatusCode.BadRequest, "Invalid request: ${e.message}")
          }
        }
        post(SelectToolSet.URL_PATH) {
          try {
            val selectToolSetRequest = call.receive<SelectToolSet>()

            println("selectToolSetRequest: $selectToolSetRequest")

            val toolSets = availableToolSets.filter {
              selectToolSetRequest.toolSetNames.contains(it.name)
            }
            println("Steps: $selectToolSetRequest")
            trailblazeToolRepo.removeAllTrailblazeTools()

            toolSets.forEach {
              trailblazeToolRepo.addTrailblazeTools(it)
            }
            val responseMessage = buildString {
              appendLine("Enabled ToolSet(s): ${selectToolSetRequest.toolSetNames}.")
              val registeredToolNames = trailblazeToolRepo.getCurrentToolDescriptors().map { it.name }
              appendLine("Registered tools: ${registeredToolNames.joinToString(", ")}")
            }

            call.respond(HttpStatusCode.OK, responseMessage)
          } catch (e: Exception) {
            println("Exception in /prompt handler: ${e.message}")
            call.respond(HttpStatusCode.BadRequest, "Invalid request: ${e.message}")
          }
        }
        post(McpPromptRequestData.URL_PATH) {
          try {
            val mcpPromptRequestData = call.receive<McpPromptRequestData>()
            println("Steps: $mcpPromptRequestData")

            val prompt = TestObjective.TrailblazeObjective.TrailblazePrompt(
              fullPrompt = mcpPromptRequestData.fullPrompt,
              steps = mcpPromptRequestData.steps.map { step ->
                TrailblazePromptStep(description = step)
              },
            )
            val statusResult = trailblazeRunner.run(prompt)
            call.respond(HttpStatusCode.OK, "Prompt Executed. $statusResult")
          } catch (e: Exception) {
            println("Exception in /prompt handler: ${e.message}")
            call.respond(HttpStatusCode.BadRequest, "Invalid request: ${e.message}")
          }
        }
        get("/ping") {
          // Used to make sure the server is available
          call.respondText("""{ "status" : "Running on port $port" }""", ContentType.Application.Json)
        }
        route("{...}") {
          handle {
            println("Unhandled route: ${call.request.uri} [${call.request.httpMethod}]")
            call.respond(HttpStatusCode.NotFound)
          }
        }
      }
    }.start(wait = wait)
    println("Server starting...")
    return server
  }
}
