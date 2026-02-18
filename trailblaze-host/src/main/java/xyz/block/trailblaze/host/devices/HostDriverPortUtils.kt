package xyz.block.trailblaze.host.devices

import java.net.ServerSocket
import java.util.concurrent.TimeUnit
import xyz.block.trailblaze.util.Console

/**
 * Shared port management utilities used by host driver factories (Android and iOS).
 *
 * Provides functions for cleaning up stale port bindings, waiting for port release,
 * and killing processes that may be holding ports from previous sessions.
 */
internal object HostDriverPortUtils {

  /**
   * Waits for a local port to become available (unbound).
   *
   * @return true if the port was released within the timeout, false otherwise.
   */
  fun waitForPortRelease(
    port: Int,
    timeoutMs: Long,
  ): Boolean {
    val startTime = System.currentTimeMillis()
    var attempts = 0
    while (System.currentTimeMillis() - startTime < timeoutMs) {
      try {
        ServerSocket(port).close()
        Console.log("Port $port successfully released after ${System.currentTimeMillis() - startTime}ms")
        return true
      } catch (e: Exception) {
        attempts++
        if (attempts % 10 == 0) {
          Console.log(
            "Still waiting for port $port to be released... " +
              "(${System.currentTimeMillis() - startTime}ms elapsed)",
          )
        }
        Thread.sleep(100)
      }
    }
    Console.log("Warning: Port $port may still be in use after ${timeoutMs}ms timeout")
    return false
  }

  /**
   * Force-kills any local processes bound to the given port.
   * Uses `lsof` and `kill -9` — safe to call even if no process is using the port.
   */
  fun killProcessesUsingPort(port: Int) {
    try {
      val lsofProcess =
        ProcessBuilder(listOf("lsof", "-ti:$port")).redirectErrorStream(true).start()

      val lsofCompleted = lsofProcess.waitFor(5, TimeUnit.SECONDS)
      if (!lsofCompleted) {
        lsofProcess.destroyForcibly()
        return
      }

      val pids = lsofProcess.inputStream.bufferedReader().readText().trim()

      if (pids.isNotEmpty()) {
        pids.split("\n").filter { it.isNotBlank() }.forEach { pid ->
          try {
            ProcessBuilder(listOf("kill", "-9", pid.trim()))
              .start()
              .waitFor(2, TimeUnit.SECONDS)
          } catch (e: Exception) {
            // Ignore individual process kill failures
          }
        }
      }
    } catch (e: Exception) {
      // Ignore cleanup failures — don't prevent new connections
    }
  }

  /**
   * Removes a stale adb port forward for the given device and port.
   * Safe to call even if no forward exists.
   */
  fun removeStaleAdbPortForward(deviceInstanceId: String, port: Int) {
    try {
      val process =
        ProcessBuilder(
            listOf("adb", "-s", deviceInstanceId, "forward", "--remove", "tcp:$port"),
          )
          .redirectErrorStream(true)
          .start()
      val completed = process.waitFor(5, TimeUnit.SECONDS)
      if (!completed) {
        process.destroyForcibly()
      }
      // Ignore errors — there may not be a stale forward
    } catch (e: Exception) {
      // Ignore cleanup failures
    }
  }
}
