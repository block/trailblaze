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
  
  /** Show/bring window to front */
  const val SHOW_WINDOW = "/cli/show-window"

  /** Execute a CLI subcommand in-process on the daemon (IPC fast path). */
  const val EXEC = "/cli/exec"
}
