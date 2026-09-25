package xyz.block.trailblaze.capture.video

import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import xyz.block.trailblaze.capture.CaptureOptions
import xyz.block.trailblaze.capture.CaptureStream
import xyz.block.trailblaze.capture.model.CaptureArtifact
import xyz.block.trailblaze.capture.model.CaptureType
import xyz.block.trailblaze.util.Console

/**
 * `CaptureStream` for Playwright-driven web / Electron sessions that records the session recording
 * from the **same live screencast** feeding the `/devices` viewer and the stream-sourced agent screenshots,
 * instead of Playwright's built-in `Browser.NewContextOptions.setRecordVideoDir` (that path is
 * [PlaywrightVideoCapture], kept as the [fallback]).
 *
 * This makes web match Android's one-encoder model: one frame stream, consumed by the viewer, the
 * screenshot matcher, and the recorder. It also fixes two things the Playwright-recorder path
 * can't:
 *  - **Electron has no session video today** — `setRecordVideoDir` is a context-*creation* option,
 *    but Electron attaches to an already-running context over CDP, so it was never wired. A
 *    screencast follows a live page regardless of who created the context.
 *  - **Fragmentation** — Playwright writes one `.webm` per page and finalizes only at context
 *    close, so popups / `resetSession` scatter footage across files ([PlaywrightVideoCapture]
 *    keeps only the most recent). One screencast → one continuous recording.
 *
 * ### Lifecycle
 *  - [start] looks up a [WebScreencastFeedRegistry.Feed] for the device. **No feed → delegate
 *    wholesale to [fallback]** (the report-export path, which drives a browser with no live
 *    screencast, takes this route so it keeps working unchanged). A feed present → subscribe to
 *    its JPEG frames, writing each (throttled) frame to a temp dir under the session directory
 *    stamped with its host-clock arrival time.
 *  - [stop] detaches, then muxes the collected frames into the session recording (`video.webm`,
 *    VP9, in the process-wide [RecordingFormat]) via the ffmpeg concat demuxer at a constant frame
 *    rate (see [ScreencastTimeline] for the wall-clock timing model).
 *
 * Because this path never publishes a [PlaywrightVideoRecordDir] entry, the browser context is
 * built **without** `setRecordVideoDir` — the two recorders are mutually exclusive by construction,
 * so a session is never double-recorded.
 */
class WebScreencastVideoCapture(
  private val fallback: CaptureStream = PlaywrightVideoCapture(),
  /** Test seam: swap out the ffmpeg binary path. */
  private val ffmpegBinary: String = "ffmpeg",
  private val format: RecordingFormat = RecordingFormat.preferred,
) : CaptureStream {
  override val type: CaptureType get() = format.captureType

  private var sessionDir: File? = null
  private var framesDir: File? = null
  private var startTimestampMs: Long = 0
  private var subscription: AutoCloseable? = null

  /** True when no screencast feed was registered at [start] and we handed off to [fallback]. */
  private var usingFallback = false

  private val frameIndex = AtomicInteger(0)
  private val framesLock = Any()
  private val frames = mutableListOf<ScreencastTimeline.Frame>()

  /** Last stored frame's arrival time; used to throttle bursty screencast output. */
  private var lastStoredAtMs: Long = 0
  private var droppedToThrottle = 0
  private var droppedToCap = 0

  override fun start(sessionDir: File, deviceId: String, appId: String?) {
    this.sessionDir = sessionDir
    this.startTimestampMs = System.currentTimeMillis()
    sessionDir.mkdirs()

    val feed = WebScreencastFeedRegistry.get(deviceId)
    if (feed == null) {
      // No live screencast for this device (report export, or a manager that didn't register a
      // feed). Fall back to Playwright's own recorder so this path still produces a video.
      usingFallback = true
      Console.log(
        "[WebScreencastVideoCapture] no screencast feed for deviceId=$deviceId — " +
          "falling back to Playwright setRecordVideoDir recorder",
      )
      fallback.start(sessionDir, deviceId, appId)
      return
    }

    val dir = File(sessionDir, SCREENCAST_FRAMES_SUBDIR).apply { mkdirs() }
    framesDir = dir
    subscription = feed.subscribe { jpeg, hostTimestampMs -> onFrame(jpeg, hostTimestampMs) }
    Console.log(
      "[WebScreencastVideoCapture] recording from live screencast for deviceId=$deviceId " +
        "into ${dir.absolutePath}",
    )
  }

  private fun onFrame(jpeg: ByteArray, hostTimestampMs: Long) {
    val dir = framesDir ?: return
    synchronized(framesLock) {
      // Throttle bursty runs: a page transition can emit many frames within a few ms. One frame
      // per THROTTLE_MIN_INTERVAL_MS is smooth enough for a report scrubber while bounding count.
      // The first frame is always kept (lastStoredAtMs == 0).
      if (lastStoredAtMs != 0L && hostTimestampMs - lastStoredAtMs < THROTTLE_MIN_INTERVAL_MS) {
        droppedToThrottle++
        return
      }
      if (frames.size >= MAX_FRAMES) {
        // Hard cap so a pathological multi-hour session can't exhaust disk. Not silent — logged
        // once at stop with the drop count so a truncated recording is diagnosable.
        droppedToCap++
        return
      }
      val idx = frameIndex.getAndIncrement()
      val frameFile = File(dir, "frame_${"%06d".format(idx)}.jpg")
      try {
        frameFile.writeBytes(jpeg)
      } catch (e: Exception) {
        Console.log("[WebScreencastVideoCapture] failed to write frame $idx: ${e.message}")
        return
      }
      frames.add(ScreencastTimeline.Frame(path = frameFile.absolutePath, capturedAtMs = hostTimestampMs))
      lastStoredAtMs = hostTimestampMs
    }
  }

  override fun stop(options: CaptureOptions): CaptureArtifact? {
    if (usingFallback) return fallback.stop(options)

    // Detach first so no frame lands after we snapshot the list.
    runCatching { subscription?.close() }
    subscription = null
    val endTimestampMs = System.currentTimeMillis()

    val dir = sessionDir ?: return null
    val frameSnapshot = synchronized(framesLock) { frames.toList() }
    if (droppedToThrottle > 0 || droppedToCap > 0) {
      Console.log(
        "[WebScreencastVideoCapture] captured ${frameSnapshot.size} frames " +
          "(throttled $droppedToThrottle, over-cap $droppedToCap)",
      )
    }
    if (frameSnapshot.isEmpty()) {
      Console.log("[WebScreencastVideoCapture] no screencast frames captured in ${dir.absolutePath}")
      cleanupFramesDir()
      return null
    }

    val output = File(dir, format.canonicalFilename)
    val muxed = muxFrames(frameSnapshot, output, startTimestampMs, endTimestampMs)
    cleanupFramesDir()
    if (muxed == null) {
      Console.log("[WebScreencastVideoCapture] ffmpeg mux produced no ${output.name} in ${dir.absolutePath}")
      return null
    }

    return CaptureArtifact(
      file = muxed,
      type = format.captureType,
      startTimestampMs = startTimestampMs,
      endTimestampMs = endTimestampMs,
    )
  }

  /**
   * Muxes [frames] into [output] through [ScreencastFrameMux], the encode this recorder shares
   * with the Android screencap fallback.
   */
  private fun muxFrames(
    frames: List<ScreencastTimeline.Frame>,
    output: File,
    sessionStartMs: Long,
    sessionEndMs: Long,
  ): File? = ScreencastFrameMux.mux(
    frames = frames,
    output = output,
    sessionStartMs = sessionStartMs,
    sessionEndMs = sessionEndMs,
    format = format,
    ffmpegBinary = ffmpegBinary,
    logTag = "WebScreencastVideoCapture",
    muxFps = MUX_FPS,
  )

  private fun cleanupFramesDir() {
    framesDir?.let { dir -> runCatching { dir.deleteRecursively() } }
    framesDir = null
  }

  companion object {
    private const val SCREENCAST_FRAMES_SUBDIR = ".trailblaze-screencast-frames"

    /**
     * Constant output frame rate for the muxed recording. The report timeline aligns by wall-clock,
     * so a modest rate is enough for smooth scrubbing; the encoder collapses static runs (the
     * majority of most sessions) to near-zero bytes, so 10fps keeps even a 10-minute session's
     * recording well under a megabyte.
     */
    private const val MUX_FPS = ScreencastFrameMux.DEFAULT_MUX_FPS

    /** Minimum spacing between stored frames — throttles bursts without visibly dropping motion. */
    private const val THROTTLE_MIN_INTERVAL_MS = 50L

    /**
     * Upper bound on stored frames (~28 minutes at the throttle rate). A backstop against a
     * runaway session exhausting disk, not an expected limit; drops past it are logged at stop.
     */
    private const val MAX_FRAMES = 34_000
  }
}
