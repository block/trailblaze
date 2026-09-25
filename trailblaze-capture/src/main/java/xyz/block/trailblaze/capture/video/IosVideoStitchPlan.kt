package xyz.block.trailblaze.capture.video

import java.io.File

/**
 * Pure planner for stitching the iOS session video from one or more wall-clock-anchored segments
 * into the session's single recording whose playback timeline stays **linear against host wall-clock** — the
 * contract the report timeline relies on (`videoPositionMs = eventEpochMs - startTimestampMs`).
 *
 * The primary iOS path is a single baguette segment whose per-frame PTS are already wall-clock
 * (see [WallClockMuxConsumer]); that's a one-entry plan and a no-op passthrough. A second
 * segment only appears when the baguette feed dies mid-session and the recorder restarts via
 * `simctl` for the remainder — see the class kdoc on the iOS capture. When that happens the two
 * segments cover disjoint wall-clock spans with a **gap** in between (baguette death →
 * simulator-recording boot), and a naive concat would compress that gap and shift every later
 * frame off its true time. This planner preserves the gap by extending each non-final segment's
 * presented duration to cover it, so the next segment starts at its true wall-clock offset.
 *
 * A presented duration is what the segment *occupies*, not how much footage it holds: [IosVideoStitcher]
 * pads each segment with clones of its own last frame and trims to this length, so the gap shows
 * the dying screen rather than black, and a segment whose footage overran or fell short of its
 * window still can't shift the one after it.
 *
 * All timing here is host-epoch millis ([VideoSegment.startEpochMs]/[VideoSegment.endEpochMs]) —
 * the same `System.currentTimeMillis()` clock that stamps every Trailblaze session-log event. The
 * planner is deliberately I/O-free so it can be unit-tested with plain numbers.
 */
object IosVideoStitchPlan {

  /**
   * Floor for a non-final entry's presented duration. A duration that rounds to `0.000` trims the
   * entry away to nothing, silently discarding the segment — so clamp up to 1ms (matching
   * [ScreencastTimeline.MIN_FRAME_DURATION_MS]) when two segments share a start epoch (clock wobble
   * at the death→simctl-boot boundary).
   */
  private const val MIN_FRAME_DURATION_MS = 1L

  /** One recorded video segment and the host-epoch wall-clock window it covers. */
  data class VideoSegment(
    val file: File,
    /** Host epoch (ms) of the segment's first frame — its offset-0 anchor. */
    val startEpochMs: Long,
    /** Host epoch (ms) when the segment stopped recording. */
    val endEpochMs: Long,
  )

  /**
   * One entry in the ffmpeg concat plan.
   *
   * @param presentedDurationMs when non-null, the `duration` directive to emit for this file in the
   *   ffconcat script — the wall-clock span this segment (plus any trailing gap before the next
   *   segment) should occupy. Null for the final segment, which plays out its natural length.
   */
  data class ConcatEntry(val file: File, val presentedDurationMs: Long?)

  /**
   * A resolved stitch plan.
   *
   * @param needsConcat false for the single-segment fast path (the segment file *is* the final
   *   `video.mp4`, no ffmpeg work needed); true when two or more segments must be concatenated.
   */
  data class StitchPlan(
    val entries: List<ConcatEntry>,
    val overallStartEpochMs: Long,
    val overallEndEpochMs: Long,
    val needsConcat: Boolean,
  )

  /**
   * Builds the plan for [segments] (in wall-clock order). Returns null when there is nothing to
   * stitch. Segments are sorted by [VideoSegment.startEpochMs] defensively; each non-final entry's
   * presented duration spans from its own start to the next segment's start (content + gap), so the
   * combined timeline is `overallEnd - overallStart` of continuous wall-clock.
   */
  fun plan(segments: List<VideoSegment>): StitchPlan? {
    val ordered = segments.sortedBy { it.startEpochMs }
    if (ordered.isEmpty()) return null

    val entries = ordered.mapIndexed { index, segment ->
      val next = ordered.getOrNull(index + 1)
      val presentedDurationMs =
        if (next == null) null
        // Cover this segment's content plus the gap until the next segment begins. Floor at 1ms so
        // clock wobble (next-start at or before this-start) can't emit a 0.000 duration the concat
        // demuxer would drop, silently discarding this segment.
        else (next.startEpochMs - segment.startEpochMs).coerceAtLeast(MIN_FRAME_DURATION_MS)
      ConcatEntry(file = segment.file, presentedDurationMs = presentedDurationMs)
    }

    return StitchPlan(
      entries = entries,
      overallStartEpochMs = ordered.first().startEpochMs,
      // Latest end across all segments, not the last-by-start's end: a segment that starts earlier
      // could still end later, and the overall window is the artifact's recording window.
      overallEndEpochMs = ordered.maxOf { it.endEpochMs },
      needsConcat = ordered.size > 1,
    )
  }
}
