package xyz.block.trailblaze.capture.logcat

import java.io.File
import xyz.block.trailblaze.capture.CaptureOptions
import xyz.block.trailblaze.capture.CaptureStream
import xyz.block.trailblaze.capture.model.CaptureArtifact
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
    outputFile = File(sessionDir, "logcat.txt")

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
    if (appId != null) {
      val processName = appId.substringAfterLast(".")
      command.addAll(listOf("--predicate", "process == \"$processName\""))
    }

    try {
      val pb = ProcessBuilder(command).redirectOutput(outputFile).redirectErrorStream(true)
      process = pb.start()
    } catch (e: Exception) {
      Console.log("Failed to start iOS log capture: ${e.message}")
    }
  }

  override fun stop(options: CaptureOptions): CaptureArtifact? {
    process?.destroyForcibly()
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
}
