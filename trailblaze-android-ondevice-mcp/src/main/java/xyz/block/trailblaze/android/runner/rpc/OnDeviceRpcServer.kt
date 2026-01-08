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
import xyz.block.trailblaze.llm.RunYamlRequest
import xyz.block.trailblaze.logs.client.TrailblazeJsonInstance
import xyz.block.trailblaze.logs.client.TrailblazeSession
import xyz.block.trailblaze.logs.client.TrailblazeSessionManager
import xyz.block.trailblaze.mcp.android.ondevice.rpc.RpcResult
import xyz.block.trailblaze.mcp.handlers.RunYamlRequestHandler
import xyz.block.trailblaze.mcp.registerRpcHandler
import xyz.block.trailblaze.mcp.respondRpcError

/**
 * On-device RPC server for MCP.
 * Uses explicit session management via TrailblazeSessionManager.
 */
class OnDeviceRpcServer(
  private val sessionManager: TrailblazeSessionManager,
  private val runTrailblazeYaml: suspend (RunYamlRequest, TrailblazeSession) -> TrailblazeSession,
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

        // Register type-safe RPC handlers
        registerRpcHandler(
          RunYamlRequestHandler(
            sessionManager = sessionManager,
            backgroundScope = backgroundScope,
            getCurrentJob = { currPromptJob },
            setCurrentJob = { job -> currPromptJob = job },
            runTrailblazeYaml = runTrailblazeYaml
          )
        )

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
}