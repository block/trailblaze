package xyz.block.trailblaze.capture.video

import java.io.File
import java.util.Locale

/**
 * Cuts a window out of a finished recording, re-encoded with the settings every Trailblaze
 * recording in its container uses. Re-encoded rather than stream-copied because a copy can only start on a
 * keyframe, seconds before the request; a decode starts on the frame asked for.
 */
object VideoSlice {

  /** Encoding a window of a recording takes seconds; this bounds a wedged ffmpeg. */
  private const val TIMEOUT_SECONDS = 600L

  /**
   * Encodes [durationMs] of [source] from [offsetMs] into [output], video only. `-ss` is an input
   * option, so the first frame written is the first one at or after the offset; `-t` is an output
   * option, so the cut ends on clip time. Returns false, leaving no partial file, when ffmpeg fails.
   */
  fun slice(source: File, offsetMs: Long, durationMs: Long, output: File, ffmpegBinary: String = "ffmpeg"): Boolean {
    val result = runSubprocessWithTimeout(command(source, offsetMs, durationMs, output, ffmpegBinary), timeoutSeconds = TIMEOUT_SECONDS)
    if (result?.exitCode != 0 || !output.isFile || output.length() == 0L) {
      output.delete()
      return false
    }
    // A screen that stopped changing ends the cut at its last frame, short of the window.
    RecordingTailHold.holdToWindow(output, durationMs)
    return true
  }

  /** In [output]'s own container, each frame keeping its stamp, as [RecordingTailHold] re-encodes. */
  internal fun command(source: File, offsetMs: Long, durationMs: Long, output: File, ffmpegBinary: String): List<String> =
    listOf(
      ffmpegBinary, "-y", "-v", "error",
      "-ss", seconds(offsetMs),
      // simctl's own mp4, which a session keeps when it isn't transcoded, needs it as the transcode does.
      "-fflags", "+igndts",
      "-i", source.absolutePath,
      "-t", seconds(durationMs),
      "-an",
    ) + RecordingTailHold.formatFor(output).videoFilterArgs() + RecordingTailHold.encodeArgsFor(output) +
      RecordingTailHold.ENCODER_TIME_BASE + output.absolutePath

  /** How long [file] is, in milliseconds, or null when ffprobe can't tell. */
  fun durationMs(file: File, ffprobeBinary: String = "ffprobe"): Long? = VideoDuration.probeMs(file, ffprobeBinary)

  private fun seconds(ms: Long): String = String.format(Locale.ROOT, "%d.%03d", ms / 1000, ms % 1000)
}
