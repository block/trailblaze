package xyz.block.trailblaze.capture.video

import java.io.File
import xyz.block.trailblaze.util.Console

/**
 * Executes the gap-preserving stitch planned by [IosVideoStitchPlan]: joins the session's segments
 * into its single recording, re-encoded in the requested [RecordingFormat].
 *
 * **Why the concat filter and not the concat demuxer.** The demuxer decides the stream's codec and
 * frame size from the *first* file and then feeds every later file's packets to that decoder. The
 * two segments here never match — the baguette segment is VP9 (or H.264) at the simulator's native
 * size, the `simctl` remainder is H.264 at a scaled-down one — so the demuxer hands H.264 packets
 * to a VP9 decoder, discards every frame it fails to decode, **and still exits 0**. The result is a
 * recording containing only the first segment, delivered under the whole session's window, with no
 * failure anywhere: measured on a real simulator as 5.08s of footage returned for a 13.07s session.
 * The `concat` *filter* decodes each input separately, which is what makes a heterogeneous join
 * work at all; the cost is that every input must first be scaled onto one common frame size.
 *
 * **How the gap is held.** Each non-final segment is padded with clones of its own last frame
 * ([tpad][https://ffmpeg.org/ffmpeg-filters.html#tpad]) and then hard-trimmed to the presented
 * duration the planner computed, so the next segment begins at exactly its true wall-clock offset
 * whether the segment's own footage ran long or short. The dying screen stays visible across the
 * gap instead of the recording cutting to black.
 *
 * Split out from the host-side `BaguetteIosVideoCapture` so the encode runs through the same
 * [runSubprocessWithTimeout]/[sanitizeSubprocessOutputForLog] path every other timeboxed subprocess
 * in this module uses (the web mux, the Playwright transcode) — one tested subprocess implementation, no
 * open-coded drain/timeout/destroy variations. The pure planner stays I/O-free; this is its only
 * I/O-bearing companion.
 */
object IosVideoStitcher {

  /** Generous cap for a two-segment re-encode; a wedged ffmpeg is destroyed and the stitch fails. */
  private const val STITCH_TIMEOUT_SECONDS = 120L

  /**
   * Runs [plan] into [finalFile], encoding in [format]. Returns true only when the output is a
   * non-empty recording that actually spans the plan; false on any failure (missing or failed
   * ffmpeg, timeout, empty output, a segment that contributed nothing) — the caller degrades to no
   * video artifact, never failing the run.
   *
   * @param ffmpegBinary test seam / override for the ffmpeg binary path.
   * @param ffprobeBinary test seam / override for the ffprobe binary path.
   */
  fun stitch(
    plan: IosVideoStitchPlan.StitchPlan,
    finalFile: File,
    format: RecordingFormat,
    ffmpegBinary: String = "ffmpeg",
    ffprobeBinary: String = "ffprobe",
  ): Boolean {
    // A missing frame size is not a reason to lose the recording. ffprobe may be absent on a host
    // whose ffmpeg is fine (the single-segment re-encode comes through here too, and the same host
    // recorded the session), so join without the fit: segments that already agree on a size — one
    // baguette segment, or a handover at matching resolution — still stitch, and ones that don't
    // fail loudly in the concat filter's own exit code rather than silently here.
    val target = targetFrameSize(plan, ffprobeBinary)
    if (target == null) {
      Console.log(
        "[baguette-video] could not read a frame size from any segment (is ffprobe installed?) — " +
          "joining without scaling; segments of different sizes will fail to join",
      )
    }

    val result =
      runSubprocessWithTimeout(
        command =
          buildList {
            add(ffmpegBinary)
            add("-y")
            plan.entries.forEach { add("-i"); add(it.file.absolutePath) }
            add("-an")
            add("-filter_complex")
            add(filterComplex(plan, target))
            add("-map")
            add("[$CONCAT_OUTPUT_LABEL]")
            addAll(format.encodeArgs())
            add(finalFile.absolutePath)
          },
        timeoutSeconds = STITCH_TIMEOUT_SECONDS,
      )

    if (result == null) {
      Console.error("[baguette-video] the stitch could not run or timed out after ${STITCH_TIMEOUT_SECONDS}s")
      return false
    }
    if (result.exitCode != 0 || !finalFile.exists() || finalFile.length() == 0L) {
      Console.error(
        "[baguette-video] the stitch failed (exit=${result.exitCode}): " +
          sanitizeSubprocessOutputForLog(result.output),
      )
      return false
    }
    return verifySpansThePlan(plan, finalFile, ffprobeBinary)
  }

  /**
   * The frame size every segment is scaled onto: the one belonging to the segments that are on
   * screen the longest.
   *
   * A file has a single frame size, so when segments disagree one of them must be letterboxed.
   * Segments disagree for two reasons — a `simctl` remainder records at a different resolution
   * than the baguette stream, and a session that rotates is split into a portrait-shaped segment
   * and a landscape-shaped one. Choosing by total presented duration keeps the majority of the
   * session filling the frame; choosing the first segment would pillarbox a whole landscape
   * session because it happened to open on a portrait home screen.
   */
  internal fun targetFrameSize(
    plan: IosVideoStitchPlan.StitchPlan,
    ffprobeBinary: String,
  ): VideoFrameSize.Size? = plan.entries
    .mapNotNull { entry ->
      VideoFrameSize.probe(entry.file, ffprobeBinary)?.let { it to onScreenDurationMs(plan, entry) }
    }
    .groupBy({ it.first }, { it.second })
    // Ties go to the earlier size: `maxByOrNull` keeps the first maximum it sees, and a grouping
    // preserves encounter order.
    .maxByOrNull { (_, durations) -> durations.sum() }
    ?.key

  /**
   * How long [entry] is on screen in the joined recording.
   *
   * For every entry but the last that is its presented duration — the span it holds, including any
   * gap it is padded across. The last entry has no presented duration because it simply plays out,
   * so its span is whatever the plan's overall window has left; taking it as zero would let a brief
   * opening segment outvote the orientation the session actually ran in.
   */
  private fun onScreenDurationMs(
    plan: IosVideoStitchPlan.StitchPlan,
    entry: IosVideoStitchPlan.ConcatEntry,
  ): Long {
    entry.presentedDurationMs?.let { return it }
    val precedingMs = plan.entries.sumOf { it.presentedDurationMs ?: 0L }
    return (plan.overallEndEpochMs - plan.overallStartEpochMs - precedingMs).coerceAtLeast(0L)
  }

  /**
   * Checks the delivered recording is long enough to contain every segment, which is the failure
   * this stitch is most exposed to: a segment ffmpeg could not decode is dropped silently, and the
   * exit code stays 0. The final segment starts at the sum of the presented durations, so a
   * recording no longer than that sum proves something before it was dropped, and one exactly that
   * length proves the last segment contributed no frames.
   *
   * An unreadable duration is not treated as a failure — ffprobe may be absent on a host whose
   * ffmpeg just succeeded, and a recording is never discarded over a missing probe.
   *
   * Internal so the guard is assertable against a recording of a known length.
   */
  internal fun verifySpansThePlan(
    plan: IosVideoStitchPlan.StitchPlan,
    finalFile: File,
    ffprobeBinary: String,
  ): Boolean {
    val lastSegmentStartsAtMs = plan.entries.sumOf { it.presentedDurationMs ?: 0L }
    val actualMs = VideoDuration.probeMs(finalFile, ffprobeBinary) ?: return true
    if (actualMs > lastSegmentStartsAtMs) return true
    Console.error(
      "[baguette-video] the stitch dropped a segment: the joined recording is ${actualMs}ms long but " +
        "its last segment alone starts at ${lastSegmentStartsAtMs}ms. Delivering it would stretch " +
        "part of the session across the whole timeline, so it is discarded instead.",
    )
    return false
  }

  /** Output pad name the concat filter writes to and `-map` reads back. */
  private const val CONCAT_OUTPUT_LABEL = "stitched"

  /**
   * Builds the `-filter_complex` graph: every input is scaled and letterboxed onto [target] (the
   * concat filter rejects mismatched frame sizes or aspects), each non-final input then holds its
   * last frame out to its presented duration, and the results are concatenated. With no [target] —
   * no segment's size could be probed — the fit is omitted and the inputs must already agree.
   *
   * Internal so the graph is assertable without running ffmpeg.
   */
  internal fun filterComplex(plan: IosVideoStitchPlan.StitchPlan, target: VideoFrameSize.Size?): String {
    val fit = target?.let { (w, h) ->
      "scale=$w:$h:force_original_aspect_ratio=decrease,pad=$w:$h:(ow-iw)/2:(oh-ih)/2,setsar=1,"
    }.orEmpty()
    val chains = plan.entries.mapIndexed { index, entry ->
      val hold = entry.presentedDurationMs?.let {
        val seconds = FfconcatScript.formatSeconds(it)
        // tpad clones the last frame outwards; trim then cuts to exactly the presented duration, so
        // a segment whose own footage overran its window can't push the next one late either.
        "tpad=stop_mode=clone:stop_duration=$seconds,trim=duration=$seconds,"
      }.orEmpty()
      "[$index:v]$fit${hold}setpts=PTS-STARTPTS[v$index]"
    }
    val inputs = plan.entries.indices.joinToString("") { "[v$it]" }
    return (chains + "${inputs}concat=n=${plan.entries.size}:v=1:a=0[$CONCAT_OUTPUT_LABEL]")
      .joinToString(";")
  }
}
