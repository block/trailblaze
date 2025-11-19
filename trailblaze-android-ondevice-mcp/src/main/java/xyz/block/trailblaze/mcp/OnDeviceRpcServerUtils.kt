package xyz.block.trailblaze.mcp

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.receive
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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import xyz.block.trailblaze.devices.TrailblazeDeviceClassifiersProvider
import xyz.block.trailblaze.llm.RunYamlRequest
import xyz.block.trailblaze.logs.client.TrailblazeJsonInstance
import xyz.block.trailblaze.logs.client.TrailblazeLogger
import xyz.block.trailblaze.logs.client.TrailblazeLoggerInstance
import xyz.block.trailblaze.logs.model.SessionStatus
import xyz.block.trailblaze.session.TrailblazeSessionManager
import xyz.block.trailblaze.util.toSnakeCaseIdentifier
import xyz.block.trailblaze.util.toSnakeCaseWithId
import xyz.block.trailblaze.yaml.TrailblazeYaml

// Data class for the status endpoint response
@Serializable
data class DeviceStatusResponse(
  val sessionId: String?,
  val isRunning: Boolean,
)

class OnDeviceRpcServerUtils(
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

        get("/status") {
          // Return current session status
          try {
            val sessionId = trailblazeLogger.getCurrentSessionId()
            val isRunning = currPromptJob?.isActive == true
            val response = DeviceStatusResponse(sessionId, isRunning)
            call.respond(response)
          } catch (e: Exception) {
            e.printStackTrace()
            // Return a safe default response
            call.respond(DeviceStatusResponse(sessionId = null, isRunning = false))
          }
        }

        post("/run") {
          try {
            val runYamlRequest: RunYamlRequest = call.receive()

            // Cancel any currently running job before starting a new session
            currPromptJob?.let { job ->
              if (job.isActive) {
                // Launch cancellation in background to avoid blocking the response
                backgroundScope.launch {
                  job.cancelAndJoin()
                }
                // Send end log for the interrupted session with cancellation status
                trailblazeLogger.sendEndLog(
                  SessionStatus.Ended.Cancelled(
                    durationMs = 0L,
                    cancellationMessage = "Session cancelled after the user started a new session.",
                  ),
                )
              }
            }

            // Extract config values for session naming
            val trailConfig = try {
              TrailblazeYaml().extractTrailConfig(runYamlRequest.yaml)
            } catch (e: Exception) {
              null
            }

            val configTitle = trailConfig?.title
            val configId = trailConfig?.id

            val methodName = if (configTitle != null && configId != null) {
              toSnakeCaseWithId(configTitle, configId)
            } else {
              toSnakeCaseIdentifier(runYamlRequest.testName)
            }

            // Start session with method name for consistency
            trailblazeLogger.startSession(methodName)

            sessionManager.startSession(methodName)

            // Launch the job in the background scope so it doesn't block the response
            currPromptJob = backgroundScope.launch {
              try {
                runTrailblazeYaml(runYamlRequest)
                trailblazeLogger.sendSessionEndLog(sessionManager, isSuccess = true)
              } catch (e: Exception) {
                e.printStackTrace()
                trailblazeLogger.sendSessionEndLog(sessionManager, isSuccess = false, exception = e)
              } finally {
                sessionManager.endSession()
              }
            }

            // Respond immediately after launching the job
            call.respond(HttpStatusCode.OK, "Yaml Execution Started.")
          } catch (e: Exception) {
            trailblazeLogger.sendEndLog(false, e)
            call.respond(HttpStatusCode.InternalServerError, e.stackTraceToString())
          }
        }

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

        route("{...}") {
          handle {
            call.respond(HttpStatusCode.NotFound)
          }
        }
      }
    }.start(wait = wait)
  }
}
