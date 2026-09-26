package xyz.block.trailblaze.capture.video

import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import xyz.block.trailblaze.capture.CaptureOptions
import xyz.block.trailblaze.capture.CaptureStream
import xyz.block.trailblaze.capture.model.CaptureArtifact
import xyz.block.trailblaze.capture.model.CaptureFilenames
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
 *  - [start] looks up a [WebScreencastFeedRegistry.Feed] for the device. **No feed and a
 *    [fallback] → delegate wholesale to it** (the report-export path, which drives a browser with
 *    no live screencast, takes this route so it keeps working unchanged). A feed present →
 *    subscribe to its JPEG frames, writing each (throttled) frame to a temp dir under the session
 *    directory stamped with its host-clock arrival time. With no [fallback], see below.
 *  - [stop] detaches, then muxes the collected frames into the recording named by [basename]
 *    (`video.webm` for the session's own device, VP9, in the process-wide [RecordingFormat]) via
 *    the ffmpeg concat demuxer at a constant frame rate (see [ScreencastTimeline] for the
 *    wall-clock timing model).
 *
 * Because this path never publishes a [PlaywrightVideoRecordDir] entry, the browser context is
 * built **without** `setRecordVideoDir` — the two recorders are mutually exclusive by construction,
 * so a session is never double-recorded.
 *
 * ### Following a browser with no fallback
 * A null [fallback] is for a browser that is already in use — a web companion of a multi-device
 * session. Playwright's recorder is a context-*creation* option, so reaching for it there means
 * rebuilding the context the trail is driving, mid-navigation. Instead [start] watches
 * [WebScreencastFeedRegistry] for the device and records whichever feed it has for as long as the
 * stream runs: a browser that launches after [start] is picked up when it registers, and one that
 * is relaunched is followed onto its new feed. A browser that never comes up records nothing.
 */
class WebScreencastVideoCapture(
  /** Playwright's own recorder, for a device with no feed at [start]; null to follow the feed instead. */
  private val fallback: CaptureStream? = PlaywrightVideoCapture(),
  /** Test seam: swap out the ffmpeg binary path. */
  private val ffmpegBinary: String = "ffmpeg",
  private val format: RecordingFormat = RecordingFormat.preferred,
  /**
   * Basename of the recording — [CaptureFilenames.VIDEO_BASENAME], or a companion's own
   * ([CaptureFilenames.companionVideoBasename]) so it lands beside the start device's recording in
   * the same session directory. The frame scratch directory is suffixed the same way.
   */
  private val basename: String = CaptureFilenames.VIDEO_BASENAME,
  /** How sharp the recording is encoded; [WebVideoQuality.ENV_VAR] picks it for the process. */
  private val quality: WebVideoQuality = WebVideoQuality.fromEnv(),
  /** Test seam: the scratch-disk budget for stored frames ([MAX_FRAME_BYTES] by default). */
  private val maxFrameBytes: Long = MAX_FRAME_BYTES,
) : CaptureStream {
  override val type: CaptureType get() = format.captureType

  private var sessionDir: File? = null
  private var framesDir: File? = null
  private var startTimestampMs: Long = 0
  private var subscription: AutoCloseable? = null

  /** The registry watch a stream with no [fallback] follows its device's feed through. */
  private var feedWatch: AutoCloseable? = null

  /** Guards [subscription] and [stopped] against a feed change landing while [stop] runs. */
  private val subscriptionLock = Any()
  private var stopped = false

  /** True when no screencast feed was registered at [start] and we handed off to [fallback]. */
  private var usingFallback = false

  private val frameIndex = AtomicInteger(0)
  private val framesLock = Any()
  private val frames = mutableListOf<ScreencastTimeline.Frame>()

  /**
   * Set under [framesLock] when [stop] takes its snapshot. Closing a subscription does not drain a
   * frame the feed already dispatched, so one can still arrive; it must not land after the snapshot
   * or race the scratch-dir cleanup.
   */
  private var framesClosed = false

  /** Last stored frame's arrival time; used to throttle bursty screencast output. */
  private var lastStoredAtMs: Long = 0
  private var droppedToThrottle = 0
  private var droppedToCap = 0
  private var storedFrameBytes = 0L

  override fun start(sessionDir: File, deviceId: String, appId: String?) {
    this.sessionDir = sessionDir
    this.startTimestampMs = System.currentTimeMillis()
    sessionDir.mkdirs()

    if (fallback == null) {
      framesDir = File(sessionDir, framesSubdir).apply { mkdirs() }
      feedWatch = WebScreencastFeedRegistry.watch(deviceId) { feed -> follow(deviceId, feed) }
      return
    }

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

    val dir = File(sessionDir, framesSubdir).apply { mkdirs() }
    framesDir = dir
    subscription = feed.subscribe { jpeg, hostTimestampMs -> onFrame(jpeg, hostTimestampMs) }
    Console.log(
      "[WebScreencastVideoCapture] recording from live screencast for deviceId=$deviceId " +
        "into ${dir.absolutePath}",
    )
  }

  /** Moves the subscription onto [feed], the device's feed as of now (null: its browser is gone). */
  private fun follow(deviceId: String, feed: WebScreencastFeedRegistry.Feed?) {
    val attached = synchronized(subscriptionLock) {
      if (stopped) return
      subscription?.let { runCatching { it.close() } }
      subscription = null
      if (feed == null) return@synchronized false
      try {
        subscription = feed.subscribe { jpeg, hostTimestampMs -> onFrame(jpeg, hostTimestampMs) }
        true
      } catch (e: Exception) {
        Console.log(
          "[WebScreencastVideoCapture] could not attach $basename to the screencast of " +
            "deviceId=$deviceId: $e",
        )
        return
      }
    }
    Console.log(
      "[WebScreencastVideoCapture] " +
        (if (attached) "following the screencast" else "waiting for a screencast") +
        " of deviceId=$deviceId for $basename",
    )
  }

  private fun onFrame(jpeg: ByteArray, hostTimestampMs: Long) {
    val dir = framesDir ?: return
    synchronized(framesLock) {
      if (framesClosed) return
      // Throttle bursty runs: a page transition can emit many frames within a few ms. One frame
      // per THROTTLE_MIN_INTERVAL_MS is smooth enough for a report scrubber while bounding count.
      // The first frame is always kept (lastStoredAtMs == 0).
      if (lastStoredAtMs != 0L && hostTimestampMs - lastStoredAtMs < THROTTLE_MIN_INTERVAL_MS) {
        droppedToThrottle++
        return
      }
      if (frames.size >= MAX_FRAMES || storedFrameBytes + jpeg.size > maxFrameBytes) {
        // Hard caps so a pathological multi-hour session can't exhaust disk. Not silent — logged
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
      storedFrameBytes += jpeg.size
      lastStoredAtMs = hostTimestampMs
    }
  }

  override fun stop(options: CaptureOptions): CaptureArtifact? {
    if (usingFallback) return fallback?.stop(options)

    // Detach first so no frame lands after we snapshot the list. Marking the stream stopped first
    // makes a feed change landing mid-stop a no-op, and a second stop a no-op too.
    val attached = synchronized(subscriptionLock) {
      if (stopped) return null
      stopped = true
      subscription.also { subscription = null }
    }
    runCatching { feedWatch?.close() }
    feedWatch = null
    runCatching { attached?.close() }
    val endTimestampMs = System.currentTimeMillis()

    val dir = sessionDir ?: return null
    val frameSnapshot = synchronized(framesLock) {
      framesClosed = true
      frames.toList()
    }
    if (droppedToThrottle > 0 || droppedToCap > 0) {
      Console.log(
        "[WebScreencastVideoCapture] $basename captured ${frameSnapshot.size} frames " +
          "(throttled $droppedToThrottle, over-cap $droppedToCap)",
      )
    }
    if (frameSnapshot.isEmpty()) {
      Console.log("[WebScreencastVideoCapture] no screencast frames captured for $basename in ${dir.absolutePath}")
      cleanupFramesDir()
      return null
    }

    val output = File(dir, format.filename(basename))
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
   * with the Android screencap fallback — with the codec settings tuned for web pages
   * ([RecordingFormat.webScreencastEncodeArgs]).
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
    encodeArgs = format.webScreencastEncodeArgs(quality),
    ffmpegBinary = ffmpegBinary,
    logTag = "WebScreencastVideoCapture",
    muxFps = MUX_FPS,
  )

  private fun cleanupFramesDir() {
    framesDir?.let { dir -> runCatching { dir.deleteRecursively() } }
    framesDir = null
  }

  /** Per-recording, so two browsers recorded into one session directory keep their frames apart. */
  private val framesSubdir: String = CaptureFilenames.framesScratchDir(SCREENCAST_FRAMES_SUBDIR, basename)

  companion object {
    private const val SCREENCAST_FRAMES_SUBDIR = ".trailblaze-screencast-frames"

    /**
     * Constant output frame rate for the muxed recording. The report timeline aligns by wall-clock,
     * so a modest rate is enough for smooth scrubbing, and the encoder collapses static runs
     * (the majority of most sessions) to near-zero bytes.
     */
    private const val MUX_FPS = ScreencastFrameMux.DEFAULT_MUX_FPS

    /** Minimum spacing between stored frames — throttles bursts without visibly dropping motion. */
    private const val THROTTLE_MIN_INTERVAL_MS = 50L

    /**
     * Upper bound on stored frames (~28 minutes at the throttle rate). A backstop against a
     * runaway session exhausting disk, not an expected limit; drops past it are logged at stop.
     */
    private const val MAX_FRAMES = 34_000

    /**
     * Upper bound on stored frame bytes, alongside [MAX_FRAMES]: the count cap alone doesn't bound
     * disk, because frame size follows the screencast's JPEG quality. 1.5 GB is what the count cap
     * allowed at the old q60 frames (~40 KB); at the recording's q95 (~75 KB) a page that changes
     * every frame reaches it after ~17 minutes, and a typical session, which is mostly static and
     * emits no frames while still, never does.
     */
    private const val MAX_FRAME_BYTES = 1_500_000_000L
  }
}
