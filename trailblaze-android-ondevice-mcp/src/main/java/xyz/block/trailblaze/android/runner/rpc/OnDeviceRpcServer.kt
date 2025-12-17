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
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import xyz.block.trailblaze.devices.TrailblazeDeviceClassifiersProvider
import xyz.block.trailblaze.llm.RunYamlRequest
import xyz.block.trailblaze.logs.client.TrailblazeJsonInstance
import xyz.block.trailblaze.logs.client.TrailblazeLogger
import xyz.block.trailblaze.logs.client.TrailblazeLoggerInstance
import xyz.block.trailblaze.logs.model.SessionStatus
import xyz.block.trailblaze.mcp.android.ondevice.rpc.RpcResult
import xyz.block.trailblaze.mcp.handlers.DeviceStatusRequestHandler
import xyz.block.trailblaze.mcp.handlers.RunYamlRequestHandler
import xyz.block.trailblaze.mcp.registerRpcHandler
import xyz.block.trailblaze.mcp.respondRpcError
import xyz.block.trailblaze.session.TrailblazeSessionManager

class OnDeviceRpcServer(
  private val runTrailblazeYaml: suspend (RunYamlRequest) -> Unit,
  private val trailblazeDeviceClassifiersProvider: TrailblazeDeviceClassifiersProvider? = null,
  private val trailblazeLogger: TrailblazeLogger = TrailblazeLoggerInstance,
  val sessionManager: TrailblazeSessionManager = TrailblazeSessionManager(),
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
          DeviceStatusRequestHandler(
            trailblazeLogger = trailblazeLogger,
            getCurrentJob = { currPromptJob }
          )
        )

        registerRpcHandler(
          RunYamlRequestHandler(
            trailblazeLogger = trailblazeLogger,
            sessionManager = sessionManager,
            backgroundScope = backgroundScope,
            getCurrentJob = { currPromptJob },
            setCurrentJob = { job -> currPromptJob = job },
            runTrailblazeYaml = { request -> runTrailblazeYaml(request) }
          )
        )

        post("/cancel") {
          try {
            // Cancel the session in the session manager
            sessionManager.cancelCurrentSession()

            // Send cancellation end log immediately
            trailblazeLogger.sendEndLog(
              SessionStatus.Ended.Cancelled(
                durationMs = 0L, // Duration will be calculated in the logger
                cancellationMessage = "Session cancelled by user request via /cancel endpoint.",
              ),
            )

            // Also cancel the current job if it's running
            // Launch cancellation in background to avoid blocking the response
            currPromptJob?.let { job ->
              if (job.isActive) {
                backgroundScope.launch {
                  try {
                    job.cancelAndJoin()
                  } catch (e: Exception) {
                    e.printStackTrace()
                  }
                  // End the session after job is cancelled
                  sessionManager.endSession()
                }
              }
            }

            call.respond(
              HttpStatusCode.OK,
              "Session cancellation requested for: ${sessionManager.getCurrentSessionId() ?: "unknown"}",
            )
          } catch (e: Exception) {
            e.printStackTrace()
            call.respond(
              HttpStatusCode.InternalServerError,
              "Error cancelling session: ${e.message}",
            )
          }
        }

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