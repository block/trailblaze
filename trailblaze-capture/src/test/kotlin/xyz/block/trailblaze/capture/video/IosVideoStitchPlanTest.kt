package xyz.block.trailblaze.capture.video

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit tests for the pure [IosVideoStitchPlan]. No ffmpeg, no device — the whole point of
 * extracting the planner is that the gap-preserving wall-clock math is testable with plain numbers.
 */
class IosVideoStitchPlanTest {

  private fun seg(name: String, start: Long, end: Long) =
    IosVideoStitchPlan.VideoSegment(File("/tmp/$name"), startEpochMs = start, endEpochMs = end)

  @Test
  fun `empty segments returns null`() {
    assertNull(IosVideoStitchPlan.plan(emptyList()))
  }

  @Test
  fun `single segment is a passthrough plan with no concat and no duration directive`() {
    val plan = IosVideoStitchPlan.plan(listOf(seg("baguette.mp4", start = 1_000, end = 61_000)))!!
    assertFalse(plan.needsConcat, "single segment shouldn't need a concat pass")
    assertEquals(1, plan.entries.size)
    assertNull(plan.entries.single().presentedDurationMs, "final/only entry plays its natural length")
    assertEquals(1_000, plan.overallStartEpochMs)
    assertEquals(61_000, plan.overallEndEpochMs)
  }

  @Test
  fun `two segments with a gap preserve wall-clock via the first entry's presented duration`() {
    // baguette recorded [1000, 40000] then died; simctl restarted and recorded [43000, 61000].
    // The 3s gap (40000..43000) must be preserved so the simctl portion sits at its true offset.
    val baguette = seg("baguette.mp4", start = 1_000, end = 40_000)
    val simctl = seg("simctl.mp4", start = 43_000, end = 61_000)
    val plan = IosVideoStitchPlan.plan(listOf(baguette, simctl))!!

    assertTrue(plan.needsConcat)
    assertEquals(2, plan.entries.size)
    // First entry is presented for (nextStart - thisStart) = 43000 - 1000 = 42000ms, i.e. its
    // 39s of content plus the 3s gap frozen on its last frame — so simctl begins at offset 42s,
    // which equals its true wall-clock offset (43000 - 1000).
    assertEquals(42_000, plan.entries[0].presentedDurationMs)
    assertNull(plan.entries[1].presentedDurationMs)
    assertEquals(1_000, plan.overallStartEpochMs)
    assertEquals(61_000, plan.overallEndEpochMs)
  }

  @Test
  fun `segments are ordered by start epoch defensively`() {
    val later = seg("simctl.mp4", start = 43_000, end = 61_000)
    val earlier = seg("baguette.mp4", start = 1_000, end = 40_000)
    val plan = IosVideoStitchPlan.plan(listOf(later, earlier))!!
    assertEquals("baguette.mp4", plan.entries[0].file.name)
    assertEquals("simctl.mp4", plan.entries[1].file.name)
    assertEquals(1_000, plan.overallStartEpochMs)
  }

  @Test
  fun `ffconcat script emits file and duration directives and escapes single quotes`() {
    val plan = IosVideoStitchPlan.plan(
      listOf(
        IosVideoStitchPlan.VideoSegment(File("/tmp/a b's.mp4"), 0, 2_000),
        IosVideoStitchPlan.VideoSegment(File("/tmp/second.mp4"), 2_000, 5_000),
      ),
    )!!
    val script = IosVideoStitchPlan.toFfconcatScript(plan)
    assertTrue(script.startsWith("ffconcat version 1.0"))
    // First entry carries a duration (2.0s span from 0..2000); path's single quote escaped.
    assertTrue(script.contains("file '/tmp/a b'\\''s.mp4'"), "expected escaped path, got:\n$script")
    assertTrue(script.contains("duration 2.000"))
    // Final entry has no duration directive.
    assertTrue(script.contains("file '/tmp/second.mp4'"))
    assertFalse(script.trimEnd().endsWith("duration"), script)
  }

  @Test
  fun `clock wobble producing an equal or out-of-order next-start floors duration at 1ms`() {
    // Pathological: two segments whose starts are equal (or the sort keeps them adjacent). The
    // presented duration must never go negative, and must not be 0 either — a 0.000 duration
    // directive is dropped by the concat demuxer, silently discarding the segment. Floor at 1ms.
    val plan = IosVideoStitchPlan.plan(
      listOf(seg("a.mp4", start = 5_000, end = 5_000), seg("b.mp4", start = 5_000, end = 9_000)),
    )!!
    assertEquals(1L, plan.entries[0].presentedDurationMs)
  }
}
