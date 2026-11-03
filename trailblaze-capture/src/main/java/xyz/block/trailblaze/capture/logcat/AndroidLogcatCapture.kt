package xyz.block.trailblaze.capture.logcat

import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile
import java.util.Locale
import java.util.UUID
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

  /** What the file held when [markSessionStart] ran: the boundary when no marker was confirmed. */
  @Volatile
  private var lengthAtMark: Long = 0

  /** The line [markSessionStart] logged on the device, once logd confirmed it. */
  @Volatile
  private var sessionStartMarker: String? = null

  /**
   * Logs a marker line on the device and returns once logd has it. Every line logged before it,
   * including a crash adb has not delivered yet, is ahead of it in the stream, and every line the
   * session logs comes after it. Without a confirmed marker the boundary is what the file held at
   * this call, which can only count more crashes, never fewer.
   */
  override fun markSessionStart() {
    val file = outputFile ?: return
    val id = deviceId ?: return
    reopenIfEnded(file, id)
    lengthAtMark = file.length()
    val marker = "$SESSION_START_MARKER-${UUID.randomUUID()}"
    val logged = runCatching {
      // Its own connection: a transport failure on the shared one would evict it, and with it the
      // instrumentation the trail is about to drive. Never retried, since a marker that lands after
      // the call gave up could fall after the session's own lines.
      AndroidHostAdbUtils.execAdbShellCommandOnOwnConnection(
        deviceId = TrailblazeDeviceId(id, TrailblazeDevicePlatform.ANDROID),
        args = listOf("log", "-t", MARKER_TAG, marker),
        timeoutMs = MARK_TIMEOUT_MS,
        quiet = true,
      )
    }.getOrNull() != null
    sessionStartMarker = marker.takeIf { logged }
    // The stream is live only if the marker reaches the file through it. One that stopped
    // delivering need not have said so yet; reopened, it starts again from the last line it
    // wrote, the marker included.
    val delivered = logged && awaitMarker(file, from = lengthAtMark, marker = marker, timeoutMs = MARK_DELIVERY_TIMEOUT_MS)
    if (!delivered) reopenIfEnded(file, id, force = true)
  }

  override fun confirmRecording() {
    reopenIfEnded(outputFile ?: return, deviceId ?: return)
  }

  override val bytesBeforeSessionStart: Long
    get() {
      val file = outputFile ?: return 0
      val marker = sessionStartMarker ?: return lengthAtMark
      return markerLineOffset(file, from = lengthAtMark, marker = marker) ?: lengthAtMark
    }

  /**
   * A stream can fail to open, or end, while the device connects or another stream starts: a
   * recovered adb timeout replaces the device's connection, closing the stream with it. Reopened from the last
   * line it wrote so the session is recorded in full; a line logged in that same millisecond can
   * appear twice. A stop that lands meanwhile wins, and the reopened stream is closed.
   */
  private fun reopenIfEnded(file: File, deviceId: String, force: Boolean = false) {
    val ended = synchronized(handleLock) {
      if (stopped || (!force && streamHandle?.isRunning == true)) return
      streamHandle.also { streamHandle = null }
    }
    ended?.let { runCatching { it.close() } }
    val why = when {
      ended == null -> "never opened"
      force -> "did not deliver the session-start marker"
      else -> "ended"
    }
    Console.log("[logcat] stream on $deviceId $why while capture started; reopening it")
    val reopened = try {
      if (endsMidLine(file)) file.appendText("\n")
      openStream(file, deviceId, since = lastEpochMs(file) ?: logcatSinceMs, append = true)
    } catch (e: Exception) {
      Console.log("Failed to reopen logcat capture: ${e.message}")
      return
    }
    val kept = synchronized(handleLock) { (!stopped).also { if (it) streamHandle = reopened } }
    if (!kept) runCatching { reopened.close() }
  }

  private fun openStream(file: File, deviceId: String, since: Long?, append: Boolean) =
    AndroidHostAdbUtils.streamingShellToFile(
      deviceId = TrailblazeDeviceId(deviceId, TrailblazeDevicePlatform.ANDROID),
      // `-T <epoch>` drops what a failed clear left in the buffer, such as an earlier run's crash.
      command = "logcat -v epoch -v printable" + (since?.let { " -T ${sinceArgument(it)}" } ?: ""),
      outputFile = file,
      append = append,
    )

  private val handleLock = Any()

  /** Guarded by [handleLock], so a stream reopened after [stop] is closed rather than kept. */
  private var streamHandle: AndroidHostAdbUtils.ShellStreamToFile? = null
  private var stopped = false
  private var outputFile: File? = null
  private var startTimestampMs: Long = 0

  /** The device-clock time the stream started from, when the device told us. */
  private var logcatSinceMs: Long? = null
  private var deviceId: String? = null

  override fun start(sessionDir: File, deviceId: String, appId: String?) {
    this.deviceId = deviceId
    appProcessName = appId
    synchronized(handleLock) { stopped = false }
    outputFile = File(sessionDir, filename)
    lengthAtMark = 0
    sessionStartMarker = null
    // `-T` is a device-clock time; a host-clock fallback could cut the session's own lines.
    val deviceNowMs = DeviceClock.deviceNowMs(deviceId)
    startTimestampMs = deviceNowMs ?: System.currentTimeMillis()
    logcatSinceMs = deviceNowMs

    val trailblazeDeviceId = TrailblazeDeviceId(deviceId, TrailblazeDevicePlatform.ANDROID)

    // Clear logcat buffer before starting. On a connection of its own, like the marker: capture can
    // start while instrumentation connects, and a transport failure on the shared client would
    // close it. Never retried, since a clear that lands late could drop the session's own lines.
    val cleared = runCatching {
      AndroidHostAdbUtils.execAdbShellCommandOnOwnConnection(
        deviceId = trailblazeDeviceId,
        args = listOf("logcat", "-c"),
        timeoutMs = CLEAR_TIMEOUT_MS,
      )
    }.getOrNull() != null
    if (!cleared) Console.log("Failed to clear logcat buffer on $deviceId")

    try {
      val file = outputFile ?: return
      val opened = openStream(file, deviceId, since = deviceNowMs, append = false)
      val kept = synchronized(handleLock) { (!stopped).also { if (it) streamHandle = opened } }
      if (!kept) runCatching { opened.close() }
    } catch (e: Exception) {
      Console.log("Failed to start logcat capture: ${e.message}")
    }
  }

  override fun stop(options: CaptureOptions): CaptureArtifact? {
    val handle = synchronized(handleLock) {
      stopped = true
      streamHandle.also { streamHandle = null }
    }
    handle?.let { runCatching { it.close() } }

    val file = outputFile ?: return null
    if (!file.exists() || file.length() == 0L) return null

    return CaptureArtifact(
      file = file,
      type = CaptureType.LOGCAT,
      startTimestampMs = startTimestampMs,
      endTimestampMs = deviceId?.let { DeviceClock.nowMs(it) } ?: System.currentTimeMillis(),
    )
  }

  internal companion object {
    /** `logcat -T` reads a bare number as a line count; the fraction makes it a time. */
    fun sinceArgument(epochMs: Long): String = "%d.%03d".format(Locale.ROOT, epochMs / 1000, epochMs % 1000)

    /** The epoch time of the last timestamped line in [file]'s tail, or null when it has none. */
    internal fun lastEpochMs(file: File): Long? = runCatching {
      RandomAccessFile(file, "r").use { raf ->
        val start = maxOf(0L, raf.length() - TAIL_BYTES)
        val bytes = ByteArray((raf.length() - start).toInt())
        raf.seek(start)
        raf.readFully(bytes)
        String(bytes, Charsets.UTF_8).lineSequence().toList().asReversed()
          .firstNotNullOfOrNull { LogcatParser.parseLine(it).epochMs }
      }
    }.getOrNull()

    private fun endsMidLine(file: File): Boolean = file.length() > 0 &&
      RandomAccessFile(file, "r").use { raf ->
        raf.seek(raf.length() - 1)
        raf.read() != '\n'.code
      }

    /** Whether [marker] reaches [file] past [from] within [timeoutMs]. */
    internal fun awaitMarker(file: File, from: Long, marker: String, timeoutMs: Long, pollMs: Long = 20): Boolean {
      val deadline = System.nanoTime() + timeoutMs * 1_000_000
      while (true) {
        if (markerLineOffset(file, from, marker) != null) return true
        if (System.nanoTime() >= deadline) return false
        Thread.sleep(pollMs)
      }
    }

    private const val MARK_DELIVERY_TIMEOUT_MS = 2_000L

    private const val TAIL_BYTES = 64 * 1024L

    private const val MARKER_TAG = "Trailblaze"
    private const val SESSION_START_MARKER = "trailblaze-session-start"
    private const val MARK_TIMEOUT_MS = 3_000L
    private const val CLEAR_TIMEOUT_MS = 5_000L

    /**
     * The byte offset of the start of the first line at or after [from] in [file] that contains
     * [marker], or null when no line does.
     */
    internal fun markerLineOffset(file: File, from: Long, marker: String): Long? =
      runCatching { file.inputStream().buffered().use { scanForMarker(it, from, marker) } }.getOrNull()

    private fun scanForMarker(input: InputStream, from: Long, marker: String): Long? {
      var skipped = 0L
      while (skipped < from) {
        val n = input.skip(from - skipped)
        if (n <= 0) return null
        skipped += n
      }
      // One byte per char, so a line's length is its byte count.
      val reader = input.reader(Charsets.ISO_8859_1).buffered()
      val line = StringBuilder()
      var lineStart = from
      var offset = from
      var c = reader.read()
      while (c >= 0) {
        offset++
        if (c == '\n'.code) {
          if (line.contains(marker)) return lineStart
          line.setLength(0)
          lineStart = offset
        } else {
          line.append(c.toChar())
        }
        c = reader.read()
      }
      return lineStart.takeIf { line.contains(marker) }
    }
  }
}
