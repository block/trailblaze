package xyz.block.trailblaze.capture.video

/**
 * Shared emission primitives for the ffmpeg [concat demuxer](https://ffmpeg.org/ffmpeg-formats.html#concat-1)
 * `ffconcat` script, so the two producers that build one — [ScreencastTimeline] (web/mobile
 * damage-driven screencast, one `file`+`duration` per captured frame) and [IosVideoStitchPlan]
 * (iOS baguette↔simctl segment stitch, one `file`+`duration` per segment) — escape paths and
 * format durations identically instead of each carrying its own copy that could drift.
 */
internal object FfconcatScript {

  /** Escapes a path for a single-quoted ffconcat `file '...'` directive (single-quote → `'\''`). */
  fun escapeConcatPath(path: String): String = path.replace("'", "'\\''")

  /** Fixed-3-decimal seconds, locale-independent (ffmpeg parses `.` as the decimal separator). */
  fun formatSeconds(ms: Long): String {
    val whole = ms / 1000
    val frac = ms % 1000
    return "$whole.${frac.toString().padStart(3, '0')}"
  }
}
