package xyz.block.trailblaze.logs.server

import kotlin.concurrent.thread
import kotlin.system.exitProcess

/**
 * Ends the daemon process from a thread the HTTP server does not own.
 *
 * `System.exit` runs the JVM shutdown hooks, and Ktor's hook stops the server by waiting for its
 * Netty event loops to drain. Called from inside a request handler, that wait includes the very
 * thread making the call, which never returns because it is parked inside `System.exit`: the port
 * closes (so `trailblaze stop` reports success) while the JVM and every subprocess it owns live on.
 */
object DaemonExit {

  /**
   * Returns immediately; [exit] runs on a fresh non-daemon thread. The caller's thread must be free
   * to finish its work before the exit completes — the shutdown endpoint's response and its event
   * loop's termination both depend on it.
   */
  fun exitOffCallerThread(status: Int = 0, exit: (Int) -> Unit = { exitProcess(it) }) {
    thread(name = "trailblaze-daemon-exit", isDaemon = false) { exit(status) }
  }
}
