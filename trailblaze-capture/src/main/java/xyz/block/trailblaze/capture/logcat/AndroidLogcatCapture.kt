package xyz.block.trailblaze.capture.logcat

import java.io.File
import xyz.block.trailblaze.capture.CaptureOptions
import xyz.block.trailblaze.capture.CaptureStream
import xyz.block.trailblaze.capture.DeviceClock
import xyz.block.trailblaze.capture.model.CaptureArtifact
import xyz.block.trailblaze.capture.model.CaptureFilenames
import xyz.block.trailblaze.capture.model.CaptureType
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.util.AndroidHostAdbUtils
import xyz.block.trailblaze.util.Console

/**
 * Captures logcat output by streaming `logcat` over the dadb shell wire protocol (no `adb` binary
 * subprocess), filtered to the app under test.
 *
 * Uses epoch timestamp format for easy correlation with session log timestamps. Bytes are written
 * directly to a file in the session directory via [AndroidHostAdbUtils.streamingShellToFile], so
 * stop() must close the returned [AutoCloseable] for the file to be fully flushed.
 *
 * ### Output format (epoch)
 *
 * ```
 * 1772846521.234  5432  5432 D MyApp   : onCreate called
 * ```
 */
class AndroidLogcatCapture : CaptureStream {
  override val type = CaptureType.LOGCAT

  private var streamHandle: AutoCloseable? = null
  private var outputFile: File? = null
  private var startTimestampMs: Long = 0
  private var deviceId: String? = null

  override fun start(sessionDir: File, deviceId: String, appId: String?) {
    this.deviceId = deviceId
    startTimestampMs = DeviceClock.nowMs(deviceId)
    outputFile = File(sessionDir, CaptureFilenames.DEVICE_LOG)

    val trailblazeDeviceId = TrailblazeDeviceId(deviceId, TrailblazeDevicePlatform.ANDROID)

    // Clear logcat buffer before starting
    try {
      AndroidHostAdbUtils.execAdbShellCommand(
        deviceId = trailblazeDeviceId,
        args = listOf("logcat", "-c"),
      )
    } catch (e: Exception) {
      Console.log("Failed to clear logcat buffer: ${e.message}")
    }

    // Start logcat capture with epoch timestamps, streaming bytes directly to the session file.
    val command = buildString {
      append("logcat -v epoch -v printable")
      // Filter to app PID if appId is known and app is running
      if (appId != null) {
        val pid = getAppPid(deviceId, appId)
        if (pid != null) {
          append(" --pid=").append(pid)
          Console.log("Filtering logcat to PID $pid ($appId)")
        }
      }
    }

    try {
      val file = outputFile ?: return
      streamHandle = AndroidHostAdbUtils.streamingShellToFile(
        deviceId = trailblazeDeviceId,
        command = command,
        outputFile = file,
      )
    } catch (e: Exception) {
      Console.log("Failed to start logcat capture: ${e.message}")
    }
  }

  override fun stop(options: CaptureOptions): CaptureArtifact? {
    streamHandle?.let { runCatching { it.close() } }
    streamHandle = null

    val file = outputFile ?: return null
    if (!file.exists() || file.length() == 0L) return null

    return CaptureArtifact(
      file = file,
      type = CaptureType.LOGCAT,
      startTimestampMs = startTimestampMs,
      endTimestampMs = deviceId?.let { DeviceClock.nowMs(it) } ?: System.currentTimeMillis(),
    )
  }

  private fun getAppPid(deviceId: String, appId: String): String? = try {
    AndroidHostAdbUtils.execAdbShellCommand(
      deviceId = TrailblazeDeviceId(deviceId, TrailblazeDevicePlatform.ANDROID),
      args = listOf("pidof", appId),
    ).trim().ifEmpty { null }
  } catch (_: Exception) {
    null
  }
}
