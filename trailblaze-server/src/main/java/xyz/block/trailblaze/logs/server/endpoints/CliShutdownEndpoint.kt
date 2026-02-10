package xyz.block.trailblaze.logs.server.endpoints

import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Routing
import io.ktor.server.routing.post
import kotlinx.serialization.Serializable

/**
 * Response from the shutdown endpoint.
 */
@Serializable
data class CliShutdownResponse(
  val success: Boolean,
  val message: String,
)

/**
 * Endpoint to gracefully shutdown the Trailblaze daemon.
 * POST [CliEndpoints.SHUTDOWN]
 */
object CliShutdownEndpoint {

  fun register(
    routing: Routing,
    onShutdownRequest: () -> Unit,
  ) = with(routing) {
    post(CliEndpoints.SHUTDOWN) {
      try {
        call.respond(HttpStatusCode.OK, CliShutdownResponse(
          success = true,
          message = "Shutdown initiated"
        ))
        // Shutdown after responding
        onShutdownRequest()
      } catch (e: Exception) {
        call.respond(
          HttpStatusCode.InternalServerError,
          CliShutdownResponse(success = false, message = e.message ?: "Unknown error")
        )
      }
    }
  }
}
