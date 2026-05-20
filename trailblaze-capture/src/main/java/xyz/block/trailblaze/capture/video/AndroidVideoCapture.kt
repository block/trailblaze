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
 * Captures the device screen as an MP4 by attaching a [MuxToMp4Consumer] to the shared
 * per-device [H264Tee].
 *
 * The tee owns a single `adb exec-out screenrecord --output-format=h264` invocation, shared
 * with whatever else (e.g. the live `/devices` viewer) is also watching the device. This
 * avoids the encoder contention that two concurrent `screenrecord` invocations would cause
 * on most Android devices.
 *
 * Output file is `video.mp4` in the session directory — same path the prior on-device
 * `screenrecord` + `adb pull` + concat path produced. Downstream callers (sprite extractor,
 * UI `<video>` player) are unchanged.
 *
 * ### Compared to the previous implementation
 *  - **No /sdcard writes.** Bytes flow host-side from the start; the device's storage isn't
 *    touched. No `adb pull` step, no cleanup of stale segment files.
 *  - **No timer-driven segment chain.** Instead of guessing "10 s before the 3-min cap",
 *    the tee surfaces the producer transition deterministically and the consumer rolls to
 *    a fresh segment file on signal.
 *  - **Android 11+: no chaining at all.** `screenrecord --time-limit 0` runs the full session
 *    in one segment; the post-stop concat is then just a fast `-c copy` MP4 wrap.
 *
 * ### Limitations (unchanged from the prior implementation)
 *  - Some emulator images / GPU configurations refuse `screenrecord` outright. We don't
 *    distinguish that failure mode here — the consumer just reports "no segments captured"
 *    and the caller falls back accordingly.
 */
class AndroidVideoCapture : CaptureStream {
  override val type = CaptureType.VIDEO

  private var sessionDir: File? = null
  private var deviceId: String? = null
  private var startTimestampMs: Long = 0
  private var consumer: MuxToMp4Consumer? = null
  private var isLandscape: Boolean = false

  override fun start(sessionDir: File, deviceId: String, appId: String?) {
    this.sessionDir = sessionDir
    this.deviceId = deviceId
    this.startTimestampMs = DeviceClock.nowMs(deviceId)

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
    consumer = MuxToMp4Consumer(sessionDir = sessionDir, tee = tee).also { it.start() }
  }

  override fun stop(options: CaptureOptions): CaptureArtifact? {
    val dev = deviceId ?: return null
    val dir = sessionDir ?: return null
    val cons = consumer ?: return null
    consumer = null

    // Capture the recording-end wall-clock BEFORE `cons.stop()` — that call drains the H264
    // tee, ffmpeg-concats segments, and wraps the result into video.mp4, which can take
    // multiple seconds. The user-perceived "recording stopped" moment is now, not after
    // ffmpeg finalizes. The CaptureArtifact's endTimestampMs is what the report viewer uses
    // as the timeline endpoint, so capturing it post-wrap inflates the apparent recording
    // window and skews the wall-clock-vs-mp4 mismatch check in VideoSpriteExtractor.
    val endTimestampMs = DeviceClock.nowMs(dev)
    val videoFile = cons.stop() ?: return null

    // Sprite-sheet generation: same downstream call as before. The video file is now an MP4
    // produced by our local ffmpeg concat, so all the existing decode paths still apply.
    val spriteSheet =
      VideoSpriteExtractor.generateSpriteSheet(
        videoFile,
        fps = options.spriteFrameFps,
        frameHeight = options.spriteFrameHeight,
        webpQuality = options.spriteQuality,
        isLandscape = isLandscape,
        // screenrecord emits a raw H.264 elementary stream with no timing info, so the
        // ffmpeg `-c copy` wrap in MuxToMp4Consumer ends up synthesizing PTS at a default
        // 25fps that doesn't match wall-clock — VideoSpriteExtractor uses this expected
        // window to detect that mismatch and re-stamp before sprite extraction.
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

    // Fallback: keep the merged video file (sprite extraction failed or ffmpeg missing).
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
