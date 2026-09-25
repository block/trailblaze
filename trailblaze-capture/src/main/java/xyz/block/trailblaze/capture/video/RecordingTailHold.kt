package xyz.block.trailblaze.capture.video

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import xyz.block.trailblaze.util.Console

/**
 * Holds a recording's last frame out to the moment its session stopped — the WebM, or the mp4 a
 * host without libvpx records instead.
 *
 * A damage-driven feed (`screenrecord`, baguette) emits frames only while the screen changes, so a
 * session that spends its tail on a still screen — waiting on an LLM, asserting, reading the
 * hierarchy — ends its file at the last change. Measured in CI: a 23 s eval came out as
 * a 2.2 s clip, a 16 s "capture screen state" run as 0.16 s, and a static run as one 40 ms frame.
 * The report places a step by its time inside the recording, so every step after the last change
 * fell back to a screenshot, and the Video tab played a second of footage for the whole session.
 *
 * Holding the last frame is what the screen actually did, so the file is re-encoded with one clone
 * of its final frame stamped at [untilMs]; a player shows the previous frame until then. Every
 * earlier frame keeps its own timestamp (`-fps_mode passthrough`). One clone, not a clone per
 * frame interval: `tpad` alone would write 25 frames a second for however long the tail lasted.
 */
object RecordingTailHold {

  /** A tail shorter than this is encoder latency, not a still screen, and is left alone. */
  const val MIN_HOLD_MS: Long = 250L

  /** Slack for the container's own rounding when checking the result reaches [untilMs]. */
  private const val DURATION_TOLERANCE_MS: Long = 100L

  /**
   * Millisecond encoder time base. Left to its default the VP9 encoder derives one from the
   * stream's declared 25 fps and snaps every frame onto a 40 ms grid (0.350 s came out 0.360 s);
   * a WebM input is already on milliseconds, so 1:1000 rounds nothing.
   */
  internal val ENCODER_TIME_BASE = listOf("-enc_time_base", "1:1000")

  /** Bound on the re-encode: realtime VP9 runs well over 100x real time, so this is a wedge guard. */
  private const val TIMEOUT_SECONDS: Long = 120L

  /**
   * Re-encodes [file] in place so it runs to [untilMs] (clip time). True when it now does; false
   * leaves [file] exactly as it was — a missing ffprobe/ffmpeg, a failed encode or a result that
   * doesn't reach [untilMs] costs the held tail, never the recording.
   */
  fun holdLastFrame(
    file: File,
    untilMs: Long,
    ffmpegBinary: String = "ffmpeg",
    ffprobeBinary: String = "ffprobe",
  ): Boolean {
    val frames = frameCount(file, ffprobeBinary) ?: return false
    val held = File(file.parentFile, "${file.nameWithoutExtension}.held.${file.extension}")
    val result = runSubprocessWithTimeout(
      listOf(ffmpegBinary, "-y", "-hide_banner", "-loglevel", "error", "-i", file.absolutePath, "-an", "-vf", filter(frames, untilMs)) +
        encodeArgsFor(file) + ENCODER_TIME_BASE + held.absolutePath,
      timeoutSeconds = TIMEOUT_SECONDS,
    )
    val durationMs = if (result?.exitCode == 0 && held.isFile) VideoDuration.probeMs(held, ffprobeBinary) else null
    if (durationMs == null || durationMs < untilMs - DURATION_TOLERANCE_MS) {
      Console.log(
        "[RecordingTailHold] could not hold ${file.name} to ${untilMs}ms (exit=${result?.exitCode}, " +
          "duration=$durationMs): ${sanitizeSubprocessOutputForLog(result?.output.orEmpty())}",
      )
      held.delete()
      return false
    }
    return try {
      Files.move(held.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
      true
    } catch (e: Exception) {
      // Runs inside a recorder's stop(): a failed swap costs the held tail, never the stop.
      Console.log("[RecordingTailHold] could not replace ${file.name} with its held copy: ${e.message}")
      held.delete()
      false
    }
  }

  /**
   * [holdLastFrame] for a recorder that cannot see its own last frame (simctl hands back a finished
   * file): reads how long [file] runs and holds it to [windowMs] only when it falls short by more
   * than [MIN_HOLD_MS]. True when the file now runs the window, false when it may not — an
   * unreadable duration is left alone, since nothing says the tail is missing.
   */
  fun holdToWindow(
    file: File,
    windowMs: Long,
    probeMs: (File) -> Long? = { VideoDuration.probeMs(it) },
    hold: (File, Long) -> Boolean = { f, untilMs -> holdLastFrame(f, untilMs) },
  ): Boolean {
    val durationMs = probeMs(file) ?: return false
    if (windowMs - durationMs < MIN_HOLD_MS) return true
    return hold(file, windowMs)
  }

  /**
   * The re-encode keeps [file]'s own container, read off its extension. The mp4 args leave timing
   * to the muxer, so passthrough is added here: the held clone must keep its stamp, not be padded
   * out to a constant rate. B-frames are off because x264 reorders the far-off clone ahead of the
   * frames before it, and the mp4 then reads 0.8 s long for a 6 s hold.
   */
  internal fun encodeArgsFor(file: File): List<String> =
    if (file.extension.equals(RecordingFormat.MP4.fileExtension, ignoreCase = true)) {
      RecordingFormat.MP4.encodeArgs() + listOf("-fps_mode", "passthrough", "-bf", "0")
    } else {
      RecordingFormat.WEBM.encodeArgs()
    }

  /**
   * Clones the final frame once, then moves only that clone to [untilMs]. Frames are counted from
   * zero, so the clone is frame [frames]. Internal so the graph is assertable without ffmpeg.
   */
  internal fun filter(frames: Int, untilMs: Long): String =
    "tpad=stop_mode=clone:stop=1,setpts='if(gte(N,$frames),${untilMs / 1000.0}/TB,PTS)'"

  /**
   * Frames in [file], read from the demuxer without decoding. The live encode runs with no
   * lookahead (`-lag-in-frames 0`), so it writes no hidden frames and packets are frames.
   */
  private fun frameCount(file: File, ffprobeBinary: String): Int? {
    val result = runSubprocessWithTimeout(
      listOf(ffprobeBinary, "-v", "error", "-select_streams", "v:0", "-count_packets", "-show_entries", "stream=nb_read_packets", "-of", "csv=p=0", file.absolutePath),
      timeoutSeconds = TIMEOUT_SECONDS,
    ) ?: return null
    if (result.exitCode != 0) return null
    return result.output.trim().lineSequence().firstOrNull()?.trim()?.toIntOrNull()?.takeIf { it > 0 }
  }
}
