package xyz.block.trailblaze.capture.video

import xyz.block.trailblaze.util.Console
import java.io.File

/**
 * Single frames out of a finished recording, as image files.
 *
 * A capture that saved no screenshot still happened on screen, and the session's recording has
 * that screen: this is how a frame gets out of it. Two rules make it exact rather than nearby.
 *
 * - **The frame on screen at an instant is the last one that started at or before it.** A
 *   recording of a UI is sparse — a still screen encodes nothing new — so the next frame can be
 *   whole seconds after the instant and show a later screen. A seek (`-ss`) answers with that next
 *   frame, which is why frames are picked here from the file's own frame times ([Recording.frameAt])
 *   and then selected by exact timestamp, not reached by seeking.
 * - **One decode per recording.** A recording's keyframes can be tens of seconds apart, so a
 *   per-frame seek decodes the same stretch again for every frame. [extract] decodes the file once
 *   and writes every requested frame on the way through.
 */
object VideoStills {

  /** Cap on each probe. They read the container and its packet index, not the pictures. */
  private const val PROBE_TIMEOUT_SECONDS = 20L

  /** Cap on the decode, which reads the whole file once however many frames are asked for. */
  private const val EXTRACT_TIMEOUT_SECONDS = 120L

  /**
   * What [frameAt] needs to know about a recording.
   *
   * @param durationSec the container's duration, which is what a browser reports as the clip's
   *   length and what the report scales a run instant onto.
   * @param width the width frames decode to, after any rotation the container asks for.
   * @param framePts every frame's presentation time in [timeBaseSec] units, ascending.
   */
  class Recording(
    val durationSec: Double,
    val width: Int,
    val height: Int,
    val timeBaseSec: Double,
    val framePts: LongArray,
  ) {
    /**
     * The pts of the frame on screen at [atSec] seconds into the recording: the last frame
     * presented at or before it, or the first frame when [atSec] precedes every frame (a player
     * shows the first frame from the start). Null for a recording with no frames.
     */
    fun frameAt(atSec: Double): Long? {
      if (framePts.isEmpty()) return null
      var lo = 0
      var hi = framePts.size - 1
      if (framePts[0] * timeBaseSec > atSec) return framePts[0]
      while (lo < hi) {
        val mid = (lo + hi + 1) ushr 1
        if (framePts[mid] * timeBaseSec <= atSec) lo = mid else hi = mid - 1
      }
      return framePts[lo]
    }
  }

  /**
   * The recording in [file], or null when it can't be read: no `ffprobe`, an empty or unreadable
   * file, or no duration, size or frames in it.
   */
  fun probe(file: File, ffprobeBinary: String = "ffprobe"): Recording? {
    if (!file.exists() || file.length() == 0L) return null
    val durationMs = VideoDuration.probeMs(file, ffprobeBinary) ?: return null
    val size = VideoFrameSize.probe(file, ffprobeBinary) ?: return null
    val result = runSubprocessWithTimeout(
      command = listOf(
        ffprobeBinary,
        "-v", "error",
        "-select_streams", "v:0",
        "-show_entries", "stream=time_base:packet=pts,flags",
        "-of", "csv=p=0",
        file.absolutePath,
      ),
      timeoutSeconds = PROBE_TIMEOUT_SECONDS,
    ) ?: return null
    if (result.exitCode != 0) return null
    val (timeBaseSec, pts) = parsePackets(result.output) ?: return null
    if (pts.isEmpty()) return null
    return Recording(durationMs / 1000.0, size.width, size.height, timeBaseSec, pts)
  }

  /**
   * Parses `stream=time_base:packet=pts,flags` CSV: one `<pts>,<flags>` line per packet and one
   * `<num>/<den>` line for the stream. A packet with no pts (`N/A`) or flagged for discard (`D`)
   * is never presented, so it is no frame.
   */
  internal fun parsePackets(output: String): Pair<Double, LongArray>? {
    var timeBaseSec: Double? = null
    val pts = mutableListOf<Long>()
    for (raw in output.lineSequence()) {
      val line = raw.trim()
      if (line.isEmpty()) continue
      val fraction = TIME_BASE.matchEntire(line)
      if (fraction != null) {
        val den = fraction.groupValues[2].toLong()
        if (den > 0) timeBaseSec = fraction.groupValues[1].toLong().toDouble() / den
        continue
      }
      val fields = line.split(',')
      val value = fields[0].toLongOrNull() ?: continue
      if (fields.getOrNull(1)?.contains('D') == true) continue
      pts += value
    }
    val base = timeBaseSec?.takeIf { it > 0.0 } ?: return null
    return base to pts.distinct().sorted().toLongArray()
  }

  /**
   * Writes the frame presented at each pts in [frames] to its file as a PNG, in one decode of
   * [file], and returns the pts that were written. PNG because it is lossless: the caller encodes
   * the final image, and a lossy intermediate would be compressed twice. A pts the decoder never
   * presented is left out rather than answered with a neighbour. Every file is written complete or
   * not at all: frames decode into [workDir] and are moved into place only once ffmpeg has finished.
   *
   * @param size the width and height to scale each frame to, or null to keep the recording's own.
   */
  fun extract(
    file: File,
    frames: Map<Long, File>,
    workDir: File,
    ffmpegBinary: String = "ffmpeg",
    size: Pair<Int, Int>? = null,
  ): Set<Long> {
    if (frames.isEmpty()) return emptySet()
    val wanted = frames.keys.sorted()
    workDir.mkdirs()
    // `showinfo` after `select` logs each frame the filter passes, in the order image2 numbers the
    // files, so line i names the pts of file i. Timestamps are kept as the file has them
    // (`-copyts`), which is what the probed pts are in.
    val select = wanted.joinToString("+") { "eq(pts\\,$it)" }
    val scale = size?.let { (width, height) -> ",scale=$width:$height" }.orEmpty()
    val result = runSubprocessWithTimeout(
      command = listOf(
        ffmpegBinary,
        "-hide_banner", "-nostdin", "-y",
        "-copyts",
        "-i", file.absolutePath,
        "-vf", "select='$select'$scale,showinfo",
        "-fps_mode", "passthrough",
        "-f", "image2",
        File(workDir, "frame-%d.png").absolutePath,
      ),
      timeoutSeconds = EXTRACT_TIMEOUT_SECONDS,
    )
    if (result == null) {
      Console.log("[VideoStills] ffmpeg could not be run or timed out after ${EXTRACT_TIMEOUT_SECONDS}s: ${file.name}")
      return emptySet()
    }
    if (result.exitCode != 0) {
      Console.log(
        "[VideoStills] ffmpeg failed on ${file.name}: exit=${result.exitCode}\n" +
          sanitizeSubprocessOutputForLog(result.output.takeLast(400)),
      )
      return emptySet()
    }
    val written = mutableSetOf<Long>()
    parseShowinfoPts(result.output).forEachIndexed { index, pts ->
      val decoded = File(workDir, "frame-${index + 1}.png")
      val target = frames[pts] ?: return@forEachIndexed
      if (decoded.isFile && decoded.length() > 0 && decoded.renameTo(target)) written += pts
    }
    return written
  }

  /** The `pts:` of each frame `showinfo` logged, in the order it logged them. */
  internal fun parseShowinfoPts(output: String): List<Long> =
    output.lineSequence()
      .filter { it.contains("Parsed_showinfo") }
      .mapNotNull { SHOWINFO_PTS.find(it)?.groupValues?.get(1)?.toLongOrNull() }
      .toList()

  private val TIME_BASE = Regex("(\\d+)/(\\d+)")
  private val SHOWINFO_PTS = Regex("\\bn:\\s*\\d+\\s+pts:\\s*(-?\\d+)")
}
