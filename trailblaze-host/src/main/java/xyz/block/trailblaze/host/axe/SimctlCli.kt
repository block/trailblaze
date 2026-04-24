package xyz.block.trailblaze.host.axe

import java.util.concurrent.TimeUnit

/**
 * Thin wrapper around `xcrun simctl` for iOS Simulator app-lifecycle operations that AXe
 * intentionally does not cover (launch, terminate, install, uninstall, openurl).
 *
 * Maestro uses the same tool internally on iOS — we just call it directly from the AXe
 * driver path.
 */
object SimctlCli {

  data class Result(val exitCode: Int, val stdout: String, val stderr: String) {
    val success: Boolean get() = exitCode == 0
  }

  fun launch(udid: String, bundleId: String, timeoutSeconds: Long = 15): Result =
    run(listOf("xcrun", "simctl", "launch", udid, bundleId), timeoutSeconds)

  fun terminate(udid: String, bundleId: String, timeoutSeconds: Long = 10): Result =
    run(listOf("xcrun", "simctl", "terminate", udid, bundleId), timeoutSeconds)

  fun uninstall(udid: String, bundleId: String, timeoutSeconds: Long = 20): Result =
    run(listOf("xcrun", "simctl", "uninstall", udid, bundleId), timeoutSeconds)

  fun openUrl(udid: String, url: String, timeoutSeconds: Long = 10): Result =
    run(listOf("xcrun", "simctl", "openurl", udid, url), timeoutSeconds)

  private fun run(args: List<String>, timeoutSeconds: Long): Result {
    val proc = ProcessBuilder(args).redirectErrorStream(false).start()
    val finished = proc.waitFor(timeoutSeconds, TimeUnit.SECONDS)
    if (!finished) {
      proc.destroyForcibly()
      return Result(-1, "", "simctl timed out after ${timeoutSeconds}s")
    }
    val stdout = proc.inputStream.bufferedReader().readText()
    val stderr = proc.errorStream.bufferedReader().readText()
    return Result(proc.exitValue(), stdout, stderr)
  }
}
