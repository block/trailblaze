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
    /**
     * Returns `true` when a window handler actually ran, `false` when this daemon has no window
     * to show (headless server, or the desktop UI hasn't installed its callback yet). Callers
     * (`trailblaze app` attach detection, the duplicate-instance window handoff) branch on
     * [CliShowWindowResponse.success], so a no-op must not report success.
     */
    onShowWindowRequest: () -> Boolean,
  ) = with(routing) {
    post(CliEndpoints.SHOW_WINDOW) {
      try {
        val handled = onShowWindowRequest()
        call.respond(HttpStatusCode.OK, CliShowWindowResponse(
          success = handled,
          message = if (handled) "Window shown" else "No window available on this daemon"
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
