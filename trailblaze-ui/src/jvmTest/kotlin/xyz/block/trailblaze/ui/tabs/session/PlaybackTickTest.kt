package xyz.block.trailblaze.ui.tabs.session

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Coverage for the autoplay step shared by the video and screenshot panels in
 * [SessionCombinedView].
 *
 * This used to be one of two playback modes — the other compressed idle gaps so a
 * `--gif/--webp/--video` export scaled with step count instead of wall-clock. That mode only ran
 * when the host asked for autoplay on load, which no host does any more, so playback is now
 * always 1:1 and these are the whole contract.
 */
class PlaybackTickTest {

  @Test
  fun `playback advances from the start position by the elapsed amount`() {
    val tick = computePlaybackTick(elapsedMs = 300L, playStartAbsMs = 1_000L, endAbsMs = 5_000L)

    assertEquals(1_300L, tick.targetAbsMs)
    assertFalse(tick.reachedEnd)
  }

  @Test
  fun `overshooting the end snaps back to it rather than scrubbing past`() {
    // The loop ticks on a wall clock, so the final tick almost always lands past the end. Reporting
    // the overshoot would scrub the timeline beyond the last frame before playback stops.
    val tick = computePlaybackTick(elapsedMs = 9_000L, playStartAbsMs = 1_000L, endAbsMs = 5_000L)

    assertEquals(5_000L, tick.targetAbsMs)
    assertTrue(tick.reachedEnd)
  }

  @Test
  fun `landing exactly on the end counts as reaching it`() {
    // Boundary: `>=`, not `>`. Were this exclusive, a tick that lands precisely on the end would
    // report "not finished" and the loop would spin for one more interval before stopping.
    val tick = computePlaybackTick(elapsedMs = 4_000L, playStartAbsMs = 1_000L, endAbsMs = 5_000L)

    assertEquals(5_000L, tick.targetAbsMs)
    assertTrue(tick.reachedEnd)
  }

  @Test
  fun `a resumed loop continues from where it was, not from the session start`() {
    // A playback-speed change relaunches the loop with elapsed reset to 0, passing the current
    // scrub position as the start. That must not rewind playback.
    val tick = computePlaybackTick(elapsedMs = 0L, playStartAbsMs = 3_000L, endAbsMs = 5_000L)

    assertEquals(3_000L, tick.targetAbsMs)
    assertFalse(tick.reachedEnd)
  }
}
