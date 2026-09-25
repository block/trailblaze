package xyz.block.trailblaze.capture.video

import java.io.File

/**
 * How long a recording actually is, asked of the file rather than inferred from the clock.
 *
 * Needed by the one recorder that cannot observe its own frames: Playwright owns its `.webm` and
 * reports neither the first frame nor the last, and it writes a stable tail of duplicate frames
 * while finalizing (measured at ~910 ms, the same on three runs). A window bookended on the calls
 * around it is therefore *shorter* than the file, and since the report scales clip time onto the
 * window, that difference lands as a proportional error — half a second by mid-session on a ten
 * second recording. Spanning the window across the real duration makes that scale exactly 1.
 */
internal object VideoDuration {

  /**
   * Cap on the probe. It reads a container header, which is milliseconds of work — the budget is
   * for the process launch around it on a loaded CI box, not for the read. Kept short because it
   * runs on the teardown path with the session's exit waiting behind it: a probe that hangs should
   * cost the caller its measured duration and nothing else, since the window it falls back to is
   * already correct to within the recorder's finalizing tail.
   */
  private const val PROBE_TIMEOUT_SECONDS = 5L

  /**
   * Duration of [file] in milliseconds, or null when it can't be determined — no `ffprobe` on
   * PATH, an unreadable or still-empty file, or a container with no duration in its header.
   * Callers must have a window to fall back to; a recording is never dropped over this.
   */
  fun probeMs(file: File, ffprobeBinary: String = "ffprobe"): Long? {
    if (!file.exists() || file.length() == 0L) return null
    val result = runSubprocessWithTimeout(
      command = listOf(
        ffprobeBinary,
        "-v", "error",
        "-show_entries", "format=duration",
        "-of", "default=noprint_wrappers=1:nokey=1",
        file.absolutePath,
      ),
      timeoutSeconds = PROBE_TIMEOUT_SECONDS,
    ) ?: return null
    if (result.exitCode != 0) return null
    return parseDurationMs(result.output)
  }

  /**
   * Parses ffprobe's bare `format=duration` output into whole milliseconds.
   *
   * Split out because this is where the failure modes are: ffprobe answers `N/A` for a container
   * whose header has no duration, prints nothing at all for a file it could not open, and a
   * partially-written recording can report a duration of zero. Each of those has to read as "no
   * answer" rather than as a zero-length window, which the report would treat as an instant.
   */
  internal fun parseDurationMs(output: String): Long? {
    val seconds = output.lineSequence()
      .map { it.trim() }
      .firstOrNull { it.isNotEmpty() }
      ?.toDoubleOrNull()
      ?: return null
    if (!seconds.isFinite() || seconds <= 0.0) return null
    return Math.round(seconds * 1000.0)
  }
}
