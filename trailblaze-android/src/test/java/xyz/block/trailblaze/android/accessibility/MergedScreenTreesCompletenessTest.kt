package xyz.block.trailblaze.android.accessibility

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import xyz.block.trailblaze.android.accessibility.TrailblazeAccessibilityService.Companion.MergedScreenTrees

/**
 * A capture builds two projections of the same window roots — the Maestro-shape `treeNode` and the
 * accessibility-shape `accessibilityNode` — by two independent walks. With the node cache dropped
 * every node in each walk is a live fetch, so a busy app can answer one walk and stiff the other:
 * the walks can genuinely disagree about what they got.
 *
 * These tests pin the consequence: completeness is a property of ONE projection, and a consumer is
 * told about the tree it is going to read. When both walks shared a single tally, a child lost only
 * in the Maestro walk marked a whole accessibility tree untrustworthy, and `assertNotVisible` —
 * which reads only the accessibility tree — refused to conclude absence from a tree that held
 * every node the app had advertised to it. It failed on a timeout instead of passing.
 */
class MergedScreenTreesCompletenessTest {

  @Test
  fun `a drop in the Maestro walk alone leaves the accessibility tree trustworthy`() {
    val capture = MergedScreenTrees(
      treeNode = null,
      accessibilityNode = null,
      foregroundAppId = null,
      captureCoverage = null,
      droppedFetchesForAccessibilityNode = 0,
      droppedFetchesForTreeNode = 2,
    )

    // The assertion this protects: absence checks read `accessibilityNode` and must still trust it.
    assertTrue(capture.isAccessibilityNodeComplete)
    assertFalse(capture.isTreeNodeComplete)
    // ...while a diagnostic describing the capture as a whole still reports that something was lost.
    assertFalse(capture.isComplete)
  }

  @Test
  fun `a drop in the accessibility walk alone leaves the Maestro tree trustworthy`() {
    val capture = MergedScreenTrees(
      treeNode = null,
      accessibilityNode = null,
      foregroundAppId = null,
      captureCoverage = null,
      droppedFetchesForAccessibilityNode = 3,
      droppedFetchesForTreeNode = 0,
    )

    assertFalse(capture.isAccessibilityNodeComplete)
    assertTrue(capture.isTreeNodeComplete)
    assertFalse(capture.isComplete)
  }

  @Test
  fun `a capture that lost nothing is complete in both projections`() {
    val capture = MergedScreenTrees(
      treeNode = null,
      accessibilityNode = null,
      foregroundAppId = null,
      captureCoverage = null,
      droppedFetchesForAccessibilityNode = 0,
      droppedFetchesForTreeNode = 0,
    )

    assertTrue(capture.isAccessibilityNodeComplete)
    assertTrue(capture.isTreeNodeComplete)
    assertTrue(capture.isComplete)
  }

  @Test
  fun `an unresolvable window root is missing from both projections`() {
    // getCaptureWindowRoots tallies these once and the capture adds them to BOTH counts, because a
    // window absent from the capture is absent from both shapes of it. Nothing else in the capture
    // is shared, so this is the one case where the two numbers must move together.
    val capture = MergedScreenTrees(
      treeNode = null,
      accessibilityNode = null,
      foregroundAppId = null,
      captureCoverage = null,
      droppedFetchesForAccessibilityNode = 1,
      droppedFetchesForTreeNode = 1,
    )

    assertFalse(capture.isAccessibilityNodeComplete)
    assertFalse(capture.isTreeNodeComplete)
  }
}
