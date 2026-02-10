package xyz.block.trailblaze.logs.server.endpoints

import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Routing
import io.ktor.server.routing.post
import kotlinx.serialization.Serializable
import xyz.block.trailblaze.llm.RunYamlRequest

/**
 * Request from CLI to run a trail file.
 * This is a simplified, serializable version of DesktopAppRunYamlParams.
 */
@Serializable
data class CliRunRequest(
  /** The RunYamlRequest containing all execution parameters */
  val runYamlRequest: RunYamlRequest,
  /** Whether to force stop the target app before running the trail. */
  val forceStopTargetApp: Boolean = false,
)

/**
 * Response from the run endpoint.
 */
@Serializable
data class CliRunResponse(
  /** Whether the run was started successfully */
  val success: Boolean,
  /** Session ID for tracking the run */
  val sessionId: String? = null,
  /** Error message if failed */
  val error: String? = null,
)

/**
 * Endpoint to trigger a trail run from the CLI.
 * POST [CliEndpoints.RUN]
 */
object CliRunEndpoint {

  fun register(
    routing: Routing,
    onRunRequest: (CliRunRequest) -> CliRunResponse,
  ) = with(routing) {
    post(CliEndpoints.RUN) {
      try {
        val request = call.receive<CliRunRequest>()
        val response = onRunRequest(request)
        
        val statusCode = if (response.success) HttpStatusCode.OK else HttpStatusCode.BadRequest
        call.respond(statusCode, response)
      } catch (e: Exception) {
        call.respond(
          HttpStatusCode.InternalServerError,
          CliRunResponse(success = false, error = e.message ?: "Unknown error")
        )
      }
    }
  }
}
