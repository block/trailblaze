package xyz.block.trailblaze.mcp.android.ondevice.rpc

/**
 * The parts of an Android memory reading that must be identical wherever it runs: the host's adb
 * script and the runner's own shell reads both ask the app to collect garbage the same way, wait
 * the same time for it, and read a pid out of the same `pidof` output.
 *
 * Shared because the two are written in different dialects — one is a `sh -c` script sent over adb,
 * the other a sequence of single `UiAutomation` commands — and letting the policy drift would make
 * `gcForced` mean something different depending on which side happened to answer.
 */
object AndroidMemoryReadCommands {
  /**
   * SIGUSR1: what ART collects on. Sent with `run-as`, the only way the shell user may signal
   * another app's process, and then only a debuggable build.
   */
  const val GC_SIGNAL: String = "-10"

  /** How long ART is given to finish that collection before the heap is read. */
  const val GC_SETTLE_MS: Long = 50

  /** The settle as a `sleep` argument, for the side that waits inside a shell script. */
  val GC_SETTLE_SECONDS: String = (GC_SETTLE_MS / 1000.0).toString()

  /**
   * `run-as <appId> kill -10 <pid>`. [pid] is a literal on the device side and a shell expansion
   * (`$pid`) in the adb script, so it is passed as text.
   */
  fun gcCommand(appId: String, pid: String): String = "run-as $appId kill $GC_SIGNAL $pid"

  /**
   * The first pid in `pidof <package>` output, or null when nothing is running. `pidof` prints
   * every matching process space-separated; the app under test is the first.
   */
  fun firstPid(pidofOutput: String?): Int? = pidofOutput
    ?.split(' ', '\t', '\n', '\r')
    ?.firstOrNull { it.isNotBlank() }
    ?.trim()
    ?.toIntOrNull()
}
