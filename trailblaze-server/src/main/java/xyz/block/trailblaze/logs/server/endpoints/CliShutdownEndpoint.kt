package xyz.block.trailblaze.logs.server.endpoints

import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Routing
import io.ktor.server.routing.post
import kotlinx.serialization.Serializable
import xyz.block.trailblaze.util.Console

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
    /** In-flight run summaries logged with the request so a mid-run kill is attributable. */
    activeRunSummaries: () -> List<String> = { emptyList() },
  ) = with(routing) {
    post(CliEndpoints.SHUTDOWN) {
      try {
        // The exit itself (Compose exitApplication) produces no log line of its own — this is
        // the only daemon-side record that the death was a requested shutdown rather than a
        // crash. Keep it loud, and name the in-flight runs the shutdown will abandon.
        val activeRuns = activeRunSummaries()
        Console.log(
          "[CliShutdownEndpoint] Shutdown requested via ${CliEndpoints.SHUTDOWN}" +
            " (in-flight runs: ${activeRuns.size})" +
            if (activeRuns.isNotEmpty()) {
              " — shutting down will abandon them:" +
                activeRuns.joinToString("") { "\n  - $it" }
            } else {
              ""
            },
        )
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
