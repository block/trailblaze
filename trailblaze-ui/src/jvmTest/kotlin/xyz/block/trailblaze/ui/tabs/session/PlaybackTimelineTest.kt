package xyz.block.trailblaze.ui.tabs.session

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit coverage for the idle-gap compression that keeps `--gif/--webp/--video` export
 * duration proportional to step count rather than the session's real wall-clock (#173).
 */
class PlaybackTimelineTest {

  @Test
  fun `build returns null when there is nothing to compress`() {
    assertNull(PlaybackTimeline.build(emptyList(), 1_000L))
    assertNull(PlaybackTimeline.build(listOf(42L), 1_000L), "single anchor")
    assertNull(PlaybackTimeline.build(listOf(1L, 1L, 1L), 1_000L), "all duplicates collapse to one")
    assertNull(PlaybackTimeline.build(listOf(0L, 1_000L), 0L), "non-positive cap")
  }

  @Test
  fun `gaps under the cap play one to one`() {
    // Three anchors 1s apart, cap 4s — nothing is capped.
    val tl = PlaybackTimeline.build(listOf(0L, 1_000L, 2_000L), 4_000L)!!
    assertEquals(2_000L, tl.totalCompressedMs)
    assertEquals(0L, tl.absoluteAt(0L))
    assertEquals(1_000L, tl.absoluteAt(1_000L))
    assertEquals(2_000L, tl.absoluteAt(2_000L))
  }

  @Test
  fun `a long idle gap is collapsed to the cap`() {
    // 0 -> 10ms of real activity, then a 100-minute idle gap, then end.
    val tenMinutes = 10 * 60 * 1000L
    val anchors = listOf(0L, 10L, tenMinutes)
    val cap = 4_000L
    val tl = PlaybackTimeline.build(anchors, cap)!!

    // Compressed total = 10ms (first gap, under cap) + 4000ms (capped idle gap).
    assertEquals(10L + cap, tl.totalCompressedMs)

    // The collapsed gap still maps its endpoints to the real timestamps...
    assertEquals(10L, tl.absoluteAt(10L))
    assertEquals(tenMinutes, tl.absoluteAt(tl.totalCompressedMs))
    // ...and the midpoint of the compressed gap interpolates to the midpoint of real time.
    val mid = tl.absoluteAt(10L + cap / 2)
    val expectedMid = (10L + tenMinutes) / 2
    assertTrue(
      kotlin.math.abs(mid - expectedMid) <= 2L,
      "midpoint should interpolate to ~$expectedMid, was $mid",
    )
  }

  @Test
  fun `compressed duration scales with step count not wall-clock`() {
    // 25 steps, each separated by an hour of idle time — the shape from https://github.com/block/trailblaze/issues/173.
    val hour = 60 * 60 * 1000L
    val anchors = (0 until 25).map { it * hour }
    val cap = 4_000L
    val tl = PlaybackTimeline.build(anchors, cap)!!

    // 24 gaps, all far over the cap -> 24 * 4s, regardless of the ~24-hour real span.
    assertEquals(24 * cap, tl.totalCompressedMs)
    assertTrue(
      tl.totalCompressedMs < 2 * 60 * 1000L,
      "25-step session must compress to well under two minutes, was ${tl.totalCompressedMs}ms",
    )
  }

  @Test
  fun `out-of-range compressed offsets clamp to the endpoints`() {
    val tl = PlaybackTimeline.build(listOf(100L, 600L), 4_000L)!!
    assertEquals(100L, tl.absoluteAt(-50L))
    assertEquals(600L, tl.absoluteAt(tl.totalCompressedMs + 9_999L))
  }

  @Test
  fun `unsorted and duplicate anchors are normalized`() {
    val tl = PlaybackTimeline.build(listOf(2_000L, 0L, 1_000L, 1_000L), 4_000L)!!
    assertEquals(2_000L, tl.totalCompressedMs)
    assertEquals(0L, tl.absoluteAt(0L))
    assertEquals(2_000L, tl.absoluteAt(2_000L))
  }

  // ── compressedAt: inverse of absoluteAt ──────────────────────────────────

  @Test
  fun `compressedAt inverts absoluteAt across a collapsed gap`() {
    val tenMinutes = 10 * 60 * 1000L
    val tl = PlaybackTimeline.build(listOf(0L, 10L, tenMinutes), 4_000L)!!
    // Endpoints.
    assertEquals(0L, tl.compressedAt(tl.startAbsMs))
    assertEquals(tl.totalCompressedMs, tl.compressedAt(tl.endAbsMs))
    // Round-trip: compressed -> absolute -> compressed lands back where it started
    // (within rounding) for several interior offsets.
    for (c in listOf(0L, 5L, 10L, 10L + 1_000L, tl.totalCompressedMs - 1L)) {
      val backC = tl.compressedAt(tl.absoluteAt(c))
      assertTrue(kotlin.math.abs(backC - c) <= 2L, "round-trip $c -> $backC")
    }
  }

  @Test
  fun `compressedAt clamps outside the spanned range`() {
    val tl = PlaybackTimeline.build(listOf(100L, 600L), 4_000L)!!
    assertEquals(0L, tl.compressedAt(50L))
    assertEquals(tl.totalCompressedMs, tl.compressedAt(9_999L))
  }

  // ── computePlaybackTick: shared autoplay step ────────────────────────────

  @Test
  fun `tick without a timeline advances 1 to 1 and snaps to endAbs`() {
    // Below the end: advances from playStart by elapsed.
    val t1 = computePlaybackTick(elapsedMs = 300L, playStartAbsMs = 1_000L, endAbsMs = 5_000L, timeline = null)
    assertEquals(1_300L, t1.targetAbsMs)
    assertFalse(t1.reachedEnd)
    // At/over the end: snaps exactly to endAbs (not the overshoot) and reports end.
    val t2 = computePlaybackTick(elapsedMs = 9_000L, playStartAbsMs = 1_000L, endAbsMs = 5_000L, timeline = null)
    assertEquals(5_000L, t2.targetAbsMs)
    assertTrue(t2.reachedEnd)
  }

  @Test
  fun `tick with a timeline ends at the timeline endpoint, never the passed endAbs`() {
    // Review #1: the end-snap must be the timeline's own endpoint, not the passed-in
    // (uncovered) endAbs. This also pins the video-tail semantics (review #2): the video
    // panel passes videoEndAbsMs = videoMetadata.endTimestampMs, which can extend past the
    // last log event (effectiveEndMs = tl.endAbsMs). Compressed export deliberately stops at
    // the last event and does NOT play a trailing event-less video tail — idle compression
    // by design. Here the passed end (999_999) is well past tl.endAbsMs (600_000).
    val tl = PlaybackTimeline.build(listOf(0L, 10L, 600_000L), 4_000L)!!
    val videoEndAbsPastLastLog = 999_999L
    val tick = computePlaybackTick(
      elapsedMs = tl.totalCompressedMs + 1_000L,
      playStartAbsMs = tl.startAbsMs,
      endAbsMs = videoEndAbsPastLastLog,
      timeline = tl,
    )
    assertTrue(tick.reachedEnd)
    assertEquals(tl.endAbsMs, tick.targetAbsMs)
    assertEquals(600_000L, tick.targetAbsMs)
  }

  @Test
  fun `tick with a timeline snaps to its own endpoint even when the passed end is smaller`() {
    // Reverse of the video-tail case: video ends BEFORE the last log, so the passed
    // videoEndAbs (500_000) is less than tl.endAbsMs (600_000). Compression still follows
    // the timeline and scrubs to its own endpoint; the frame cache clamps the sliver.
    val tl = PlaybackTimeline.build(listOf(0L, 10L, 600_000L), 4_000L)!!
    val videoEndAbsBeforeLastLog = 500_000L
    val tick = computePlaybackTick(
      elapsedMs = tl.totalCompressedMs + 1_000L,
      playStartAbsMs = tl.startAbsMs,
      endAbsMs = videoEndAbsBeforeLastLog,
      timeline = tl,
    )
    assertTrue(tick.reachedEnd)
    assertEquals(600_000L, tick.targetAbsMs)
  }

  @Test
  fun `tick with a timeline advances onto the correct absolute frame mid-playback`() {
    // A normal mid-run advance (not resume, not end): from session start, after some
    // compressed elapsed that lands inside the second segment, the target must equal the
    // timeline's own absolute mapping for that compressed offset.
    val tl = PlaybackTimeline.build(listOf(0L, 1_000L, 3_000L, 600_000L), 4_000L)!!
    val compressedElapsed = tl.totalCompressedMs - 100L // inside the final (collapsed) segment
    val tick = computePlaybackTick(
      elapsedMs = compressedElapsed,
      playStartAbsMs = tl.startAbsMs, // export starts at session start -> compressedAt == 0
      endAbsMs = 999_999L,
      timeline = tl,
    )
    assertFalse(tick.reachedEnd)
    assertEquals(tl.absoluteAt(compressedElapsed), tick.targetAbsMs)
  }

  @Test
  fun `tick with a timeline resumes from the current position after a mid-run relaunch`() {
    // review #2: a speed change relaunches the loop with elapsed reset to 0. Anchoring the
    // compressed clock on the current scrub position must NOT jump playback back to start.
    val tl = PlaybackTimeline.build(listOf(0L, 10L, 600_000L), 4_000L)!!
    val midAbs = tl.absoluteAt(tl.totalCompressedMs / 2)
    // Relaunch: elapsed starts at 0 again, playStart is the current (mid) position.
    val tick = computePlaybackTick(elapsedMs = 0L, playStartAbsMs = midAbs, endAbsMs = 999_999L, timeline = tl)
    assertFalse(tick.reachedEnd)
    // Inside a collapsed gap 1 compressed-ms maps to ~150 absolute-ms, so compare in
    // compressed space (the loop's clock), where the round-trip is near-exact. The point
    // is that the offset is preserved — not reset to 0 — so playback doesn't restart.
    assertTrue(
      kotlin.math.abs(tl.compressedAt(tick.targetAbsMs) - tl.totalCompressedMs / 2) <= 2L,
      "resume should preserve the compressed offset, target was ${tick.targetAbsMs}",
    )
    assertTrue(tick.targetAbsMs > tl.startAbsMs + 1_000L, "must not restart at session start")
  }

  // ── exportPlaybackAnchors: windowing rule ────────────────────────────────

  @Test
  fun `anchors always include the window bounds for an event-less session`() {
    assertEquals(listOf(100L, 900L), exportPlaybackAnchors(emptyList(), startMs = 100L, endMs = 900L))
  }

  @Test
  fun `anchors drop log timestamps outside the window`() {
    val anchors = exportPlaybackAnchors(
      logTimestampsMs = listOf(50L, 200L, 500L, 5_000L),
      startMs = 100L,
      endMs = 900L,
    )
    assertEquals(listOf(100L, 200L, 500L, 900L), anchors)
  }

  @Test
  fun `anchors where every log shares one timestamp collapse to a single gap`() {
    val anchors = exportPlaybackAnchors(listOf(400L, 400L, 400L), startMs = 100L, endMs = 900L)
    // build() distinct()s these to {100, 400, 900} -> two gaps.
    val tl = PlaybackTimeline.build(anchors, 4_000L)!!
    assertEquals(100L, tl.startAbsMs)
    assertEquals(900L, tl.endAbsMs)
  }

  // ── collapsedGapMsAt: surfacing fast-forwarded gaps ──────────────────────

  @Test
  fun `collapsedGapMsAt returns the real duration only inside a collapsed gap`() {
    // First gap (0->2000) is under the 4s cap (plays 1:1); second (2000->600000) is collapsed.
    val tl = PlaybackTimeline.build(listOf(0L, 2_000L, 600_000L), 4_000L)!!
    // Inside the uncompressed gap -> null (nothing was skipped).
    assertNull(tl.collapsedGapMsAt(1_000L))
    // Inside the collapsed gap -> its real span (598_000ms), regardless of where in it.
    assertEquals(598_000L, tl.collapsedGapMsAt(2_000L))
    assertEquals(598_000L, tl.collapsedGapMsAt(300_000L))
    assertEquals(598_000L, tl.collapsedGapMsAt(599_999L))
  }

  @Test
  fun `collapsedGapMsAt is null outside the spanned range and at the final anchor`() {
    val tl = PlaybackTimeline.build(listOf(0L, 600_000L), 4_000L)!!
    assertNull(tl.collapsedGapMsAt(-1L))
    assertNull(tl.collapsedGapMsAt(600_000L)) // end anchor: the gap is behind us
    assertNull(tl.collapsedGapMsAt(700_000L))
  }

  @Test
  fun `collapsedGapMsAt never fires when no gap exceeds the cap`() {
    val tl = PlaybackTimeline.build(listOf(0L, 1_000L, 2_000L, 3_000L), 4_000L)!!
    for (t in listOf(0L, 500L, 1_500L, 2_999L)) assertNull(tl.collapsedGapMsAt(t))
  }
}
