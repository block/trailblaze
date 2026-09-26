package xyz.block.trailblaze.capture.video

import java.io.File
import xyz.block.trailblaze.capture.CaptureOptions
import xyz.block.trailblaze.capture.CaptureStream
import xyz.block.trailblaze.capture.model.CaptureArtifact
import xyz.block.trailblaze.capture.model.CaptureFilenames
import xyz.block.trailblaze.capture.model.CaptureType
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.util.AndroidHostAdbUtils
import xyz.block.trailblaze.util.Console

/**
 * Records the device screen by attaching a [WallClockMuxConsumer] to the shared per-device
 * [H264Tee].
 *
 * The tee owns a single `adb exec-out screenrecord --output-format=h264` invocation, shared
 * with whatever else (e.g. the live `/devices` viewer) is also watching the device. This
 * avoids the encoder contention that two concurrent `screenrecord` invocations would cause
 * on most Android devices.
 *
 * ### What it writes
 * The session recording in the process-wide [RecordingFormat]: `video.webm`, a VP9 WebM encoded
 * **live** as the frames arrive, so it is complete the moment the session stops and the HTML report
 * can embed and play it as the session's timeline. It is the session's only video artifact. On a
 * host whose ffmpeg has no VP9 encoder the recording is `video.mp4` instead (`-c copy`, no encode);
 * the report plays that too.
 *
 * ### Why the wall-clock mux (vs [MuxToMp4Consumer])
 * `screenrecord` emits a raw H.264 elementary stream with **no per-frame timing**. The old
 * [MuxToMp4Consumer] wrote it to `.h264` segments and `-c copy`-concatenated them at stop; the
 * resulting mp4 got synthetic constant-rate PTS (ffmpeg's default 25fps) that a later pass had to
 * *guess back* into wall-clock by spreading frames uniformly. Uniform spreading is wrong whenever
 * the real screen activity isn't uniform (idle stretches, bursts of taps), so the report's Timeline
 * showed frames that didn't match the step at a given timestamp — most visibly on a long-running CI
 * trail where one frozen frame ended up covering ~43% of the timeline.
 *
 * [WallClockMuxConsumer] instead pipes the live tee through `ffmpeg -use_wallclock_as_timestamps 1`,
 * stamping each access unit with the host wall clock as it arrives — the same technique the iOS
 * baguette capture uses. The file's PTS are then genuinely wall-clock-spaced and a session-log event
 * lands on its true frame. See that class's kdoc for the continuous-encode / monotonic-DTS
 * assumption.
 *
 * ### Clock alignment
 * The recording window is the **host** clock epochs the mux stamped its first and last frames with,
 * unconverted. An Android session mixes clocks — host-stamped runner logs beside device-stamped tool
 * logs, and the device's clock can sit seconds off on an emulator — so every consumer that places an
 * event on the recording (the HTML report, the desktop app) first puts the whole session on the host
 * clock via `normalizedToHostClock`, which derives each device's offset from the `hostReceivedAt`
 * ingestion anchors. The event's epoch and the window it is placed against are therefore both
 * host-clock. (The report maps an event onto the recording by SCALING clip time onto that window
 * — `videoClipTimeAt`, duration/window — not by subtracting its start.) Converting the window to the device clock here would re-introduce exactly the skew the
 * readers just removed.
 *
 * ### How closely the recording tracks the screen
 * Measured on an emulator against a page painting its own millisecond clock: a frame stamped at
 * host time `T` shows the screen as it was **60–100 ms earlier** — the device's encode plus the adb
 * hop — and that offset wanders within a session by roughly one 17 ms frame interval. Elapsed time
 * *inside* the recording tracks real elapsed time to within a few ms, so a step lands on the right
 * frame; do not expect accuracy to the individual frame.
 *
 * ### Limitations
 *  - Some images have no `screenrecord` binary at all, and some emulator / GPU configurations
 *    refuse it outright. The first case is detected before recording starts
 *    ([AndroidScreenrecordSupport]) and handed to [AndroidScreencapVideoCapture]; the second is
 *    not distinguished here — the mux just reports no bytes captured and the empty file is
 *    discarded.
 *  - `screenrecord`'s AVC encoder is assumed to emit monotonic DTS (no B-frames), which both mux
 *    outputs require; this holds for stock `screenrecord` but is worth validating on unusual OEM
 *    encoders.
 */
class AndroidVideoCapture(
  /**
   * Test seam: builds the mux the recording is written through. Swapping in a fake exercises the
   * start/stop routing — and the clock the artifact window is on — with no ffmpeg and no device.
   */
  private val muxFactory: (
    outputFile: File,
    tee: H264Tee,
    output: WallClockMuxConsumer.Output,
  ) -> WallClockVideoMux = { outputFile, tee, output -> WallClockMuxConsumer(outputFile, tee, output) },
  /**
   * Basename of the recording written into the session directory: `video` for the session's own
   * device, `video-<name>` for a companion in a multi-device session, so two displays recorded into
   * one session directory do not write over each other.
   */
  private val basename: String = CaptureFilenames.VIDEO_BASENAME,
  /** Records the session when the device's firmware has no `screenrecord` to stream from. */
  private val fallback: CaptureStream = AndroidScreencapVideoCapture(basename = basename),
  /** Test seam: whether this device can stream its screen at all. */
  private val screenrecordAvailable: (TrailblazeDeviceId) -> Boolean = {
    AndroidScreenrecordSupport.isAvailable(it)
  },
  /** Test seam: the host clock the stop request is read from. */
  private val nowMs: () -> Long = System::currentTimeMillis,
  /** Test seam: holds a recording's last frame out to a clip time; see [RecordingTailHold]. */
  private val holdLastFrame: (File, Long) -> Boolean = { file, untilMs -> RecordingTailHold.holdLastFrame(file, untilMs) },
) : CaptureStream {

  override val type: CaptureType get() = format.captureType

  private var sessionDir: File? = null
  private var deviceId: String? = null
  private var mux: WallClockVideoMux? = null
  private var format: RecordingFormat = RecordingFormat.MP4

  /** True when the device had no `screenrecord` and [fallback] owns this session's recording. */
  private var usingFallback = false

  override fun start(sessionDir: File, deviceId: String, appId: String?) {
    this.sessionDir = sessionDir
    this.deviceId = deviceId

    val trailblazeDeviceId = TrailblazeDeviceId(deviceId, TrailblazeDevicePlatform.ANDROID)

    if (!screenrecordAvailable(trailblazeDeviceId)) {
      // Nothing to stream from. Sampling screenshots is slower and coarser, but it produces the
      // same artifact, so the session is recorded rather than silently left without footage.
      usingFallback = true
      fallback.start(sessionDir, deviceId, appId)
      return
    }

    // Query actual device dimensions for accurate recording size. Scales to ~720p on the
    // short side while preserving aspect ratio so tablets, foldables, and non-16:9 devices
    // record correctly.
    val dims = getDeviceDisplaySize(deviceId)
    val videoSize = if (dims != null) {
      scaleToRecordingSize(dims.first, dims.second)
    } else {
      VIDEO_SIZE_FALLBACK
    }
    format = RecordingFormat.preferred
    Console.log(
      "Android video recording: deviceSize=${dims ?: "unknown"}, videoSize=$videoSize, " +
        "format=$format",
    )

    val tee = H264Tee.forDevice(trailblazeDeviceId, videoSize = videoSize, bitRate = BIT_RATE)
    mux = muxFactory(
      File(sessionDir, format.filename(basename)),
      tee,
      format.liveMuxOutput(),
    ).also { it.start() }
  }

  override fun stop(options: CaptureOptions): CaptureArtifact? {
    if (usingFallback) return fallback.stop(options)
    val dir = sessionDir ?: return null
    val m = mux ?: return null
    mux = null
    // Read before the drain: the session ends here, not when ffmpeg finishes finalizing.
    val stopRequestedAtMs = nowMs()

    // Draining + finalizing the file happens inside stop(); the returned MuxResult carries the
    // host-clock epoch of the actual first and last frames written — a truer recording window
    // than a "stop was called at" timestamp.
    val result = m.stop()
    if (result == null) {
      Console.log("[AndroidVideoCapture] no video captured in ${dir.absolutePath}")
      // ffmpeg creates its output the moment it is spawned, so a device that fed the pipe nothing
      // leaves a zero-byte `video.webm` in the session directory. Nothing registers it — the
      // artifact list, and so `capture_metadata.json`, skip it — but it still ships inside the
      // session's log zip, where it reads as a recording that will not play. Measured on an
      // OEM image that ships no `screenrecord` binary: the shell's "not found" lands on
      // STDOUT, inside the H.264 pipe, and the command still exits 0 — nothing upstream notices.
      deleteEmptyRecording(File(dir, format.filename(basename)))
      return null
    }
    val videoFile = result.file

    // Host-clock epochs, straight from the mux — the clock every reader of this window has already
    // normalized the session's logs onto. See "Clock alignment" in the class doc.
    val startTimestampMs = result.firstFrameEpochMs
    var endTimestampMs = result.lastFrameEpochMs

    // screenrecord sends nothing while the screen is still, so a session that ends on a still
    // screen stops its file at the last change — seconds or minutes before the session did, and the
    // report then has no footage for any step after it. Hold the last frame out to the stop so the
    // file and its window cover the whole session.
    // Only while the feed is alive, though: a feed that died (screenrecord or adb gone — the crash
    // and ANR runs) also stops at its last frame, and holding it would show a healthy screen for
    // the failure. Its file ends where the evidence ends, and the report falls back to screenshots.
    if (stopRequestedAtMs - endTimestampMs >= RecordingTailHold.MIN_HOLD_MS) {
      if (!result.feedAlive) {
        Console.log("[AndroidVideoCapture] feed died before stop; not holding ${videoFile.name}'s last frame")
      } else if (holdLastFrame(videoFile, stopRequestedAtMs - startTimestampMs)) {
        endTimestampMs = stopRequestedAtMs
      }
    }

    return CaptureArtifact(
      file = videoFile,
      type = format.captureType,
      startTimestampMs = startTimestampMs,
      endTimestampMs = endTimestampMs,
    )
  }

  /** Removes a recording file that exists but holds nothing, so no reader mistakes it for footage. */
  private fun deleteEmptyRecording(recording: File) {
    if (!recording.isFile || recording.length() > 0L) return
    if (recording.delete()) {
      Console.log("[AndroidVideoCapture] removed empty ${recording.name}")
    }
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
