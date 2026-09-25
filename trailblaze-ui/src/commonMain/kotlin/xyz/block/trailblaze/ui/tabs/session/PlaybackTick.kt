package xyz.block.trailblaze.ui.tabs.session

/** One autoplay step: where to put the scrubber next, and whether playback has ended. */
internal data class PlaybackTick(val targetAbsMs: Long, val reachedEnd: Boolean)

/**
 * Compute the next scrubber position for an autoplay loop.
 *
 * One caller today — the screenshot panel in [SessionCombinedView]. It stays out of that
 * composable so the "snap to the real end on the last frame" rule can be unit-tested directly
 * (see `PlaybackTickTest`) rather than only through a running Compose tree.
 *
 * @param elapsedMs `monotonicElapsed * playbackSpeed` since the loop (re)started.
 * @param playStartAbsMs the absolute scrub position the loop started from.
 * @param endAbsMs the absolute end to stop at — `effectiveEndMs`, the last log timestamp.
 */
internal fun computePlaybackTick(
  elapsedMs: Long,
  playStartAbsMs: Long,
  endAbsMs: Long,
): PlaybackTick {
  val target = playStartAbsMs + elapsedMs
  return if (target >= endAbsMs) PlaybackTick(endAbsMs, true) else PlaybackTick(target, false)
}
