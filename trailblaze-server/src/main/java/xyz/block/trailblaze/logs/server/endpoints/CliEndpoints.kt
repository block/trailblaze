package xyz.block.trailblaze.logs.server.endpoints

/**
 * CLI endpoint path constants - shared between server and client.
 * 
 * These constants define the HTTP endpoint paths used by CLI commands
 * to communicate with the Trailblaze daemon server.
 */
object CliEndpoints {
  /** Health check endpoint */
  const val PING = "/ping"
  
  /** Get daemon status */
  const val STATUS = "/cli/status"
  
  /** Submit a trail run asynchronously — returns a runId immediately */
  const val RUN_ASYNC = "/cli/run-async"

  /** Poll for the status of an async run */
  const val RUN_STATUS = "/cli/run-status"

  /** Cancel an in-flight async run */
  const val RUN_CANCEL = "/cli/run-cancel"

  /** Request daemon shutdown */
  const val SHUTDOWN = "/cli/shutdown"

  /** Execute a CLI subcommand in-process on the daemon (IPC fast path). */
  const val EXEC = "/cli/exec"

  /**
   * [EXEC] under the route the launcher forwards to. A forwarded command runs the DAEMON's code, so
   * it must only reach a daemon that makes the checks this CLI relies on: today, refusing to run
   * another copy of a trailmap the caller's workspace has. A daemon that predates them answers 404,
   * and the launcher runs the command in its own JVM instead, which restarts a stale daemon once
   * idle. Move to a new route when a forwarded command starts relying on another such check.
   */
  const val EXEC_V2 = "/cli/exec/v2"
}
