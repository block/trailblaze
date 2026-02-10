package xyz.block.trailblaze.logs.server.endpoints

import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get
import kotlinx.serialization.Serializable

/**
 * Response from the status endpoint.
 */
@Serializable
data class CliStatusResponse(
  /** Whether the daemon is running */
  val running: Boolean,
  /** Server port */
  val port: Int,
  /** Number of connected devices */
  val connectedDevices: Int,
  /** Current active session ID, if any */
  val activeSessionId: String? = null,
  /** Uptime in seconds */
  val uptimeSeconds: Long,
)

/**
 * Endpoint to get daemon status.
 * GET [CliEndpoints.STATUS]
 */
object CliStatusEndpoint {

  fun register(
    routing: Routing,
    statusProvider: () -> CliStatusResponse,
  ) = with(routing) {
    get(CliEndpoints.STATUS) {
      try {
        val status = statusProvider()
        call.respond(HttpStatusCode.OK, status)
      } catch (e: Exception) {
        call.respond(
          HttpStatusCode.InternalServerError,
          CliStatusResponse(
            running = false,
            port = 0,
            connectedDevices = 0,
            uptimeSeconds = 0,
          )
        )
      }
    }
  }
}
