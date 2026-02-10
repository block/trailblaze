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
  
  /** Trigger a trail run */
  const val RUN = "/cli/run"
  
  /** Request daemon shutdown */
  const val SHUTDOWN = "/cli/shutdown"
  
  /** Show/bring window to front */
  const val SHOW_WINDOW = "/cli/show-window"
}
