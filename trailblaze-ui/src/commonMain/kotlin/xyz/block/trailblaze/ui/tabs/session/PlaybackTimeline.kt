package xyz.block.trailblaze.ui.tabs.session

/**
 * Maps autoplay's "compressed" elapsed time onto absolute session timestamps so that
 * exported timelines (`trailblaze report --gif/--webp/--video`) scale with the number of
 * recorded steps rather than the session's real wall-clock.
 *
 * The autoplay loop in [SessionCombinedView] advances the scrubber by
 * `wallClockElapsed * playbackSpeed`. Without compression that makes export duration
 * proportional to `sessionEnd - sessionStart`, so a session recorded interactively over
 * a long period (a handful of actions spread across, say, 100 minutes — see issue #173)
 * produces a 100+ minute animation that no fixed capture window can accommodate and that
 * would be an enormous artifact even if it could.
 *
 * This collapses the dead air: gaps between consecutive [anchorsMs] longer than
 * [maxGapMs] are played back as if they were exactly [maxGapMs]; shorter gaps (the
 * meaningful intra-step activity) play 1:1. The result is a piecewise-linear mapping from
 * compressed playback time to absolute session time. During a collapsed gap the scrubber
 * still slides across the real timestamps — only faster — but since no event occurs in a
 * gap the displayed frame is unchanged anyway, so the only visible effect is that idle
 * stretches no longer waste capture time.
 *
 * Built once per export from the session's log timestamps. Used only on the export
 * autoplay path; interactive playback keeps real (speed-scaled) timing.
 */
internal class PlaybackTimeline private constructor(
  /** Sorted absolute session timestamps (ms) of the anchor points. */
  private val absAnchors: LongArray,
  /** Compressed playback offset (ms, from 0) corresponding to each anchor. */
  private val compAnchors: LongArray,
) {
  // [absAnchors] and [compAnchors] are index-aligned: compAnchors[i] is the compressed
  // playback offset at which the scrubber sits on absAnchors[i]. The private constructor
  // is the only producer, so the alignment + "absAnchors strictly increasing" invariants
  // hold for every instance.

  /** Total compressed playback duration in ms — the loop runs until elapsed reaches this. */
  val totalCompressedMs: Long get() = compAnchors[compAnchors.size - 1]

  /** First/last absolute timestamps the timeline spans (the real session start/end). */
  val startAbsMs: Long get() = absAnchors[0]
  val endAbsMs: Long get() = absAnchors[absAnchors.size - 1]

  /**
   * Absolute session timestamp to show at [compressedMs] of compressed playback,
   * interpolating linearly within the segment that [compressedMs] falls in. Clamps to the
   * first/last anchor outside the `[0, totalCompressedMs]` range.
   */
  fun absoluteAt(compressedMs: Long): Long {
    val n = absAnchors.size
    if (compressedMs <= 0L) return absAnchors[0]
    if (compressedMs >= totalCompressedMs) return absAnchors[n - 1]

    // Largest i such that compAnchors[i] <= compressedMs (segment [i, i+1]).
    var lo = 0
    var hi = n - 1
    while (lo < hi) {
      val mid = (lo + hi + 1) ushr 1
      if (compAnchors[mid] <= compressedMs) lo = mid else hi = mid - 1
    }
    val i = lo
    val compSpan = compAnchors[i + 1] - compAnchors[i]
    if (compSpan <= 0L) return absAnchors[i]
    val frac = (compressedMs - compAnchors[i]).toDouble() / compSpan.toDouble()
    val absSpan = absAnchors[i + 1] - absAnchors[i]
    return absAnchors[i] + (absSpan * frac).toLong()
  }

  /**
   * Inverse of [absoluteAt]: the compressed playback offset at which the scrubber sits on
   * [absoluteMs]. Lets a play loop anchor its compressed clock on the *current* scrub
   * position rather than always assuming playback began at the session start — so a
   * mid-run relaunch (e.g. a playback-speed change) resumes from where it was instead of
   * jumping back to the start. Clamps outside `[startAbsMs, endAbsMs]`.
   */
  fun compressedAt(absoluteMs: Long): Long {
    val n = absAnchors.size
    if (absoluteMs <= absAnchors[0]) return 0L
    if (absoluteMs >= absAnchors[n - 1]) return totalCompressedMs

    // Largest i such that absAnchors[i] <= absoluteMs (segment [i, i+1]).
    var lo = 0
    var hi = n - 1
    while (lo < hi) {
      val mid = (lo + hi + 1) ushr 1
      if (absAnchors[mid] <= absoluteMs) lo = mid else hi = mid - 1
    }
    val i = lo
    val absSpan = absAnchors[i + 1] - absAnchors[i]
    if (absSpan <= 0L) return compAnchors[i]
    val frac = (absoluteMs - absAnchors[i]).toDouble() / absSpan.toDouble()
    val compSpan = compAnchors[i + 1] - compAnchors[i]
    return compAnchors[i] + (compSpan * frac).toLong()
  }

  /**
   * If [absoluteMs] falls within a gap that was actually collapsed (its real span exceeded
   * the cap, so compressed playback fast-forwards across it), returns that gap's *real*
   * duration in ms; otherwise null. Lets the player caption a fast-forwarded idle stretch
   * ("» 37m later") so a compressed gap isn't silently invisible in the exported animation —
   * the duration is preserved as a label even though it isn't spent as playback time.
   *
   * A gap `[i, i+1]` was collapsed iff its absolute span is wider than its compressed span
   * (`min(realGap, cap) < realGap`), so no cap value needs to be retained to detect it.
   */
  fun collapsedGapMsAt(absoluteMs: Long): Long? {
    val n = absAnchors.size
    if (absoluteMs < absAnchors[0] || absoluteMs >= absAnchors[n - 1]) return null

    // Largest i such that absAnchors[i] <= absoluteMs (segment [i, i+1]).
    var lo = 0
    var hi = n - 1
    while (lo < hi) {
      val mid = (lo + hi + 1) ushr 1
      if (absAnchors[mid] <= absoluteMs) lo = mid else hi = mid - 1
    }
    val i = lo
    val absSpan = absAnchors[i + 1] - absAnchors[i]
    val compSpan = compAnchors[i + 1] - compAnchors[i]
    return if (absSpan > compSpan) absSpan else null
  }

  companion object {
    /**
     * Build a timeline from [anchorsMs] (any order, duplicates tolerated), collapsing any
     * gap longer than [maxGapMs]. Returns null when there's nothing to compress — fewer
     * than two distinct anchors, or a non-positive cap — so callers fall back to the
     * uncompressed linear path.
     */
    fun build(anchorsMs: List<Long>, maxGapMs: Long): PlaybackTimeline? {
      if (maxGapMs <= 0L) return null
      val sorted = anchorsMs.distinct().sorted().toLongArray()
      if (sorted.size < 2) return null

      val comp = LongArray(sorted.size)
      comp[0] = 0L
      for (i in 1 until sorted.size) {
        val gap = sorted[i] - sorted[i - 1]
        comp[i] = comp[i - 1] + minOf(gap, maxGapMs)
      }
      return PlaybackTimeline(sorted, comp)
    }
  }
}

/** One autoplay step: where to put the scrubber next, and whether playback has ended. */
internal data class PlaybackTick(val targetAbsMs: Long, val reachedEnd: Boolean)

/**
 * Compute the next scrubber position for an autoplay loop. Shared by both the video and
 * screenshot panels in [SessionCombinedView] so the compressed-vs-linear branch (and the
 * "snap to the real end on the last frame" behavior) lives in exactly one place.
 *
 * @param elapsedMs `monotonicElapsed * playbackSpeed` since the loop (re)started.
 * @param playStartAbsMs the absolute scrub position the loop started from.
 * @param endAbsMs the absolute end the *uncompressed* path stops at (the video/screenshot
 *   panels source this differently — `videoMetadata.endTimestampMs` vs `effectiveEndMs`).
 * @param timeline idle-gap compression for the export path, or null for 1:1 playback.
 *
 * With a [timeline], the compressed clock is anchored on [playStartAbsMs] (via
 * [PlaybackTimeline.compressedAt]) and the end-snap lands on the timeline's own endpoint —
 * never [endAbsMs], which the compressed mapping doesn't cover. Without one, playback
 * advances 1:1 and snaps to [endAbsMs].
 *
 * The timeline endpoint is the last log timestamp (`effectiveEndMs`), which can differ from
 * the video panel's [endAbsMs] (`videoMetadata.endTimestampMs`) in either direction, and
 * compression intentionally follows the timeline either way:
 *  - video ends *after* the last log (a trailing event-less tail): we stop at the last
 *    event rather than playing dead tail — the whole point of idle compression.
 *  - video ends *before* the last log: we scrub to `effectiveEndMs`; the frame cache clamps
 *    to its last available frame for that sliver. Both are benign because completion is
 *    signaled off the `isVideoPlaying` transition, not off reaching a specific timestamp.
 */
internal fun computePlaybackTick(
  elapsedMs: Long,
  playStartAbsMs: Long,
  endAbsMs: Long,
  timeline: PlaybackTimeline?,
): PlaybackTick {
  if (timeline == null) {
    val target = playStartAbsMs + elapsedMs
    return if (target >= endAbsMs) PlaybackTick(endAbsMs, true) else PlaybackTick(target, false)
  }
  val compressedNow = timeline.compressedAt(playStartAbsMs) + elapsedMs
  return if (compressedNow >= timeline.totalCompressedMs) {
    PlaybackTick(timeline.endAbsMs, true)
  } else {
    PlaybackTick(timeline.absoluteAt(compressedNow), false)
  }
}

/**
 * The anchor timestamps an export [PlaybackTimeline] is built from: the session window
 * bounds plus every log timestamp that falls inside it. Pulled out of [SessionCombinedView]
 * so the windowing rule (logs outside `[startMs, endMs]` are dropped; the bounds are always
 * present so an event-less session still has the two endpoints) is unit-testable.
 */
internal fun exportPlaybackAnchors(
  logTimestampsMs: List<Long>,
  startMs: Long,
  endMs: Long,
): List<Long> = buildList {
  add(startMs)
  addAll(logTimestampsMs)
  add(endMs)
}.filter { it in startMs..endMs }
