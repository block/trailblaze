package xyz.block.trailblaze.capture.video

import java.io.File

/**
 * The pixel dimensions of a recording, asked of the file rather than assumed.
 *
 * Needed when two independently-produced segments have to be joined into one recording: the
 * segments come from different recorders at different resolutions (the baguette stream captures the
 * simulator's native size, `simctl` writes a scaled-down mp4), and ffmpeg's `concat` filter refuses
 * inputs whose frame size or aspect differ. [IosVideoStitcher] scales every segment onto one target
 * size, and this is where that target comes from.
 */
internal object VideoFrameSize {

  /** Cap on the probe. It reads a container header, so a second is already generous. */
  private const val PROBE_TIMEOUT_SECONDS = 20L

  /** A recording's frame dimensions in pixels. */
  data class Size(val width: Int, val height: Int)

  /**
   * Dimensions the first video stream in [file] **decodes to**, or null when they can't be
   * determined — no `ffprobe` on PATH, an unreadable or still-empty file, or a container whose
   * header carries no video stream.
   *
   * "Decodes to" rather than "is stored as", because those differ for exactly the files this is
   * used on. A landscape `simctl` recording stores a portrait frame plus a display matrix, and
   * ffmpeg applies that matrix on decode by default — so the stream header says 1206x2622 while
   * every frame reaching the concat filter is 2622x1206. Scaling the other segments onto the
   * header's answer would fit them to a canvas that never appears, and pillarbox the whole session.
   */
  fun probe(file: File, ffprobeBinary: String = "ffprobe"): Size? {
    if (!file.exists() || file.length() == 0L) return null
    val result = runSubprocessWithTimeout(
      command = listOf(
        ffprobeBinary,
        "-v", "error",
        "-select_streams", "v:0",
        "-show_entries", "stream=width,height:stream_side_data=rotation",
        "-of", "default=noprint_wrappers=1:nokey=1",
        file.absolutePath,
      ),
      timeoutSeconds = PROBE_TIMEOUT_SECONDS,
    ) ?: return null
    if (result.exitCode != 0) return null
    return parse(result.output)
  }

  /**
   * Parses ffprobe's bare `stream=width,height:stream_side_data=rotation` output: width, then
   * height, then a rotation angle only when the stream carries a display matrix.
   *
   * Split out because this is where the failure modes are: ffprobe prints nothing for a file it
   * could not open, answers `N/A` for a stream whose header omits a dimension, and emits only one
   * line when a container reports a width but no height. Each has to read as "no answer" rather
   * than as a zero-sized frame, which would make the scale filter reject the whole stitch. A
   * quarter-turn angle additionally swaps the axes, because that is what the decoder will do.
   */
  internal fun parse(output: String): Size? {
    val lines = output.lineSequence()
      .map { it.trim() }
      .filter { it.isNotEmpty() }
      .toList()
    if (lines.size < 2) return null
    val width = lines[0].toIntOrNull()
    val height = lines[1].toIntOrNull()
    if (width == null || height == null || width <= 0 || height <= 0) return null
    // Absent for the overwhelmingly common untagged stream; ±90 (or ±270) for a rotated one.
    val quarterTurn = lines.getOrNull(2)?.toIntOrNull()?.let { Math.floorMod(it, 180) == 90 } == true
    return if (quarterTurn) Size(height, width) else Size(width, height)
  }
}
