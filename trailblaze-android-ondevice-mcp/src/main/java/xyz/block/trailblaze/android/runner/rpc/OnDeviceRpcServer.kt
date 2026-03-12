package xyz.block.trailblaze.android.runner.rpc

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDeviceInfo
import xyz.block.trailblaze.llm.RunYamlRequest
import xyz.block.trailblaze.logs.client.TrailblazeJsonInstance
import xyz.block.trailblaze.logs.client.TrailblazeSession
import xyz.block.trailblaze.logs.client.TrailblazeSessionManager
import xyz.block.trailblaze.mcp.android.ondevice.rpc.RpcResult
import xyz.block.trailblaze.mcp.handlers.GetExecutionStatusRequestHandler
import xyz.block.trailblaze.mcp.handlers.GetScreenStateRequestHandler
import xyz.block.trailblaze.mcp.handlers.ListActiveSessionsRequestHandler
import xyz.block.trailblaze.mcp.handlers.RunYamlRequestHandler
import xyz.block.trailblaze.mcp.handlers.SubscribeToProgressRequestHandler
import xyz.block.trailblaze.mcp.progress.ProgressSessionManager
import xyz.block.trailblaze.mcp.registerRpcHandler
import xyz.block.trailblaze.mcp.respondRpcError

/**
 * On-device RPC server for MCP.
 * Uses explicit session management via TrailblazeSessionManager.
 *
 * Routes requests based on [RunYamlRequest.agentImplementation]:
 * - TRAILBLAZE_RUNNER: Uses [runTrailblazeYaml] callback (traditional YAML-based agent)
 * - TWO_TIER_AGENT: Uses [runTwoTierAgent] callback (OuterLoopAgent + InnerLoopScreenAnalyzer)
 * - MULTI_AGENT_V3: Mobile-Agent-v3 inspired implementation with progress reporting
 *
 * ## Progress Reporting
 *
 * When [progressManager] is provided, the server enables:
 * - Real-time progress event streaming via `/rpc/SubscribeToProgressRequest`
 * - Execution status queries via `/rpc/GetExecutionStatusRequest`
 * - Active session listing via `/rpc/ListActiveSessionsRequest`
 *
 * @param sessionManager Manages session lifecycle and logging
 * @param runTrailblazeYaml Callback to execute via TrailblazeRunner (YAML processing)
 * @param runTwoTierAgent Callback to execute via two-tier agent (OuterLoopAgent)
 * @param trailblazeDeviceInfoProvider Provider for device info including classifiers - used in session start logs
 * @param progressManager Optional manager for tracking and emitting progress events
 */
class OnDeviceRpcServer(
  private val sessionManager: TrailblazeSessionManager,
  private val runTrailblazeYaml: suspend (RunYamlRequest, TrailblazeSession) -> TrailblazeSession,
  private val runTwoTierAgent: suspend (RunYamlRequest, TrailblazeSession) -> TrailblazeSession,
  private val trailblazeDeviceInfoProvider: (TrailblazeDeviceId) -> TrailblazeDeviceInfo,
  private val progressManager: ProgressSessionManager = ProgressSessionManager(),
) {

  // Use a dedicated coroutine scope for background jobs
  private val backgroundScope = CoroutineScope(Dispatchers.IO)
  private var currPromptJob: Job? = null

  fun startServer(port: Int, wait: Boolean = true) {
    val server = embeddedServer(
      factory = CIO,
      port = port,
    ) {
      install(ContentNegotiation) {
        json(TrailblazeJsonInstance)
      }

      routing {
        get("/ping") {
          // Used to make sure the server is available
          call.respondText("""{ "status" : "Running on port $port" }""", ContentType.Application.Json)
        }

        // Register unified request handler that routes based on agentImplementation
        registerRpcHandler(
          RunYamlRequestHandler(
            sessionManager = sessionManager,
            backgroundScope = backgroundScope,
            getCurrentJob = { currPromptJob },
            setCurrentJob = { job -> currPromptJob = job },
            runTrailblazeYaml = runTrailblazeYaml,
            runTwoTierAgent = runTwoTierAgent,
            trailblazeDeviceInfoProvider = trailblazeDeviceInfoProvider,
            progressManager = progressManager,
          )
        )

        // Register GetScreenState handler for MCP subagent screen state queries
        registerRpcHandler(GetScreenStateRequestHandler())

        // Register progress-related handlers for MCP clients (Phase 6)
        registerRpcHandler(SubscribeToProgressRequestHandler(progressManager))
        registerRpcHandler(GetExecutionStatusRequestHandler(progressManager))
        registerRpcHandler(ListActiveSessionsRequestHandler(progressManager))

        // Catch-all for unregistered RPC endpoints
        post("/rpc/{...}") {
          call.respondRpcError(
            status = HttpStatusCode.NotFound,
            errorType = RpcResult.ErrorType.HTTP_ERROR,
            message = "No RPC handler registered for path: ${call.request.local.uri}",
            details = "Available endpoints can be discovered by checking registered handlers."
          )
        }

        // Catch any other unmatched routes
        route("{...}") {
          handle {
            call.respond(HttpStatusCode.NotFound)
          }
        }
      }
    }.start(wait = wait)
  }

  /**
   * Returns the progress session manager for external access.
   *
   * This allows external code to:
   * - Listen to progress events via [ProgressSessionManager.progressEvents]
   * - Query session status via [ProgressSessionManager.getExecutionStatus]
   * - Register as event listeners via [ProgressSessionManager.onProgressEvent]
   */
  fun getProgressManager(): ProgressSessionManager = progressManager
}