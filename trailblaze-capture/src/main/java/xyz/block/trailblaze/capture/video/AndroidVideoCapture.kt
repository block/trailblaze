package xyz.block.trailblaze.capture.video

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
 * Captures the device screen as an MP4 by attaching a [WallClockMp4MuxConsumer] to the shared
 * per-device [H264Tee].
 *
 * The tee owns a single `adb exec-out screenrecord --output-format=h264` invocation, shared
 * with whatever else (e.g. the live `/devices` viewer) is also watching the device. This
 * avoids the encoder contention that two concurrent `screenrecord` invocations would cause
 * on most Android devices.
 *
 * Output file is `video.mp4` in the session directory. Downstream callers (sprite extractor,
 * UI `<video>` player) are unchanged.
 *
 * ### Why the wall-clock mux (vs [MuxToMp4Consumer])
 * `screenrecord` emits a raw H.264 elementary stream with **no per-frame timing**. The old
 * [MuxToMp4Consumer] wrote it to `.h264` segments and `-c copy`-concatenated them at stop; the
 * resulting mp4 got synthetic constant-rate PTS (ffmpeg's default 25fps) that
 * [VideoSpriteExtractor.maybeRestamp] then had to *guess back* into wall-clock by spreading frames
 * uniformly. Uniform spreading is wrong whenever the real screen activity isn't uniform (idle
 * stretches, bursts of taps), so the report's Timeline showed frames that didn't match the step at
 * a given timestamp — most visibly on a long-running CI trail where one frozen frame ended up
 * covering ~43% of the timeline.
 *
 * [WallClockMp4MuxConsumer] instead pipes the live tee through `ffmpeg -use_wallclock_as_timestamps
 * 1 -c copy`, stamping each access unit with the host wall clock as it arrives — the same technique
 * the iOS baguette capture uses. The mp4's PTS are then genuinely wall-clock-spaced, `maybeRestamp`
 * becomes a no-op, and a session-log event lands on its true frame. See that class's kdoc for the
 * continuous-encode / monotonic-DTS assumption.
 *
 * ### Clock alignment
 * The mux stamps frames on the **host** clock, but Android session logs and screenshots are on the
 * **device** clock (see [DeviceClock]) — the two can differ by seconds on emulators. We measure the
 * host→device offset once at [start] and convert the mux's first/last frame epochs back to the
 * device clock for the artifact window, so `videoPositionMs = eventEpochMs - startTimestampMs`
 * stays correct.
 *
 * ### Limitations
 *  - Some emulator images / GPU configurations refuse `screenrecord` outright. We don't
 *    distinguish that failure mode here — the mux just reports no bytes captured and the caller
 *    falls back accordingly.
 *  - `screenrecord`'s AVC encoder is assumed to emit monotonic DTS (no B-frames), which the
 *    `-c copy` mux to MP4 requires; this holds for stock `screenrecord` but is worth validating
 *    on unusual OEM encoders.
 */
class AndroidVideoCapture : CaptureStream {
  override val type = CaptureType.VIDEO

  private var sessionDir: File? = null
  private var deviceId: String? = null
  private var startTimestampMs: Long = 0

  /**
   * `deviceClock - hostClock`, sampled once at [start]. Added to the mux's host-epoch frame
   * timestamps to express the video window on the device clock the session logs use.
   */
  private var deviceHostOffsetMs: Long = 0
  private var mux: WallClockMp4MuxConsumer? = null
  private var isLandscape: Boolean = false

  override fun start(sessionDir: File, deviceId: String, appId: String?) {
    this.sessionDir = sessionDir
    this.deviceId = deviceId
    // Sample the host clock right before the (adb round-trip) device-clock query so the offset
    // between them is accurate to within that round-trip — negligible against the multi-second
    // host/device clock differences this correction exists for.
    val hostStartMs = System.currentTimeMillis()
    this.startTimestampMs = DeviceClock.nowMs(deviceId)
    this.deviceHostOffsetMs = this.startTimestampMs - hostStartMs

    val trailblazeDeviceId = TrailblazeDeviceId(deviceId, TrailblazeDevicePlatform.ANDROID)

    // Query actual device dimensions for accurate recording size. Scales to ~720p on the
    // short side while preserving aspect ratio so tablets, foldables, and non-16:9 devices
    // record correctly.
    val dims = getDeviceDisplaySize(deviceId)
    val videoSize = if (dims != null) {
      isLandscape = dims.first > dims.second
      scaleToRecordingSize(dims.first, dims.second)
    } else {
      VIDEO_SIZE_FALLBACK
    }
    Console.log(
      "Android video recording: deviceSize=${dims ?: "unknown"}, videoSize=$videoSize, landscape=$isLandscape",
    )

    val tee = H264Tee.forDevice(trailblazeDeviceId, videoSize = videoSize, bitRate = BIT_RATE)
    mux = WallClockMp4MuxConsumer(outputFile = File(sessionDir, "video.mp4"), tee = tee)
      .also { it.start() }
  }

  override fun stop(options: CaptureOptions): CaptureArtifact? {
    val dir = sessionDir ?: return null
    val m = mux ?: return null
    mux = null

    // Draining + finalizing the mp4 happens inside stop(); the returned MuxResult carries the
    // host-clock epoch of the actual first and last frames written — a truer recording window
    // than a "stop was called at" timestamp.
    val result = m.stop()
    if (result == null) {
      Console.log("[AndroidVideoCapture] no video captured in ${dir.absolutePath}")
      return null
    }
    val videoFile = result.file

    // The mux stamps frames on the host clock; convert to the device clock the session logs use
    // so the report's `videoPositionMs = eventEpochMs - startTimestampMs` mapping stays correct.
    val startTimestampMs = result.firstFrameEpochMs + deviceHostOffsetMs
    val endTimestampMs = result.lastFrameEpochMs + deviceHostOffsetMs

    val spriteSheet =
      VideoSpriteExtractor.generateSpriteSheet(
        videoFile,
        fps = options.spriteFrameFps,
        frameHeight = options.spriteFrameHeight,
        webpQuality = options.spriteQuality,
        isLandscape = isLandscape,
        // Deliberately NO expectedDurationMs. The mp4 already carries genuine wall-clock PTS
        // (WallClockMp4MuxConsumer), so the extractor's `maybeRestamp` / coverage-gate machinery —
        // which exists only to repair/validate the OLD constant-rate MuxToMp4Consumer output — must
        // not run. Feeding it a duration here is actively wrong: the only window we have is the
        // byte-arrival span (firstFrameEpoch..lastFrameEpoch), which legitimately *overshoots* the
        // mp4's real PTS span whenever the session ends on a static screen (screenrecord stops
        // emitting distinct frames while trailing container/flush bytes still arrive, and the final
        // NAL isn't emitted as a frame until a following start code). That few-second mismatch was
        // read as "broken timing" and triggered a uniform re-stamp that threw away the correct
        // per-frame PTS — the exact Timeline-misalignment bug this whole change removes. With no
        // expected duration, the mp4's true wall-clock PTS flow straight through the fps sampler and
        // every frame keeps its real capture time. A capture with no decodable frames is still
        // caught downstream (empty-frames failure marker), independent of any expected duration.
      )
    if (spriteSheet != null) {
      return CaptureArtifact(
        file = spriteSheet,
        type = CaptureType.VIDEO_FRAMES,
        startTimestampMs = startTimestampMs,
        endTimestampMs = endTimestampMs,
      )
    }

    // When sprite extraction returned null specifically because the underlying mp4's timing
    // was broken beyond re-stamp recovery, don't fall back to emitting the raw VIDEO either —
    // downstream report generation would just re-process the same broken mp4 into the same
    // sparse sprite we're trying to avoid. Skip emission entirely and let the timeline fall back
    // to the per-step screenshot slideshow.
    if (VideoSpriteExtractor.shouldSkipVideoFallbackForBrokenMp4(dir, "AndroidVideoCapture")) {
      return null
    }

    // Fallback: keep the muxed video file (sprite extraction failed for an infra reason —
    // ffmpeg missing, sprite assembly crashed — but the mp4 itself is plausibly fine).
    return CaptureArtifact(
      file = videoFile,
      type = CaptureType.VIDEO,
      startTimestampMs = startTimestampMs,
      endTimestampMs = endTimestampMs,
    )
  }

  /**
   * Queries the Android device's current display size via `adb shell wm size`. Returns
   * (width, height) reflecting the current orientation, or null if the query fails.
   */
  private fun getDeviceDisplaySize(deviceId: String): Pair<Int, Int>? {
    return try {
      val output = AndroidHostAdbUtils.execAdbShellCommand(
        deviceId = TrailblazeDeviceId(deviceId, TrailblazeDevicePlatform.ANDROID),
        args = listOf("wm", "size"),
      ).trim()
      // Output format: "Physical size: 1080x1920" or "Override size: ..."
      // Use the last line (override takes precedence if present)
      val lastLine = output.lines().lastOrNull { it.contains("size:") } ?: return null
      val match = Regex("(\\d+)x(\\d+)").find(lastLine) ?: return null
      val w = match.groupValues[1].toIntOrNull() ?: return null
      val h = match.groupValues[2].toIntOrNull() ?: return null
      Pair(w, h)
    } catch (_: Exception) {
      null
    }
  }

  companion object {
    /** Fallback when we can't query the device's display size. */
    private const val VIDEO_SIZE_FALLBACK = "720x1280"
    /** Target for the short side when scaling down for recording. */
    private const val TARGET_SHORT_SIDE = 720
    private const val BIT_RATE = "4000000" // 4 Mbps

    /**
     * Scales the device's real display dimensions down so the short side is ~[TARGET_SHORT_SIDE]px.
     * Preserves the device's actual aspect ratio. Both dimensions are rounded to even numbers
     * (required by most video codecs). If the device is already at or below target, uses the
     * original dimensions.
     */
    fun scaleToRecordingSize(deviceWidth: Int, deviceHeight: Int): String {
      val shortSide = minOf(deviceWidth, deviceHeight)
      if (shortSide <= TARGET_SHORT_SIDE) return "${deviceWidth}x${deviceHeight}"
      val scale = TARGET_SHORT_SIDE.toDouble() / shortSide
      val w = (deviceWidth * scale).toInt().let { it - it % 2 } // round to even
      val h = (deviceHeight * scale).toInt().let { it - it % 2 }
      return "${w}x${h}"
    }
  }
}
