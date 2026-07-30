package xyz.block.trailblaze.capture.video

/**
 * Pure timeline logic for muxing a damage-driven screencast into a wall-clock-accurate MP4.
 *
 * A CDP (web) / stream (mobile) screencast only emits a frame when the screen *changes*, so the
 * frames a recorder collects are unevenly spaced in wall-clock time — a burst during a page
 * transition, then nothing while the user reads a static page. Handing those frames straight to
 * ffmpeg would produce a video whose duration is "number of frames ÷ some default rate", with no
 * relationship to how long the session actually took. The report timeline aligns video playback
 * time to wall-clock event timestamps, so that mismatch makes the scrubber drift.
 *
 * This object turns `(frame arrival timestamps, session start, session end)` into an
 * [ffmpeg concat-demuxer](https://ffmpeg.org/ffmpeg-formats.html#concat-1) script where each
 * frame's `duration` is exactly how long that frame was on screen. Feeding that script through a
 * constant-frame-rate resample (`-vf fps=N`) yields an MP4 whose duration equals the session
 * wall-clock window — the same property Android's `VideoSpriteExtractor.maybeRestamp` recovers
 * after the fact, achieved here up front because we own the timestamps.
 *
 * Split out from [WebScreencastVideoCapture] so the timing arithmetic (the part that's easy to get
 * subtly wrong) is unit-testable without a browser, ffmpeg, or the filesystem.
 */
internal object ScreencastTimeline {

  /** One captured frame: the JPEG already written to [path], and when it arrived (host epoch ms). */
  internal data class Frame(val path: String, val capturedAtMs: Long)

  /**
   * Floor on a single frame's on-screen duration, in milliseconds. Two screencast frames can
   * arrive within the same millisecond during a burst; a `duration 0` entry makes the concat
   * demuxer drop the frame. 1ms is below the CFR resample step at any sane fps, so clamping here
   * never distorts the visible timeline — it just keeps every frame representable.
   */
  private const val MIN_FRAME_DURATION_MS = 1L

  /**
   * Duration of the trailing repeated-last-frame entry. The concat demuxer only honors a
   * `duration` directive when another `file` entry follows it, so the last real frame's on-screen
   * time is realized by repeating that file once more with a short tail. The value is arbitrary
   * (below one CFR step) — its only job is to give the preceding `duration` a successor to bind to.
   */
  private const val TRAILING_TAIL_MS = 40L

  /**
   * Builds the ffconcat script for [frames] spanning `[sessionStartMs, sessionEndMs]`, or `null`
   * when there are no frames to mux.
   *
   * Timing contract:
   *  - The first frame is anchored at video-time 0 even if it arrived after [sessionStartMs] (a
   *    screencast's first frame lands only once the pipeline warms up). This shows the earliest
   *    captured frame from the start rather than leaving a black gap, and keeps video-time 0 =
   *    session start so the report scrubber stays aligned.
   *  - Each interior frame's `duration` is the gap until the next frame.
   *  - The final frame's `duration` extends to [sessionEndMs] so a long static tail (the common
   *    "agent finished, screen quiet" ending) is represented at its true length instead of
   *    collapsing to one frame-time.
   *  - Frame times are forced monotonic non-decreasing; out-of-order or duplicate timestamps
   *    (clock jitter) collapse to a [MIN_FRAME_DURATION_MS] step rather than a negative duration.
   *
   * Paths are emitted single-quoted with embedded single-quotes escaped per the concat demuxer's
   * `'\''` convention, so a session directory containing a quote can't break the script.
   */
  internal fun buildConcatScript(
    frames: List<Frame>,
    sessionStartMs: Long,
    sessionEndMs: Long,
  ): String? {
    if (frames.isEmpty()) return null

    // Frame i's start offset from session start, forced to 0 for the first frame and clamped
    // monotonic so a backwards clock step never yields a negative gap.
    val relMs = LongArray(frames.size)
    var prev = 0L
    for (i in frames.indices) {
      val raw = if (i == 0) 0L else frames[i].capturedAtMs - sessionStartMs
      val clamped = maxOf(raw, prev)
      relMs[i] = clamped
      prev = clamped
    }

    val totalMs = maxOf(sessionEndMs - sessionStartMs, relMs.last() + MIN_FRAME_DURATION_MS)

    val sb = StringBuilder()
    sb.append("ffconcat version 1.0\n")
    for (i in frames.indices) {
      val nextRel = if (i < frames.size - 1) relMs[i + 1] else totalMs
      val durMs = maxOf(nextRel - relMs[i], MIN_FRAME_DURATION_MS)
      sb.append("file '").append(FfconcatScript.escapeConcatPath(frames[i].path)).append("'\n")
      sb.append("duration ").append(FfconcatScript.formatSeconds(durMs)).append('\n')
    }
    // Trailing repeat so the last real frame's `duration` above is honored (see TRAILING_TAIL_MS).
    sb.append("file '").append(FfconcatScript.escapeConcatPath(frames.last().path)).append("'\n")
    sb.append("duration ").append(FfconcatScript.formatSeconds(TRAILING_TAIL_MS)).append('\n')
    return sb.toString()
  }
}
