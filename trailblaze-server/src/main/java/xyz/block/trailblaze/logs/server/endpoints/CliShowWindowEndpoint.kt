package xyz.block.trailblaze.logs.server.endpoints

import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Routing
import io.ktor.server.routing.post
import kotlinx.serialization.Serializable

/**
 * Response from the show-window endpoint.
 */
@Serializable
data class CliShowWindowResponse(
  val success: Boolean,
  val message: String,
)

/**
 * Endpoint to show the Trailblaze window (bring to foreground).
 * POST [CliEndpoints.SHOW_WINDOW]
 */
object CliShowWindowEndpoint {

  fun register(
    routing: Routing,
    onShowWindowRequest: () -> Unit,
  ) = with(routing) {
    post(CliEndpoints.SHOW_WINDOW) {
      try {
        onShowWindowRequest()
        call.respond(HttpStatusCode.OK, CliShowWindowResponse(
          success = true,
          message = "Window shown"
        ))
      } catch (e: Exception) {
        call.respond(
          HttpStatusCode.InternalServerError,
          CliShowWindowResponse(success = false, message = e.message ?: "Unknown error")
        )
      }
    }
  }
}
