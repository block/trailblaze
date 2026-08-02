package xyz.block.trailblaze.capture.video

import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import xyz.block.trailblaze.capture.CaptureOptions
import xyz.block.trailblaze.capture.CaptureStream
import xyz.block.trailblaze.capture.model.CaptureArtifact
import xyz.block.trailblaze.capture.model.CaptureType
import xyz.block.trailblaze.util.Console

/**
 * `CaptureStream` for Playwright-driven web / Electron sessions that records `video.mp4` from the
 * **same live screencast** feeding the `/devices` viewer and the stream-sourced agent screenshots,
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
 *    keeps only the most recent). One screencast → one continuous MP4.
 *
 * ### Lifecycle
 *  - [start] looks up a [WebScreencastFeedRegistry.Feed] for the device. **No feed → delegate
 *    wholesale to [fallback]** (the report-export path, which drives a browser with no live
 *    screencast, takes this route so it keeps working unchanged). A feed present → subscribe to
 *    its JPEG frames, writing each (throttled) frame to a temp dir under the session directory
 *    stamped with its host-clock arrival time.
 *  - [stop] detaches, then muxes the collected frames into `video.mp4` via the ffmpeg concat
 *    demuxer at a constant frame rate (see [ScreencastTimeline] for the wall-clock timing model),
 *    and extracts the timeline sprite sheet exactly as [PlaywrightVideoCapture] does.
 *
 * Because this path never publishes a [PlaywrightVideoRecordDir] entry, the browser context is
 * built **without** `setRecordVideoDir` — the two recorders are mutually exclusive by construction,
 * so a session is never double-recorded.
 */
class WebScreencastVideoCapture(
  private val fallback: CaptureStream = PlaywrightVideoCapture(),
  /** Test seam: swap out the ffmpeg binary path. */
  private val ffmpegBinary: String = "ffmpeg",
) : CaptureStream {
  override val type = CaptureType.VIDEO

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

    val mp4 = File(dir, "video.mp4")
    val muxed = muxFramesToMp4(frameSnapshot, mp4, startTimestampMs, endTimestampMs)
    cleanupFramesDir()
    if (muxed == null) {
      Console.log("[WebScreencastVideoCapture] ffmpeg mux produced no video.mp4 in ${dir.absolutePath}")
      return null
    }

    val spriteSheet = VideoSpriteExtractor.generateSpriteSheet(
      muxed,
      fps = options.spriteFrameFps,
      // Web timeline frames render in a large pane — use the web-tuned height/quality so the
      // scrubber isn't grainy (same choice as PlaywrightVideoCapture).
      frameHeight = options.webSpriteFrameHeight(),
      webpQuality = options.webSpriteQuality(),
      isLandscape = true,
      // The mux resamples to a constant frame rate spanning the exact wall-clock window, so the
      // sprite extractor's duration sanity-check is a no-op here; pass the window so any future
      // mux regression self-corrects.
      expectedDurationMs = endTimestampMs - startTimestampMs,
    )
    if (spriteSheet != null) {
      return CaptureArtifact(
        file = spriteSheet,
        type = CaptureType.VIDEO_FRAMES,
        startTimestampMs = startTimestampMs,
        endTimestampMs = endTimestampMs,
      )
    }
    if (VideoSpriteExtractor.shouldSkipVideoFallbackForBrokenMp4(muxed.parentFile, "WebScreencastVideoCapture")) {
      return null
    }
    return CaptureArtifact(
      file = muxed,
      type = CaptureType.VIDEO,
      startTimestampMs = startTimestampMs,
      endTimestampMs = endTimestampMs,
    )
  }

  /**
   * Muxes [frames] into [output] via the ffmpeg concat demuxer. The per-frame `duration` script
   * ([ScreencastTimeline.buildConcatScript]) carries the wall-clock timing; the `-vf fps` resample
   * converts that variable-rate timeline into a constant-frame-rate H.264 stream whose duration
   * equals the session window. `-c copy` isn't an option here (the inputs are JPEGs, not an H.264
   * elementary stream like Android's `screenrecord` tee), so this is a real encode — but the
   * `veryfast` preset over a few dozen deduplicated frames finishes well under a second.
   */
  private fun muxFramesToMp4(
    frames: List<ScreencastTimeline.Frame>,
    output: File,
    sessionStartMs: Long,
    sessionEndMs: Long,
  ): File? {
    val script = ScreencastTimeline.buildConcatScript(frames, sessionStartMs, sessionEndMs) ?: return null
    val dir = output.parentFile ?: return null
    val listFile = File(dir, "video.screencast.concat.txt")
    try {
      listFile.writeText(script)
    } catch (e: Exception) {
      Console.log("[WebScreencastVideoCapture] failed to write concat script: ${e.message}")
      return null
    }
    val result = runSubprocessWithTimeout(
      command = listOf(
        ffmpegBinary,
        "-y",
        "-f", "concat",
        "-safe", "0",
        "-i", listFile.absolutePath,
        // Resample the variable-rate image timeline to CFR so the container duration matches the
        // wall-clock window. Without this, libx264 stamps a default rate and the duration is
        // meaningless (frames ÷ 25) — the exact mismatch VideoSpriteExtractor.maybeRestamp exists
        // to paper over on Android.
        "-vf", "fps=$MUX_FPS",
        "-c:v", "libx264",
        "-preset", "veryfast",
        "-crf", "23",
        "-pix_fmt", "yuv420p",
        "-movflags", "+faststart",
        output.absolutePath,
      ),
      timeoutSeconds = FFMPEG_TIMEOUT_SECONDS,
    )
    runCatching { listFile.delete() }
    if (result == null) {
      Console.log("[WebScreencastVideoCapture] ffmpeg mux could not run or timed out after ${FFMPEG_TIMEOUT_SECONDS}s")
      return null
    }
    if (result.exitCode != 0 || output.length() == 0L) {
      Console.log(
        "[WebScreencastVideoCapture] ffmpeg mux failed: exit=${result.exitCode}\n" +
          sanitizeSubprocessOutputForLog(result.output),
      )
      return null
    }
    return output
  }

  private fun cleanupFramesDir() {
    framesDir?.let { dir -> runCatching { dir.deleteRecursively() } }
    framesDir = null
  }

  companion object {
    private const val SCREENCAST_FRAMES_SUBDIR = ".trailblaze-screencast-frames"

    /**
     * Constant output frame rate for the muxed MP4. The report timeline aligns by wall-clock, so
     * a modest rate is enough for smooth scrubbing; libx264 collapses static runs (the majority of
     * most sessions) to near-zero bytes, so 10fps keeps even a 10-minute session's `video.mp4`
     * well under a megabyte.
     */
    private const val MUX_FPS = 10

    /** Minimum spacing between stored frames — throttles bursts without visibly dropping motion. */
    private const val THROTTLE_MIN_INTERVAL_MS = 50L

    /**
     * Upper bound on stored frames (~28 minutes at the throttle rate). A backstop against a
     * runaway session exhausting disk, not an expected limit; drops past it are logged at stop.
     */
    private const val MAX_FRAMES = 34_000

    private const val FFMPEG_TIMEOUT_SECONDS = 120L
  }
}
