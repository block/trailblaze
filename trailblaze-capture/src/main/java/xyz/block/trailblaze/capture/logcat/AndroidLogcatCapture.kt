package xyz.block.trailblaze.capture.logcat

import java.io.File
import xyz.block.trailblaze.capture.CaptureOptions
import xyz.block.trailblaze.capture.CaptureStream
import xyz.block.trailblaze.capture.DeviceClock
import xyz.block.trailblaze.capture.model.CaptureArtifact
import xyz.block.trailblaze.capture.model.CaptureType
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.util.AndroidHostAdbUtils
import xyz.block.trailblaze.util.Console

/**
 * Captures logcat output using `adb logcat`, filtered to the app under test.
 *
 * Uses epoch timestamp format for easy correlation with session log timestamps. Output is streamed
 * directly to a file in the session directory.
 *
 * ### Output format (epoch)
 *
 * ```
 * 1772846521.234  5432  5432 D MyApp   : onCreate called
 * ```
 */
class AndroidLogcatCapture : CaptureStream {
  override val type = CaptureType.LOGCAT

  private var process: Process? = null
  private var outputFile: File? = null
  private var startTimestampMs: Long = 0
  private var deviceId: String? = null

  override fun start(sessionDir: File, deviceId: String, appId: String?) {
    this.deviceId = deviceId
    startTimestampMs = DeviceClock.nowMs(deviceId)
    outputFile = File(sessionDir, "logcat.txt")

    val trailblazeDeviceId = TrailblazeDeviceId(deviceId, TrailblazeDevicePlatform.ANDROID)

    // Clear logcat buffer before starting
    try {
      AndroidHostAdbUtils.createAdbCommandProcessBuilder(
          args = listOf("logcat", "-c"),
          deviceId = trailblazeDeviceId,
        )
        .start()
        .waitFor()
    } catch (e: Exception) {
      Console.log("Failed to clear logcat buffer: ${e.message}")
    }

    // Start logcat capture with epoch timestamps, streaming to file
    val logcatArgs = mutableListOf("logcat", "-v", "epoch", "-v", "printable")

    // Filter to app PID if appId is known and app is running
    if (appId != null) {
      val pid = getAppPid(deviceId, appId)
      if (pid != null) {
        logcatArgs.add("--pid=$pid")
        Console.log("Filtering logcat to PID $pid ($appId)")
      }
    }

    try {
      val pb = AndroidHostAdbUtils.createAdbCommandProcessBuilder(
          args = logcatArgs,
          deviceId = trailblazeDeviceId,
        )
        .redirectOutput(outputFile)
      process = pb.start()
    } catch (e: Exception) {
      Console.log("Failed to start logcat capture: ${e.message}")
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
      endTimestampMs = deviceId?.let { DeviceClock.nowMs(it) } ?: System.currentTimeMillis(),
    )
  }

  private fun getAppPid(deviceId: String, appId: String): String? =
    try {
      val result =
        AndroidHostAdbUtils.createAdbCommandProcessBuilder(
            args = listOf("shell", "pidof", appId),
            deviceId = TrailblazeDeviceId(deviceId, TrailblazeDevicePlatform.ANDROID),
          )
          .start()
      val output = result.inputStream.bufferedReader().readText().trim()
      result.waitFor()
      output.ifEmpty { null }
    } catch (_: Exception) {
      null
    }
}
