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
   * (`WorkspaceContentHasher.lastCapturedHash`). Covers `trailmap.yaml`, tool YAMLs,
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
  /**
   * Number of trail runs currently pending or executing on this daemon (submitted via
   * `/cli/run-async`). Filled in server-side from [CliRunManager] — the desktop app's
   * status provider doesn't set it. External tooling (e.g. the dev launcher's stale-JAR
   * restart in `scripts/dev-jar-cache.sh`) checks this before stopping the daemon so a
   * rebuild in one checkout can't silently kill a run in flight from another.
   */
  val activeRuns: Int = 0,
  /**
   * One human-readable line per in-flight run (trail name, state, age, session, latest
   * progress), matching [activeRuns]. Lets the surfaces that decline to stop a busy daemon
   * tell the developer exactly who is using it.
   */
  val activeRunSummaries: List<String> = emptyList(),
  /**
   * Per-request features this daemon build honors, from [CliDaemonCapabilities]. Stamped
   * server-side (see `ServerEndpoints`) so it describes the running daemon's build rather than
   * whichever app supplied the status.
   *
   * Empty means an older daemon that predates the set — NOT a daemon with no features. The daemon
   * decodes requests with `ignoreUnknownKeys`, so a field it predates is dropped silently and the
   * run falls back to whatever the daemon's own environment says. For a field that only affects
   * logging that is a fine trade; for one that decides WHICH DEVICES a trail drives it is a wrong
   * result reported as a pass, so callers of such fields check here first and refuse to delegate.
   */
  val capabilities: Set<String> = emptySet(),
)

/**
 * Names of per-request features a daemon build honors, advertised on
 * [CliStatusResponse.capabilities].
 *
 * A capability earns a name here when a request field can be dropped SILENTLY by an older daemon
 * and the result would still look like a pass. Fields whose loss is self-announcing (a trace level,
 * a workspace anchor) don't need one — the existing version check and the run's own output cover
 * them.
 */
object CliDaemonCapabilities {
  /**
   * The daemon reads [CliRunRequest.deviceBindings] and [CliRunRequest.deviceConfiguration] — i.e.
   * `trailblaze run --bind` / `--configuration` select this run's device set. A daemon without this
   * would fall back to its startup `TRAILBLAZE_DEVICE_BINDINGS`, driving the devices some earlier
   * lane bound and passing.
   */
  const val PER_RUN_DEVICE_BINDINGS = "run.device-bindings"

  /**
   * The daemon's `session` tool reads the SAVE action's `configuration` argument and synthesizes a
   * multi-device cast from a named-device roster — i.e. `trailblaze session save --configuration`
   * names the saved configuration. A daemon without this drops the argument silently and saves the
   * session without a cast, keyed by the launch device's classifier — a wrong file reported as a
   * pass.
   */
  const val SESSION_SAVE_CONFIGURATION = "session.save-configuration"

  /**
   * The daemon reads [CliRunRequest.snapshotBaselineRef] and
   * [CliRunRequest.snapshotBaselineThresholdPercent] — i.e. `trailblaze run --snapshot-baseline`
   * compares this run's snapshots against a previous run's. A daemon without this drops both and
   * runs the trail with no comparison at all, reporting the green of a check that never ran.
   */
  const val SNAPSHOT_BASELINE = "run.snapshot-baseline"

  /** Every capability this build honors. */
  val ALL: Set<String> = setOf(PER_RUN_DEVICE_BINDINGS, SESSION_SAVE_CONFIGURATION, SNAPSHOT_BASELINE)
}

/**
 * Endpoint to get daemon status.
 * GET [CliEndpoints.STATUS]
 */
object CliStatusEndpoint {

  fun register(
    routing: Routing,
    statusProvider: suspend () -> CliStatusResponse,
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
