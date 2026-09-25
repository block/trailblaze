package xyz.block.trailblaze.capture.video

import java.io.File
import xyz.block.trailblaze.util.Console

/**
 * Encodes a sequence of timestamped still frames into a session recording.
 *
 * Shared by the two recorders that collect frames rather than a video stream: the web screencast
 * ([WebScreencastVideoCapture]) and the Android screencap fallback
 * ([AndroidScreencapVideoCapture]). Both face the same problem — frames unevenly spaced in
 * wall-clock time — and both need the same property out of it: a container whose duration equals
 * the session window, because the report places an event on the recording by scaling into that
 * window. [ScreencastTimeline] carries the per-frame timing; the constant-frame-rate resample
 * below turns it into a duration the container can state.
 *
 * `-c copy` is never an option here: the inputs are stills, not an H.264 elementary stream like
 * the `screenrecord` tee's, so this is a real encode in the [RecordingFormat]'s codec. Over the
 * few dozen deduplicated frames a session usually yields it finishes in about a second.
 */
internal object ScreencastFrameMux {

  /**
   * Constant output frame rate for the muxed recording. The report timeline aligns by wall-clock,
   * so a modest rate is enough for smooth scrubbing; the encoder collapses static runs (the
   * majority of most sessions) to near-zero bytes, so 10fps keeps even a 10-minute session's
   * recording well under a megabyte.
   */
  const val DEFAULT_MUX_FPS = 10

  private const val FFMPEG_TIMEOUT_SECONDS = 120L

  /**
   * Muxes [frames] into [output], returning it, or null when the encode could not be run or
   * produced nothing. [logTag] names the calling recorder in this object's log lines so a failure
   * is attributable to the recorder that owned the frames.
   */
  fun mux(
    frames: List<ScreencastTimeline.Frame>,
    output: File,
    sessionStartMs: Long,
    sessionEndMs: Long,
    format: RecordingFormat,
    ffmpegBinary: String,
    logTag: String,
    muxFps: Int = DEFAULT_MUX_FPS,
  ): File? {
    val script = ScreencastTimeline.buildConcatScript(frames, sessionStartMs, sessionEndMs) ?: return null
    val totalMs = ScreencastTimeline.totalMs(frames, sessionStartMs, sessionEndMs) ?: return null
    val dir = output.parentFile ?: return null
    val listFile = File(dir, "video.screencast.concat.txt")
    try {
      listFile.writeText(script)
    } catch (e: Exception) {
      Console.log("[$logTag] failed to write concat script: ${e.message}")
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
        // wall-clock window. Without this, the encoder stamps a default rate and the duration is
        // meaningless (frames ÷ 25), and the report's clip-time scaling would drift.
        "-vf", "fps=$muxFps",
        // Bound the encode to the window it claims to cover. The resample above has no way to
        // know how long the LAST frame lasts, so it reuses the gap before it and writes that much
        // extra — on a session ending quietly, nearly a second copy of the session. An output-side
        // `-t` (an input-side one would move the seek instead) cuts it back to the truth.
        "-t", FfconcatScript.formatSeconds(totalMs),
        "-an",
      ) + format.encodeArgs() + output.absolutePath,
      timeoutSeconds = FFMPEG_TIMEOUT_SECONDS,
    )
    runCatching { listFile.delete() }
    if (result == null) {
      Console.log("[$logTag] ffmpeg mux could not run or timed out after ${FFMPEG_TIMEOUT_SECONDS}s")
      return null
    }
    if (result.exitCode != 0 || output.length() == 0L) {
      Console.log(
        "[$logTag] ffmpeg mux failed: exit=${result.exitCode}\n" +
          sanitizeSubprocessOutputForLog(result.output),
      )
      return null
    }
    return output
  }
}
