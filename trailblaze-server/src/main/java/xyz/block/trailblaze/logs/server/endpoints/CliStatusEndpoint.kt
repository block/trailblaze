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
  /** Build version of the running daemon (e.g., "v20260413.171351.abc1234 (Internal)") */
  val version: String? = null,
  /**
   * Absolute path to the `trails/config/trailblaze.yaml` workspace anchor the daemon
   * resolved at startup, or `null` if the daemon is running in a scratch workspace
   * (no anchor found via walk-up). The CLI uses this to detect workspace mismatch —
   * when a user runs `trailblaze` from cwd B against a daemon started in cwd A, the
   * daemon still serves cwd A's targets/tools. Comparing this against the cwd-resolved
   * anchor lets the launcher print a prominent warning instead of silently returning
   * stale data.
   */
  val workspaceAnchor: String? = null,
  /**
   * Hex SHA-256 over every non-excluded file under the daemon's resolved
   * `<configDir>/`, captured in-memory at daemon startup
   * (`WorkspaceContentHasher.lastCapturedHash`). Covers `pack.yaml`, tool YAMLs,
   * scripted JS/TS, `trailblaze.yaml` itself, toolsets, providers — anything the
   * daemon would read at session start. `null` if the daemon's running scratch
   * (no workspace) or bootstrap hasn't run.
   *
   * The CLI recomputes the same hash for the cwd-resolved workspace at every
   * invocation and warns if they differ — that's the "you edited a workspace file
   * while the daemon was running" drift case the anchor check alone can't catch.
   * Distinct from the daemon's `.bundle.hash` (which only covers manifest files for
   * compile-cache invalidation) — see `WorkspaceContentHasher` kdoc for why both
   * exist.
   */
  val workspaceContentHash: String? = null,
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
