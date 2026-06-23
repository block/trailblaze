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
        // info-and-above (notice/error/fault too) rather than `debug`: the unified log emits an
        // enormous volume of debug-level chatter, and a test report wants the app's meaningful
        // output, not the firehose. The iOS analog of not dumping every logcat VERBOSE line.
        "--level",
        "info",
      )

    // Scope to the app under test — the logcat-equivalent app filter, NOT the full
    // system-log firehose (see [buildIosLogStreamPredicate]). Null means we couldn't derive
    // an app-scoped predicate, so fall through to capturing unfiltered.
    val predicate = buildIosLogStreamPredicate(appId)
    if (predicate != null) {
      command.addAll(listOf("--predicate", predicate))
    } else if (appId != null) {
      Console.log(
        "iOS log capture: appId '$appId' resolved to a blank process name; " +
          "no process predicate applied — capturing all simulator logs.",
      )
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

/**
 * Builds the `log stream --predicate` that scopes capture to the app under test — the
 * logcat-equivalent app filter, NOT the full system-log firehose (which is orders of magnitude
 * noisier than Android logcat). Returns `null` when [appId] is null or its last component is
 * blank, in which case the caller captures unfiltered.
 *
 * Matches the app three ways, because an app's logs don't all carry the same identity (matching
 * ONLY `process == <last-bundle-component>` missed apps whose process name differs from that
 * component, or that emit `os_log` under a subsystem — the near-empty capture in PR #3944):
 * - `process ==[c] "<name>"`         — NSLog / print from the main executable
 * - `subsystem BEGINSWITH[c] "<id>"` — os_log via `Logger(subsystem: <bundleId>, …)`
 * - `processImagePath CONTAINS[c]`   — helper / XPC processes whose name differs
 *
 * `[c]` = case-insensitive: a bundle id's last component is often lowercase (`sampleapp`,
 * `mobilesafari`) while the real process name / image path uses the app's actual casing
 * (`MobileSafari`), so exact-case matching can silently capture nothing.
 *
 * Internal (not private) purely so it can be unit-tested without spawning a process.
 */
internal fun buildIosLogStreamPredicate(appId: String?): String? {
  if (appId == null) return null
  val processName = appId.substringAfterLast(".")
  if (processName.isBlank()) return null
  return "process ==[c] \"$processName\" OR " +
    "subsystem BEGINSWITH[c] \"$appId\" OR " +
    "processImagePath CONTAINS[c] \"$processName\""
}
