package xyz.block.trailblaze.capture.logcat

import java.io.File
import java.util.Locale
import xyz.block.trailblaze.capture.CaptureOptions
import xyz.block.trailblaze.capture.CaptureStream
import xyz.block.trailblaze.capture.DeviceClock
import xyz.block.trailblaze.capture.AppScopedCaptureStream
import xyz.block.trailblaze.capture.model.CaptureArtifact
import xyz.block.trailblaze.capture.model.CaptureFilenames
import xyz.block.trailblaze.capture.model.CaptureType
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.util.AndroidHostAdbUtils
import xyz.block.trailblaze.util.Console

/**
 * Captures the device's whole logcat by streaming `logcat` over the dadb shell wire protocol (no
 * `adb` binary subprocess).
 *
 * Not filtered to the app's PID: a relaunch or reinstall changes it, and `--pid` would record
 * nothing after that; `system_server` lines (ANRs, job scheduling) are also wanted.
 * [CrashEventArtifactWriter] attributes crashes to the app by process name instead.
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
 *
 * ### Which clock
 * This artifact's bookends are on the **device** clock, unlike every video recorder's (which are
 * host-clock — see `CaptureArtifact`). That is deliberate: `logcat` stamps its own lines with the
 * device clock, so the window has to be on the same clock as the file it describes. A reader lining
 * these lines up against session logs owns that conversion.
 *
 * @param filename [CaptureFilenames.DEVICE_LOG] for a session's own device,
 *   [CaptureFilenames.companionDeviceLog] for a companion.
 */
class AndroidLogcatCapture(
  private val filename: String = CaptureFilenames.DEVICE_LOG,
) : CaptureStream, AppScopedCaptureStream {
  override val type = CaptureType.LOGCAT

  override val isAppScoped: Boolean = false

  /**
   * The app's package, which is also its main process name: [start]'s `appId`, which the host
   * resolves against the device right before starting capture. Null when the host could not tell
   * which app the session drives, in which case every crash in the log counts.
   */
  override var appProcessName: String? = null
    private set

  private var streamHandle: AutoCloseable? = null
  private var outputFile: File? = null
  private var startTimestampMs: Long = 0
  private var deviceId: String? = null

  override fun start(sessionDir: File, deviceId: String, appId: String?) {
    this.deviceId = deviceId
    appProcessName = appId
    outputFile = File(sessionDir, filename)
    // `-T` is a device-clock time; a host-clock fallback could cut the session's own lines.
    val deviceNowMs = DeviceClock.deviceNowMs(deviceId)
    startTimestampMs = deviceNowMs ?: System.currentTimeMillis()

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

    try {
      val file = outputFile ?: return
      streamHandle = AndroidHostAdbUtils.streamingShellToFile(
        deviceId = trailblazeDeviceId,
        // `-T <epoch>` drops what a failed clear left in the buffer, such as an earlier run's crash.
        command = "logcat -v epoch -v printable" + (deviceNowMs?.let { " -T ${sinceArgument(it)}" } ?: ""),
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

  private companion object {
    /** `logcat -T` reads a bare number as a line count; the fraction makes it a time. */
    fun sinceArgument(epochMs: Long): String = "%d.%03d".format(Locale.ROOT, epochMs / 1000, epochMs % 1000)
  }
}
