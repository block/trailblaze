package xyz.block.trailblaze.host.recording

import xyz.block.trailblaze.api.DriverNodeDetail
import xyz.block.trailblaze.api.TrailblazeNode
import xyz.block.trailblaze.api.TrailblazeNodeSelectorGenerator
import xyz.block.trailblaze.toolcalls.commands.TapOnByElementSelector
import xyz.block.trailblaze.toolcalls.commands.TapOnPointTrailblazeTool
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tests for [MaestroInteractionToolFactory].
 *
 * Three tap behaviors matter:
 *  1. **Tree available + selector resolves with round-trip-valid result** → emit
 *     `TapOnByElementSelector` carrying the rich `nodeSelector`. This is the happy path; what
 *     replays a year from now after the layout shifts a few pixels.
 *  2. **Tree available but resolveFromTap returns no node, OR the selector fails round-trip
 *     verification** → fall back to `TapOnPointTrailblazeTool`. Falling through *to* a
 *     selector that resolves to a different element than the user actually tapped would be
 *     worse than just recording the coordinate.
 *  3. **Tree absent (e.g. driver doesn't expose accessibility on this platform)** →
 *     `TapOnPointTrailblazeTool` with the raw coordinate.
 *
 * `findSelectorCandidates` is no longer gated on round-trip validity: empty when the tap
 * landed on no node, populated otherwise — even when the original cascade bailed to
 * `tapOnPoint`. The picker UI surfaces a warning chip in that mode so the author knows
 * they're overriding a fallback rather than picking from a verified set; the round-trip-fail
 * gate would conflate "no candidates exist" with "candidates suppressed", which made the
 * picker silently disappear on layouts where parent/child overlap is the norm.
 */
class MaestroInteractionToolFactoryTest {

  private val factory = MaestroInteractionToolFactory(deviceWidth = 1080, deviceHeight = 1920)

  // -- Tap tool --------------------------------------------------------------

  @Test
  fun `createTapTool with null tree falls through to tapOnPoint`() {
    val (tool, name) = factory.createTapTool(node = null, x = 42, y = 84, trailblazeNodeTree = null)
    val tap = assertIs<TapOnPointTrailblazeTool>(tool)
    assertEquals(42, tap.x)
    assertEquals(84, tap.y)
    assertEquals(false, tap.longPress)
    assertEquals("tapOnPoint", name)
  }

  @Test
  fun `createTapTool with tap outside any node falls through to tapOnPoint`() {
    val tree = singleLeafTree(
      bounds = TrailblazeNode.Bounds(left = 0, top = 0, right = 100, bottom = 100),
      detail = DriverNodeDetail.AndroidMaestro(text = "Hello"),
    )
    // Coordinate well outside the only node — resolveFromTap will return null.
    val (tool, name) = factory.createTapTool(
      node = null, x = 999, y = 999, trailblazeNodeTree = tree,
    )
    assertIs<TapOnPointTrailblazeTool>(tool)
    assertEquals("tapOnPoint", name)
  }

  @Test
  fun `createTapTool with valid round-trip emits TapOnByElementSelector`() {
    // A tree with one identifiable node — resolveFromTap returns it, the generator finds a
    // unique text-based selector, and the round-trip resolves back to the same node.
    val target = TrailblazeNode(
      nodeId = 1L,
      bounds = TrailblazeNode.Bounds(left = 0, top = 0, right = 100, bottom = 100),
      driverDetail = DriverNodeDetail.AndroidMaestro(text = "Submit"),
    )
    val root = TrailblazeNode(
      nodeId = 0L,
      children = listOf(target),
      driverDetail = DriverNodeDetail.AndroidMaestro(),
    )
    val (tool, name) = factory.createTapTool(
      node = null, x = 50, y = 50, trailblazeNodeTree = root,
    )
    val selectorTool = assertIs<TapOnByElementSelector>(tool)
    assertEquals("tapOnElementBySelector", name)
    assertTrue(selectorTool.nodeSelector != null, "nodeSelector should be populated")
    assertEquals(false, selectorTool.longPress)
  }

  // -- Long press follows the same cascade -----------------------------------

  @Test
  fun `createLongPressTool with null tree falls through to tapOnPoint with longPress flag`() {
    val (tool, _) = factory.createLongPressTool(
      node = null, x = 1, y = 2, trailblazeNodeTree = null,
    )
    val tap = assertIs<TapOnPointTrailblazeTool>(tool)
    assertEquals(true, tap.longPress)
  }

  // -- Selector candidates --------------------------------------------------

  @Test
  fun `findSelectorCandidates returns empty list when tree is null`() {
    val candidates = factory.findSelectorCandidates(trailblazeNodeTree = null, x = 1, y = 1)
    assertTrue(candidates.isEmpty())
  }

  @Test
  fun `findSelectorCandidates returns empty list when no node at point`() {
    val tree = singleLeafTree(
      bounds = TrailblazeNode.Bounds(left = 0, top = 0, right = 10, bottom = 10),
      detail = DriverNodeDetail.AndroidMaestro(text = "Hello"),
    )
    val candidates = factory.findSelectorCandidates(trailblazeNodeTree = tree, x = 999, y = 999)
    assertTrue(candidates.isEmpty(), "tap outside all nodes should produce no candidates")
  }

  @Test
  fun `findSelectorCandidates returns candidates when round-trip valid`() {
    val target = TrailblazeNode(
      nodeId = 1L,
      bounds = TrailblazeNode.Bounds(left = 0, top = 0, right = 100, bottom = 100),
      driverDetail = DriverNodeDetail.AndroidMaestro(text = "Submit"),
    )
    val root = TrailblazeNode(
      nodeId = 0L,
      children = listOf(target),
      driverDetail = DriverNodeDetail.AndroidMaestro(),
    )
    val candidates = factory.findSelectorCandidates(trailblazeNodeTree = root, x = 50, y = 50)
    assertTrue(candidates.isNotEmpty(), "valid round-trip should surface picker candidates")
    assertTrue(candidates.first().isBest, "first candidate should be flagged isBest=true")
  }

  /**
   * The gate's previous behavior was: round-trip fails → return empty → picker hidden.
   * With the gate lifted, the picker stays available so the author can promote a coordinate
   * tap to a selector tap. This is paired with a UI-side warning chip (driven by
   * `tool is TapOnPointTrailblazeTool`) so the author knows they're overriding a fallback.
   *
   * Tree shape: a parent with identifiable AndroidAccessibility detail, whose center is
   * occluded by a small unidentifiable child. Tap a parent corner where the child isn't
   * present — `resolveFromTap` returns the parent, `findBestSelector` produces a unique
   * selector for it, and the selector's resolved center hit-tests back to the *child*
   * (not the parent), so `roundTripValid` is false. Same fixture pattern as
   * `TrailblazeNodeHitTestAndTapResolutionTest`.
   */
  @Test
  fun `findSelectorCandidates surfaces candidates even when round-trip fails`() {
    val occludingChild = TrailblazeNode(
      nodeId = 2L,
      bounds = TrailblazeNode.Bounds(left = 40, top = 40, right = 60, bottom = 60),
      driverDetail = DriverNodeDetail.AndroidAccessibility(text = "Label"),
    )
    val parent = TrailblazeNode(
      nodeId = 1L,
      bounds = TrailblazeNode.Bounds(left = 0, top = 0, right = 100, bottom = 100),
      children = listOf(occludingChild),
      driverDetail = DriverNodeDetail.AndroidAccessibility(
        className = "android.widget.FrameLayout",
        resourceId = "com.example:id/wrapper",
      ),
    )
    val root = TrailblazeNode(
      nodeId = 0L,
      bounds = TrailblazeNode.Bounds(left = 0, top = 0, right = 400, bottom = 400),
      children = listOf(parent),
      driverDetail = DriverNodeDetail.AndroidAccessibility(),
    )

    // Confirm the fixture actually triggers round-trip-fail before relying on it.
    val resolution = TrailblazeNodeSelectorGenerator.resolveFromTap(root, 5, 5)
    assertNotNull(resolution)
    assertEquals(parent.nodeId, resolution.targetNode.nodeId)
    assertTrue(
      !resolution.roundTripValid,
      "fixture must produce roundTripValid=false; otherwise this test isn't exercising the gate",
    )

    val candidates = factory.findSelectorCandidates(trailblazeNodeTree = root, x = 5, y = 5)
    assertTrue(
      candidates.isNotEmpty(),
      "round-trip-fail should still surface candidates so the picker can promote tapOnPoint",
    )
  }

  // -- Helpers ---------------------------------------------------------------

  private fun singleLeafTree(
    bounds: TrailblazeNode.Bounds,
    detail: DriverNodeDetail,
  ): TrailblazeNode {
    val leaf = TrailblazeNode(nodeId = 1L, bounds = bounds, driverDetail = detail)
    return TrailblazeNode(
      nodeId = 0L,
      children = listOf(leaf),
      driverDetail = DriverNodeDetail.AndroidMaestro(),
    )
  }
}
