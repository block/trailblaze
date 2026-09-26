package xyz.block.trailblaze.capture.video

import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.CRC32
import xyz.block.trailblaze.capture.CaptureOptions
import xyz.block.trailblaze.capture.CaptureStream
import xyz.block.trailblaze.capture.model.CaptureArtifact
import xyz.block.trailblaze.capture.model.CaptureFilenames
import xyz.block.trailblaze.capture.model.CaptureType
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.util.Console

/**
 * Records an Android session by taking screenshots on a timer, for devices whose firmware has no
 * `screenrecord` binary to stream from ([AndroidScreenrecordSupport] decides; [AndroidVideoCapture]
 * delegates here when it says no).
 *
 * The output is the same artifact the streaming recorder produces — the session's `video.webm`,
 * VP9, in the process-wide [RecordingFormat] — placed on the report's timeline the same way, by
 * the host-clock epochs of its first and last frame. Nothing downstream can tell the two apart.
 *
 * ### What it costs, and why it is a fallback rather than a default
 * Every frame is a whole `adb exec-out screencap` round trip, roughly a third of a second, against
 * a stream that costs nothing per frame once it is running. So this samples at
 * [DEFAULT_INTERVAL_MS] rather than following the screen, and a step that lands between two
 * samples is placed on the earlier one. That is the right trade for "show me what the screen was
 * doing when this failed" and the wrong one for anything frame-accurate — which is why it is
 * reached only when the device leaves no alternative.
 *
 * ### Why consecutive identical frames are dropped
 * A screen that is not changing yields byte-identical PNGs. Keeping them would spend disk and
 * encode time to say nothing, and [ScreencastTimeline] already represents a still screen correctly
 * as one frame held for its true duration. Dedupe is by checksum of the encoded bytes, so a
 * collision costs one dropped sample and never a wrong frame.
 */
class AndroidScreencapVideoCapture(
  /** Test seam: swap out the ffmpeg binary path. */
  private val ffmpegBinary: String = "ffmpeg",
  private val format: RecordingFormat = RecordingFormat.preferred,
  /** How long to wait between samples. One frame every [DEFAULT_INTERVAL_MS] by default. */
  private val intervalMs: Long = DEFAULT_INTERVAL_MS,
  /** Test seam: returns one PNG for the device, or null when the grab failed. */
  private val grabFrame: (TrailblazeDeviceId) -> ByteArray? = ::screencapPng,
  /** See [AndroidVideoCapture]'s `basename`; the frame scratch directory is suffixed the same way. */
  private val basename: String = CaptureFilenames.VIDEO_BASENAME,
) : CaptureStream {

  override val type: CaptureType get() = format.captureType

  private var sessionDir: File? = null
  private var framesDir: File? = null
  private var startTimestampMs: Long = 0
  private val running = AtomicBoolean(false)
  private var poller: Thread? = null

  private val framesLock = Any()
  private val frames = mutableListOf<ScreencastTimeline.Frame>()
  private var lastChecksum: Long = -1
  private var droppedToDedupe = 0
  private var droppedToCap = 0
  private var failedGrabs = 0
  private var rejectedFrames = 0

  override fun start(sessionDir: File, deviceId: String, appId: String?) {
    this.sessionDir = sessionDir
    this.startTimestampMs = System.currentTimeMillis()
    sessionDir.mkdirs()
    // Two devices sampled into one session directory each need their own scratch space, or one
    // recorder's cleanup deletes the other's frames mid-session.
    val dir = File(sessionDir, framesSubdir()).apply { mkdirs() }
    framesDir = dir

    val trailblazeDeviceId = TrailblazeDeviceId(deviceId, TrailblazeDevicePlatform.ANDROID)
    running.set(true)
    poller = Thread({ pollUntilStopped(trailblazeDeviceId, dir) }, "screencap-capture-$deviceId").apply {
      isDaemon = true
      start()
    }
    Console.log(
      "[AndroidScreencapVideoCapture] sampling $deviceId every ${intervalMs}ms into ${dir.absolutePath}",
    )
  }

  /**
   * Samples until [stop] clears the flag.
   *
   * The wait is measured from the END of a grab rather than the start of the last one. A grab is
   * itself a large fraction of the interval, so pacing on a fixed deadline would leave the thread
   * with no gap at all on a slow device and turn the sampler into a busy loop against adb.
   */
  private fun pollUntilStopped(deviceId: TrailblazeDeviceId, dir: File) {
    while (running.get()) {
      val capturedAtMs = System.currentTimeMillis()
      val png = try {
        grabFrame(deviceId)
      } catch (e: Exception) {
        Console.log("[AndroidScreencapVideoCapture] screencap failed: ${e.message}")
        null
      }
      if (png != null) storeFrame(png, capturedAtMs, dir) else failedGrabs++
      if (!running.get()) return
      try {
        Thread.sleep(intervalMs)
      } catch (_: InterruptedException) {
        Thread.currentThread().interrupt()
        return
      }
    }
  }

  private fun storeFrame(png: ByteArray, capturedAtMs: Long, dir: File) {
    if (!looksLikePng(png)) {
      // A device that rejects the command answers on STDOUT, so the "bytes" can be an ASCII
      // complaint. Checked here rather than at the grab so every frame source is covered.
      rejectedFrames++
      return
    }
    synchronized(framesLock) {
      val checksum = CRC32().apply { update(png) }.value
      if (checksum == lastChecksum) {
        droppedToDedupe++
        return
      }
      if (frames.size >= MAX_FRAMES) {
        // Hard cap so a pathological multi-hour session can't exhaust disk. Not silent — logged
        // at stop with the drop count so a truncated recording is diagnosable.
        droppedToCap++
        return
      }
      val frameFile = File(dir, "frame_${"%06d".format(frames.size)}.png")
      try {
        frameFile.writeBytes(png)
      } catch (e: Exception) {
        Console.log("[AndroidScreencapVideoCapture] failed to write ${frameFile.name}: ${e.message}")
        return
      }
      frames.add(ScreencastTimeline.Frame(path = frameFile.absolutePath, capturedAtMs = capturedAtMs))
      lastChecksum = checksum
    }
  }

  override fun stop(options: CaptureOptions): CaptureArtifact? {
    running.set(false)
    // Interrupt so a sampler parked in its sleep leaves now rather than at the end of the
    // interval, then bound the join: a grab already in flight is holding an adb round trip and
    // the session must not wait on a wedged one.
    poller?.let { thread ->
      thread.interrupt()
      runCatching { thread.join(POLLER_JOIN_MILLIS) }
    }
    poller = null
    val endTimestampMs = System.currentTimeMillis()

    val dir = sessionDir ?: return null
    val frameSnapshot = synchronized(framesLock) { frames.toList() }
    if (droppedToDedupe > 0 || droppedToCap > 0 || failedGrabs > 0 || rejectedFrames > 0) {
      Console.log(
        "[AndroidScreencapVideoCapture] captured ${frameSnapshot.size} frames " +
          "(unchanged $droppedToDedupe, over-cap $droppedToCap, failed $failedGrabs, " +
          "not an image $rejectedFrames)",
      )
    }
    if (frameSnapshot.isEmpty()) {
      Console.log("[AndroidScreencapVideoCapture] no frames captured in ${dir.absolutePath}")
      cleanupFramesDir()
      return null
    }

    val output = File(dir, format.filename(basename))
    val muxed = ScreencastFrameMux.mux(
      frames = frameSnapshot,
      output = output,
      sessionStartMs = startTimestampMs,
      sessionEndMs = endTimestampMs,
      encodeArgs = format.encodeArgs(),
      ffmpegBinary = ffmpegBinary,
      logTag = "AndroidScreencapVideoCapture",
    )
    cleanupFramesDir()
    if (muxed == null) return null

    return CaptureArtifact(
      file = muxed,
      type = format.captureType,
      startTimestampMs = startTimestampMs,
      endTimestampMs = endTimestampMs,
    )
  }

  private fun cleanupFramesDir() {
    framesDir?.let { dir -> runCatching { dir.deleteRecursively() } }
    framesDir = null
  }

  private fun framesSubdir(): String = CaptureFilenames.framesScratchDir(FRAMES_SUBDIR, basename)

  companion object {
    private const val FRAMES_SUBDIR = ".trailblaze-screencap-frames"

    /**
     * Wait between samples. A grab already costs roughly a third of a second, so this lands near
     * two frames a second — enough to scrub a session by, without turning the recorder into the
     * session's biggest consumer of adb.
     */
    const val DEFAULT_INTERVAL_MS = 500L

    /** Upper bound on stored frames. A backstop against a runaway session exhausting disk. */
    private const val MAX_FRAMES = 20_000

    /** How long stop() waits for a sampler that is mid-grab before leaving it to the daemon flag. */
    private const val POLLER_JOIN_MILLIS = 5_000L

    /** Upper bound on one screencap; well past a slow device, short enough not to skew the pacing. */
    private const val SCREENCAP_TIMEOUT_MS = 10_000L

    /**
     * One PNG of the device's screen.
     *
     * `exec-out` is required rather than `adb shell`: the latter would translate bytes in the PNG
     * that happen to look like line endings and corrupt the image. Whether the bytes really are an
     * image is decided by [looksLikePng] when the frame is stored, since a device that rejects the
     * command answers on stdout — the same trap a missing `screenrecord` falls into, and the
     * reason this path exists at all.
     */
    internal fun screencapPng(deviceId: TrailblazeDeviceId): ByteArray? =
      AdbExecOut.bytes(deviceId, listOf("screencap", "-p"), SCREENCAP_TIMEOUT_MS)

    /** The 8-byte PNG signature every encoder writes. */
    internal fun looksLikePng(bytes: ByteArray): Boolean =
      bytes.size > PNG_SIGNATURE.size && PNG_SIGNATURE.indices.all { bytes[it] == PNG_SIGNATURE[it] }

    private val PNG_SIGNATURE = byteArrayOf(
      0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(),
      0x0D, 0x0A, 0x1A, 0x0A,
    )
  }
}
