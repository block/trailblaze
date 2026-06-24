package xyz.block.trailblaze.android.accessibility

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import xyz.block.trailblaze.android.accessibility.HierarchyCoverageAssessor.NodeBounds

/**
 * Unit tests for [HierarchyCoverageAssessor] — the detector that tells a full-screen capture from a
 * truncated/partial one (the "rightmost slice" Compose accessibility failure).
 *
 * Screen is a portrait phone: 1080 x 2400.
 */
class HierarchyCoverageAssessorTest {

  private val screenW = 1080
  private val screenH = 2400

  /** A content-bearing (has text), visible, positioned node. */
  private fun content(left: Int, top: Int, right: Int, bottom: Int) =
    NodeBounds(left, top, right, bottom, isVisibleToUser = true, hasText = true)

  @Test
  fun `full-screen content is not truncated`() {
    // Content spread across the full width and most of the height — a normal screen.
    val nodes = listOf(
      content(48, 120, 1032, 220), // top bar / title
      content(48, 400, 700, 480), // left-aligned body text
      content(48, 600, 1032, 700), // full-width row
      content(700, 2100, 1032, 2300), // bottom-right action
      content(48, 2100, 360, 2300), // bottom-left action
    )
    val a = HierarchyCoverageAssessor.assess(nodes, screenW, screenH)
    assertFalse(a.looksTruncated, a.reason)
    assertTrue(a.horizontalCoverage > 0.9, "expected near-full width coverage, got ${a.horizontalCoverage}")
  }

  @Test
  fun `right-edge slice is truncated (the reported symptom)`() {
    // Every content node clustered in x=864..1080 on a 1080px screen — left ~80% empty.
    val nodes = listOf(
      content(864, 200, 1040, 260),
      content(880, 600, 1056, 660),
      content(900, 1200, 1060, 1260),
      content(870, 1800, 1050, 1860),
      content(890, 2200, 1058, 2260),
    )
    val a = HierarchyCoverageAssessor.assess(nodes, screenW, screenH)
    assertTrue(a.looksTruncated, a.reason)
    assertTrue(a.reason.contains("right"), a.reason)
  }

  @Test
  fun `left-aligned narrow content is NOT truncated`() {
    // A left-aligned column of short labels — content touches the left edge, large empty band on
    // the right. This is the single most common normal screen (settings/list/form rows), and
    // Compose `Text` semantics bounds wrap the text rather than the full row, so it would trip a
    // naive symmetric slice check. The mirror of the right-edge slice must NOT be flagged.
    val nodes = listOf(
      content(20, 200, 200, 260),
      content(24, 600, 210, 660),
      content(30, 1200, 220, 1260),
      content(18, 1800, 205, 1860),
    )
    val a = HierarchyCoverageAssessor.assess(nodes, screenW, screenH)
    assertFalse(a.looksTruncated, a.reason)
  }

  @Test
  fun `left-aligned settings-style rows are NOT truncated`() {
    // Codex's reported false-positive scenario: a list of left-aligned settings rows whose text
    // nodes wrap their label (so each is narrow and the union is well under half the width) while
    // touching the left edge. Must be left alone — otherwise the gate spins to the 1s cap on every
    // ordinary snapshot/assertion poll.
    val nodes = listOf(
      content(48, 300, 360, 380),
      content(48, 460, 520, 540),
      content(48, 620, 300, 700),
      content(48, 780, 600, 860),
      content(48, 940, 280, 1020),
    )
    val a = HierarchyCoverageAssessor.assess(nodes, screenW, screenH)
    assertFalse(a.looksTruncated, a.reason)
  }

  @Test
  fun `centered dialog is not truncated`() {
    // A modal centered horizontally: gaps split symmetrically (~25% each side), so neither edge
    // band reaches the one-sided threshold even though total coverage is ~50%.
    val nodes = listOf(
      content(270, 900, 810, 980), // title
      content(270, 1040, 810, 1120), // body
      content(290, 1240, 520, 1320), // cancel
      content(560, 1240, 790, 1320), // confirm
    )
    val a = HierarchyCoverageAssessor.assess(nodes, screenW, screenH)
    assertFalse(a.looksTruncated, a.reason)
  }

  @Test
  fun `too few content nodes is never truncated`() {
    // A loading screen with one centered label squished to one side must not trip the gate.
    val nodes = listOf(
      content(900, 1180, 1060, 1240),
      content(910, 1300, 1050, 1360),
    )
    val a = HierarchyCoverageAssessor.assess(nodes, screenW, screenH)
    assertFalse(a.looksTruncated, a.reason)
    assertEquals(2, a.contentNodes)
  }

  @Test
  fun `high zero-bounds ratio is truncated even when one node is positioned`() {
    // Most content nodes report (0,0,0,0) — a tree mid-commit — plus a couple positioned ones.
    val nodes = listOf(
      content(0, 0, 0, 0),
      content(0, 0, 0, 0),
      content(0, 0, 0, 0),
      content(0, 0, 0, 0),
      content(40, 200, 1040, 280),
    )
    val a = HierarchyCoverageAssessor.assess(nodes, screenW, screenH)
    assertTrue(a.looksTruncated, a.reason)
    assertTrue(a.reason.contains("zero bounds"), a.reason)
  }

  @Test
  fun `non-content container bounds do not mask a truncated content slice`() {
    // The full-screen AndroidComposeView root (no text) plus invisible nodes must be ignored; only
    // the right-edge text nodes count, so the slice is still detected.
    val nodes = listOf(
      NodeBounds(0, 0, screenW, screenH, isVisibleToUser = true, hasText = false), // root container
      NodeBounds(0, 0, screenW, screenH, isVisibleToUser = false, hasText = true), // offscreen text
      content(864, 200, 1040, 260),
      content(880, 600, 1056, 660),
      content(900, 1200, 1060, 1260),
      content(870, 1800, 1050, 1860),
    )
    val a = HierarchyCoverageAssessor.assess(nodes, screenW, screenH)
    assertTrue(a.looksTruncated, a.reason)
    // Only the 4 visible text nodes are counted as content; the container and offscreen node aren't.
    assertEquals(4, a.contentNodes)
  }

  @Test
  fun `empty input is not truncated`() {
    val a = HierarchyCoverageAssessor.assess(emptyList(), screenW, screenH)
    assertFalse(a.looksTruncated, a.reason)
  }

  @Test
  fun `unknown screen dimensions skip the check`() {
    val nodes = listOf(content(864, 200, 1040, 260), content(880, 600, 1056, 660))
    val a = HierarchyCoverageAssessor.assess(nodes, 0, 0)
    assertFalse(a.looksTruncated, a.reason)
  }
}
