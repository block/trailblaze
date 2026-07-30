package xyz.block.trailblaze.capture.video

import java.io.File
import xyz.block.trailblaze.util.Console

/**
 * Executes the gap-preserving stitch planned by [IosVideoStitchPlan]: writes the ffconcat script,
 * then concatenates the segments into `video.mp4` with a re-encode (the baguette and simctl
 * segments differ in codec params/resolution, so `-c copy` can't join them). The ffconcat
 * `duration` directives freeze each dying segment's last frame across its gap so the following
 * segment sits at its true wall-clock offset.
 *
 * Split out from the host-side `BaguetteIosVideoCapture` so the concat runs through the same
 * [runSubprocessWithTimeout]/[sanitizeSubprocessOutputForLog] path every other timeboxed subprocess
 * in this module uses (the web mux, the sprite probe) — one tested subprocess implementation, no
 * open-coded drain/timeout/destroy variations. The pure planner stays I/O-free; this is its only
 * I/O-bearing companion.
 */
object IosVideoStitcher {

  /** ffconcat script written under the session dir; a scratch intermediate the caller cleans up. */
  const val CONCAT_SCRIPT_FILENAME = "video.concat.ffconcat"

  /** Generous cap for a two-segment re-encode; a wedged ffmpeg is destroyed and the stitch fails. */
  private const val STITCH_TIMEOUT_SECONDS = 120L

  /**
   * Runs [plan] into [finalMp4] under [sessionDir]. Returns true on a non-empty output written by a
   * clean ffmpeg exit; false on any failure (script write, missing/failed ffmpeg, timeout, empty
   * output) — the caller degrades to no video artifact, never failing the run.
   *
   * @param ffmpegBinary test seam / override for the ffmpeg binary path.
   */
  fun stitch(
    plan: IosVideoStitchPlan.StitchPlan,
    sessionDir: File,
    finalMp4: File,
    ffmpegBinary: String = "ffmpeg",
  ): Boolean {
    val scriptFile = File(sessionDir, CONCAT_SCRIPT_FILENAME)
    runCatching { scriptFile.writeText(IosVideoStitchPlan.toFfconcatScript(plan)) }
      .onFailure {
        Console.error("[baguette-video] could not write the concat script: ${it.message}")
        return false
      }

    val result =
      runSubprocessWithTimeout(
        command =
          listOf(
            ffmpegBinary,
            "-y",
            "-f", "concat", "-safe", "0",
            "-i", scriptFile.absolutePath,
            "-c:v", "libx264", "-preset", "veryfast", "-pix_fmt", "yuv420p",
            "-movflags", "+faststart",
            finalMp4.absolutePath,
          ),
        timeoutSeconds = STITCH_TIMEOUT_SECONDS,
      )

    if (result == null) {
      Console.error("[baguette-video] concat could not run or timed out after ${STITCH_TIMEOUT_SECONDS}s")
      return false
    }
    val ok = result.exitCode == 0 && finalMp4.exists() && finalMp4.length() > 0L
    if (!ok) {
      Console.error(
        "[baguette-video] concat failed (exit=${result.exitCode}): " +
          sanitizeSubprocessOutputForLog(result.output),
      )
    }
    return ok
  }
}
