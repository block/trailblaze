package xyz.block.trailblaze.logs.server

/**
 * A loopback-only port serving a lending daemon's device leases (`/leases`) and nothing else, for
 * tunnels that bring a daemon's lending to other machines. A tunnel ends on this machine's
 * loopback, where the daemon's other routes (`/cli/exec`, `/mcp`) trust the caller as this
 * machine's own user. Through this port, whatever is at the tunnel's far end can only lease and
 * drive devices. A lending daemon opens it [OFFSET] below its own HTTP port unless [ENV_VAR] names
 * another, so side-by-side daemons on one machine get ports of their own.
 */
object LendingPort {

  const val ENV_VAR = "TRAILBLAZE_LEND_PORT"

  /**
   * How far below the daemon's HTTP port its lending port sits: 52520 for the default 52525.
   * SISTER-IMPL-TAG: lending-port-offset. Scripts that set up a lender derive the same port.
   */
  const val OFFSET = 5

  /** The lending port of a lending daemon on [daemonPort]. */
  fun forDaemon(daemonPort: Int, env: (String) -> String? = System::getenv): Int = fromEnv(env) ?: (daemonPort - OFFSET)

  /** The port [ENV_VAR] names, or null when it names none. */
  fun fromEnv(env: (String) -> String? = System::getenv): Int? {
    val value = env(ENV_VAR)?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    return value.toIntOrNull()?.takeIf { it in 1..65535 }
      ?: error("$ENV_VAR must be a port number, got '$value'")
  }

  /**
   * Whether a request for [path] (as the request line carries it) on the lending port is a lease
   * route. A segment that could be read as another route once decoded or normalized is not.
   */
  fun serves(path: String): Boolean {
    val segments = path.substringBefore('?').split('/').filter { it.isNotEmpty() }
    return segments.firstOrNull() == "leases" &&
      segments.none { it == "." || it == ".." || '%' in it || '\\' in it }
  }
}
