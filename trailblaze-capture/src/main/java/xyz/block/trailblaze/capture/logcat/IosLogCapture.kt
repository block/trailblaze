package xyz.block.trailblaze.capture.logcat

import java.io.File
import java.util.concurrent.TimeUnit
import xyz.block.trailblaze.capture.CaptureOptions
import xyz.block.trailblaze.capture.CaptureStream
import xyz.block.trailblaze.capture.model.CaptureArtifact
import xyz.block.trailblaze.capture.model.CaptureFilenames
import xyz.block.trailblaze.capture.model.CaptureType
import xyz.block.trailblaze.util.Console
import xyz.block.trailblaze.util.isMacOs

/**
 * Captures iOS Simulator system log using `xcrun simctl spawn log stream`.
 *
 * This is the iOS equivalent of Android's logcat. The log is streamed directly to a file and can be
 * optionally filtered to the app under test by process name.
 *
 * ### Output format
 *
 * ```
 * 2026-03-10 14:23:45.678901-0700  MyApp[12345]: (subsystem) message
 * ```
 */
class IosLogCapture : CaptureStream {
  override val type = CaptureType.LOGCAT

  private var process: Process? = null
  private var outputFile: File? = null
  private var startTimestampMs: Long = 0

  override fun start(sessionDir: File, deviceId: String, appId: String?) {
    if (!isMacOs()) return
    startTimestampMs = System.currentTimeMillis()
    outputFile = File(sessionDir, CaptureFilenames.DEVICE_LOG)

    val command =
      mutableListOf(
        "xcrun",
        "simctl",
        "spawn",
        deviceId,
        "log",
        "stream",
        "--style",
        "compact",
        "--level",
        "debug",
      )

    // Filter to app's process name if known. The app ID (bundle ID) often matches
    // the process name, but on iOS the process name is typically the last component.
    // Skip the predicate if the resolved name is blank — `process == ""` matches
    // nothing on the simulator and would silently capture zero lines.
    if (appId != null) {
      val processName = appId.substringAfterLast(".")
      if (processName.isNotBlank()) {
        command.addAll(listOf("--predicate", "process == \"$processName\""))
      } else {
        Console.log(
          "iOS log capture: appId '$appId' resolved to a blank process name; " +
            "no process predicate applied — capturing all simulator logs.",
        )
      }
    }

    try {
      val pb = ProcessBuilder(command).redirectOutput(outputFile).redirectErrorStream(true)
      process = pb.start()
    } catch (e: Exception) {
      Console.log("Failed to start iOS log capture: ${e.message}")
    }
  }

  override fun stop(options: CaptureOptions): CaptureArtifact? {
    val p = process
    if (p != null) {
      // Try a graceful SIGTERM first so xcrun has a chance to flush its buffered output to
      // device.log; only escalate to SIGKILL if it refuses to exit. Forced termination mid-write
      // can leave a truncated tail, so log the path taken for postmortem visibility.
      p.destroy()
      val exitedGracefully = p.waitFor(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)
      if (!exitedGracefully) {
        Console.log(
          "iOS log capture: xcrun did not exit within ${SHUTDOWN_TIMEOUT_SECONDS}s — force-killing; " +
            "device.log may be truncated.",
        )
        p.destroyForcibly()
        p.waitFor(1, TimeUnit.SECONDS)
      }
    }
    process = null

    val file = outputFile ?: return null
    if (!file.exists() || file.length() == 0L) return null

    return CaptureArtifact(
      file = file,
      type = CaptureType.LOGCAT,
      startTimestampMs = startTimestampMs,
      endTimestampMs = System.currentTimeMillis(),
    )
  }

  private companion object {
    /** How long to give `xcrun simctl spawn log stream` to drain after `destroy()`. */
    const val SHUTDOWN_TIMEOUT_SECONDS = 2L
  }
}
