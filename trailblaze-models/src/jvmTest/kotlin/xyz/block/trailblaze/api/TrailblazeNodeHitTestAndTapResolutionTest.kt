package xyz.block.trailblaze.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for [TrailblazeNode.hitTest] and [TrailblazeNodeSelectorGenerator.resolveFromTap],
 * covering Z-index ordering (smallest-area wins), nested overlaps, and full round-trip
 * tap → selector → resolve → hit-test verification.
 */
class TrailblazeNodeHitTestAndTapResolutionTest {

  private var nextId = 1L

  private fun node(
    detail: DriverNodeDetail.AndroidAccessibility = DriverNodeDetail.AndroidAccessibility(),
    bounds: TrailblazeNode.Bounds? = TrailblazeNode.Bounds(0, 0, 100, 50),
    children: List<TrailblazeNode> = emptyList(),
  ): TrailblazeNode {
    val id = nextId++
    return TrailblazeNode(nodeId = id, children = children, bounds = bounds, driverDetail = detail)
  }

  // ======================================================================
  // hitTest: basic cases
  // ======================================================================

  @Test
  fun `hitTest returns null for point outside all bounds`() {
    nextId = 1L
    val root = node(
      bounds = TrailblazeNode.Bounds(0, 0, 100, 100),
      children = listOf(
        node(bounds = TrailblazeNode.Bounds(10, 10, 50, 50)),
      ),
    )
    assertNull(root.hitTest(200, 200))
  }

  @Test
  fun `hitTest returns single node when point is inside`() {
    nextId = 1L
    val child = node(
      detail = DriverNodeDetail.AndroidAccessibility(text = "Button"),
      bounds = TrailblazeNode.Bounds(10, 10, 90, 40),
    )
    val root = node(
      bounds = TrailblazeNode.Bounds(0, 0, 100, 50),
      children = listOf(child),
    )
    val hit = root.hitTest(50, 25)
    assertNotNull(hit)
    assertEquals(child.nodeId, hit.nodeId)
  }

  @Test
  fun `hitTest returns root when point is inside root but outside children`() {
    nextId = 1L
    val child = node(
      bounds = TrailblazeNode.Bounds(10, 10, 50, 40),
    )
    val root = node(
      detail = DriverNodeDetail.AndroidAccessibility(text = "Root"),
      bounds = TrailblazeNode.Bounds(0, 0, 100, 100),
      children = listOf(child),
    )
    // Point at (80, 80) is inside root but outside child
    val hit = root.hitTest(80, 80)
    assertNotNull(hit)
    assertEquals(root.nodeId, hit.nodeId)
  }

  // ======================================================================
  // hitTest: Z-index ordering (smallest area wins)
  // ======================================================================

  @Test
  fun `hitTest picks smallest overlapping node - child over parent`() {
    nextId = 1L
    val child = node(
      detail = DriverNodeDetail.AndroidAccessibility(text = "Child Button"),
      bounds = TrailblazeNode.Bounds(20, 20, 80, 40),
    )
    val parent = node(
      detail = DriverNodeDetail.AndroidAccessibility(className = "android.widget.FrameLayout"),
      bounds = TrailblazeNode.Bounds(0, 0, 100, 100),
      children = listOf(child),
    )
    val root = node(
      bounds = TrailblazeNode.Bounds(0, 0, 500, 500),
      children = listOf(parent),
    )

    // Tap at child's center (50, 30) - should hit child, not parent or root
    val hit = root.hitTest(50, 30)
    assertNotNull(hit)
    assertEquals(child.nodeId, hit.nodeId)
  }

  @Test
  fun `hitTest picks grandchild over child over parent`() {
    nextId = 1L
    val grandchild = node(
      detail = DriverNodeDetail.AndroidAccessibility(text = "Icon"),
      bounds = TrailblazeNode.Bounds(30, 30, 70, 60),
    )
    val child = node(
      detail = DriverNodeDetail.AndroidAccessibility(className = "android.widget.Button"),
      bounds = TrailblazeNode.Bounds(10, 10, 90, 80),
      children = listOf(grandchild),
    )
    val parent = node(
      detail = DriverNodeDetail.AndroidAccessibility(className = "android.widget.FrameLayout"),
      bounds = TrailblazeNode.Bounds(0, 0, 100, 100),
      children = listOf(child),
    )

    // Tap at grandchild center (50, 45)
    val hit = parent.hitTest(50, 45)
    assertNotNull(hit)
    assertEquals(grandchild.nodeId, hit.nodeId)
  }

  @Test
  fun `hitTest picks smaller sibling when overlapping`() {
    nextId = 1L
    // Overlapping siblings: a large background and a small floating button
    val background = node(
      detail = DriverNodeDetail.AndroidAccessibility(className = "android.view.View"),
      bounds = TrailblazeNode.Bounds(0, 0, 400, 400),
    )
    val floatingButton = node(
      detail = DriverNodeDetail.AndroidAccessibility(text = "FAB"),
      bounds = TrailblazeNode.Bounds(320, 320, 380, 380),
    )
    val root = node(
      bounds = TrailblazeNode.Bounds(0, 0, 400, 400),
      children = listOf(background, floatingButton),
    )

    // Tap at floating button center (350, 350) — both contain the point,
    // but floating button is smaller
    val hit = root.hitTest(350, 350)
    assertNotNull(hit)
    assertEquals(floatingButton.nodeId, hit.nodeId)
  }

  @Test
  fun `hitTest handles dialog overlay on top of content`() {
    nextId = 1L
    // Content behind dialog
    val contentButton = node(
      detail = DriverNodeDetail.AndroidAccessibility(text = "Background Button"),
      bounds = TrailblazeNode.Bounds(50, 200, 350, 260),
    )
    val contentArea = node(
      detail = DriverNodeDetail.AndroidAccessibility(className = "android.widget.LinearLayout"),
      bounds = TrailblazeNode.Bounds(0, 0, 400, 800),
      children = listOf(contentButton),
    )

    // Dialog overlay (smaller area, but contains the tap point)
    val dialogButton = node(
      detail = DriverNodeDetail.AndroidAccessibility(text = "OK"),
      bounds = TrailblazeNode.Bounds(140, 220, 260, 260),
    )
    val dialog = node(
      detail = DriverNodeDetail.AndroidAccessibility(paneTitle = "Confirm"),
      bounds = TrailblazeNode.Bounds(100, 200, 300, 400),
      children = listOf(dialogButton),
    )

    val root = node(
      bounds = TrailblazeNode.Bounds(0, 0, 400, 800),
      children = listOf(contentArea, dialog),
    )

    // Tap at (200, 240) — both contentButton and dialogButton contain this point.
    // dialogButton is smaller, so it should win
    val hit = root.hitTest(200, 240)
    assertNotNull(hit)
    assertEquals(dialogButton.nodeId, hit.nodeId)
  }

  @Test
  fun `hitTest boundary - point on exact edge is within bounds`() {
    nextId = 1L
    val child = node(
      detail = DriverNodeDetail.AndroidAccessibility(text = "Edge"),
      bounds = TrailblazeNode.Bounds(10, 10, 50, 40),
    )
    val root = node(
      bounds = TrailblazeNode.Bounds(0, 0, 100, 100),
      children = listOf(child),
    )
    // Point on exact left edge
    val hit = root.hitTest(10, 25)
    assertNotNull(hit)
    assertEquals(child.nodeId, hit.nodeId)
  }

  @Test
  fun `hitTest node without bounds is ignored`() {
    nextId = 1L
    val noBoundsChild = node(
      detail = DriverNodeDetail.AndroidAccessibility(text = "Ghost"),
      bounds = null,
    )
    val visibleChild = node(
      detail = DriverNodeDetail.AndroidAccessibility(text = "Visible"),
      bounds = TrailblazeNode.Bounds(0, 0, 100, 50),
    )
    val root = node(
      bounds = TrailblazeNode.Bounds(0, 0, 100, 100),
      children = listOf(noBoundsChild, visibleChild),
    )
    val hit = root.hitTest(50, 25)
    assertNotNull(hit)
    assertEquals(visibleChild.nodeId, hit.nodeId)
  }

  // ======================================================================
  // resolveFromTap: basic cases
  // ======================================================================

  @Test
  fun `resolveFromTap returns null for empty area`() {
    nextId = 1L
    val root = node(
      bounds = TrailblazeNode.Bounds(0, 0, 100, 100),
      children = listOf(
        node(bounds = TrailblazeNode.Bounds(10, 10, 50, 50)),
      ),
    )
    assertNull(TrailblazeNodeSelectorGenerator.resolveFromTap(root, 999, 999))
  }

  @Test
  fun `resolveFromTap generates valid unique selector`() {
    nextId = 1L
    val target = node(
      detail = DriverNodeDetail.AndroidAccessibility(text = "Submit"),
      bounds = TrailblazeNode.Bounds(50, 100, 200, 150),
    )
    val other = node(
      detail = DriverNodeDetail.AndroidAccessibility(text = "Cancel"),
      bounds = TrailblazeNode.Bounds(50, 200, 200, 250),
    )
    val root = node(
      bounds = TrailblazeNode.Bounds(0, 0, 400, 400),
      children = listOf(target, other),
    )

    val result = TrailblazeNodeSelectorGenerator.resolveFromTap(root, 125, 125)
    assertNotNull(result)
    assertEquals(target.nodeId, result.targetNode.nodeId)
    assertNotNull(result.resolvedCenter)
    assertTrue(result.roundTripValid, "Round-trip should be valid for simple case")
  }

  @Test
  fun `resolveFromTap selector resolves back to same node`() {
    nextId = 1L
    val button = node(
      detail = DriverNodeDetail.AndroidAccessibility(
        text = "Buy Now",
        className = "android.widget.Button",
      ),
      bounds = TrailblazeNode.Bounds(100, 300, 300, 360),
    )
    val root = node(
      bounds = TrailblazeNode.Bounds(0, 0, 400, 800),
      children = listOf(button),
    )

    val result = TrailblazeNodeSelectorGenerator.resolveFromTap(root, 200, 330)
    assertNotNull(result)

    // Verify the selector resolves to the same node
    val resolveResult = TrailblazeNodeSelectorResolver.resolve(root, result.selector)
    assertTrue(resolveResult is TrailblazeNodeSelectorResolver.ResolveResult.SingleMatch)
    assertEquals(button.nodeId, resolveResult.node.nodeId)
  }

  // ======================================================================
  // resolveFromTap: Z-index / child overlap scenarios
  // ======================================================================

  @Test
  fun `resolveFromTap hits child not parent when tapping overlapping area`() {
    nextId = 1L
    val child = node(
      detail = DriverNodeDetail.AndroidAccessibility(text = "Child Text"),
      bounds = TrailblazeNode.Bounds(20, 20, 80, 60),
    )
    val parent = node(
      detail = DriverNodeDetail.AndroidAccessibility(
        className = "android.widget.LinearLayout",
        resourceId = "com.example:id/container",
      ),
      bounds = TrailblazeNode.Bounds(0, 0, 100, 100),
      children = listOf(child),
    )
    val root = node(
      bounds = TrailblazeNode.Bounds(0, 0, 400, 400),
      children = listOf(parent),
    )

    // Tap at child center — should resolve to child, not parent
    val result = TrailblazeNodeSelectorGenerator.resolveFromTap(root, 50, 40)
    assertNotNull(result)
    assertEquals(child.nodeId, result.targetNode.nodeId)
  }

  @Test
  fun `resolveFromTap roundTripValid detects when center hits different node`() {
    nextId = 1L
    // Parent with child exactly at center — selector for parent resolves to parent center,
    // but hitTest at parent center would hit the child instead
    val smallChild = node(
      detail = DriverNodeDetail.AndroidAccessibility(text = "Label"),
      bounds = TrailblazeNode.Bounds(40, 40, 60, 60), // centered in parent
    )
    val parent = node(
      detail = DriverNodeDetail.AndroidAccessibility(
        className = "android.widget.FrameLayout",
        resourceId = "com.example:id/wrapper",
      ),
      bounds = TrailblazeNode.Bounds(0, 0, 100, 100),
      children = listOf(smallChild),
    )
    val root = node(
      bounds = TrailblazeNode.Bounds(0, 0, 400, 400),
      children = listOf(parent),
    )

    // Tap the parent at a corner where child is NOT present — hits parent
    val tapResult = TrailblazeNodeSelectorGenerator.resolveFromTap(root, 5, 5)
    assertNotNull(tapResult)
    assertEquals(parent.nodeId, tapResult.targetNode.nodeId)

    // The selector resolves to parent's center (50, 50) which is covered by the child.
    // So roundTripValid should be false if the center point hits the child.
    if (tapResult.resolvedCenter != null) {
      val (cx, cy) = tapResult.resolvedCenter
      val hitAtCenter = root.hitTest(cx, cy)
      if (hitAtCenter?.nodeId != parent.nodeId) {
        // The round trip correctly detected this
        assertTrue(!tapResult.roundTripValid)
      }
    }
  }

  @Test
  fun `resolveFromTap roundTripValid true when no child overlap at center`() {
    nextId = 1L
    val target = node(
      detail = DriverNodeDetail.AndroidAccessibility(text = "Big Button"),
      bounds = TrailblazeNode.Bounds(50, 50, 350, 150),
    )
    val root = node(
      bounds = TrailblazeNode.Bounds(0, 0, 400, 400),
      children = listOf(target),
    )

    val result = TrailblazeNodeSelectorGenerator.resolveFromTap(root, 200, 100)
    assertNotNull(result)
    assertTrue(result.roundTripValid)
  }

  @Test
  fun `resolveFromTap with deeply nested hierarchy`() {
    nextId = 1L
    val deepLeaf = node(
      detail = DriverNodeDetail.AndroidAccessibility(text = "Deep Leaf"),
      bounds = TrailblazeNode.Bounds(45, 45, 55, 55),
    )
    val level2 = node(
      detail = DriverNodeDetail.AndroidAccessibility(className = "android.view.View"),
      bounds = TrailblazeNode.Bounds(40, 40, 60, 60),
      children = listOf(deepLeaf),
    )
    val level1 = node(
      detail = DriverNodeDetail.AndroidAccessibility(className = "android.widget.LinearLayout"),
      bounds = TrailblazeNode.Bounds(20, 20, 80, 80),
      children = listOf(level2),
    )
    val root = node(
      bounds = TrailblazeNode.Bounds(0, 0, 100, 100),
      children = listOf(level1),
    )

    val result = TrailblazeNodeSelectorGenerator.resolveFromTap(root, 50, 50)
    assertNotNull(result)
    // Should hit the deepest/smallest node
    assertEquals(deepLeaf.nodeId, result.targetNode.nodeId)
  }

  @Test
  fun `resolveFromTap with multiple siblings picks correct one`() {
    nextId = 1L
    val button1 = node(
      detail = DriverNodeDetail.AndroidAccessibility(text = "First"),
      bounds = TrailblazeNode.Bounds(10, 10, 100, 50),
    )
    val button2 = node(
      detail = DriverNodeDetail.AndroidAccessibility(text = "Second"),
      bounds = TrailblazeNode.Bounds(10, 60, 100, 100),
    )
    val button3 = node(
      detail = DriverNodeDetail.AndroidAccessibility(text = "Third"),
      bounds = TrailblazeNode.Bounds(10, 110, 100, 150),
    )
    val root = node(
      bounds = TrailblazeNode.Bounds(0, 0, 200, 200),
      children = listOf(button1, button2, button3),
    )

    // Tap second button
    val result = TrailblazeNodeSelectorGenerator.resolveFromTap(root, 55, 80)
    assertNotNull(result)
    assertEquals(button2.nodeId, result.targetNode.nodeId)
    assertTrue(result.roundTripValid)

    // The selector should uniquely identify button2
    val resolveResult = TrailblazeNodeSelectorResolver.resolve(root, result.selector)
    assertTrue(resolveResult is TrailblazeNodeSelectorResolver.ResolveResult.SingleMatch)
    assertEquals(button2.nodeId, resolveResult.node.nodeId)
  }

  // ======================================================================
  // resolveFromTap: identical nodes (index fallback)
  // ======================================================================

  @Test
  fun `resolveFromTap with identical nodes uses index and round trips`() {
    nextId = 1L
    val items = (0..2).map { i ->
      node(
        detail = DriverNodeDetail.AndroidAccessibility(className = "android.view.View"),
        bounds = TrailblazeNode.Bounds(0, i * 60, 100, i * 60 + 50),
      )
    }
    val root = node(
      bounds = TrailblazeNode.Bounds(0, 0, 100, 200),
      children = items,
    )

    // Tap middle item (center at 50, 85)
    val result = TrailblazeNodeSelectorGenerator.resolveFromTap(root, 50, 85)
    assertNotNull(result)
    assertEquals(items[1].nodeId, result.targetNode.nodeId)

    // Verify selector resolves back
    val resolveResult = TrailblazeNodeSelectorResolver.resolve(root, result.selector)
    assertTrue(resolveResult is TrailblazeNodeSelectorResolver.ResolveResult.SingleMatch)
    assertEquals(items[1].nodeId, resolveResult.node.nodeId)
  }

  // ======================================================================
  // resolveFromTap: Compose driver variant
  // ======================================================================

  @Test
  fun `resolveFromTap works with Compose nodes`() {
    nextId = 1L
    val target = TrailblazeNode(
      nodeId = nextId++,
      bounds = TrailblazeNode.Bounds(50, 100, 250, 160),
      driverDetail = DriverNodeDetail.Compose(testTag = "submit_btn", text = "Submit"),
      children = emptyList(),
    )
    val other = TrailblazeNode(
      nodeId = nextId++,
      bounds = TrailblazeNode.Bounds(50, 200, 250, 260),
      driverDetail = DriverNodeDetail.Compose(testTag = "cancel_btn", text = "Cancel"),
      children = emptyList(),
    )
    val root = TrailblazeNode(
      nodeId = nextId++,
      bounds = TrailblazeNode.Bounds(0, 0, 400, 400),
      driverDetail = DriverNodeDetail.Compose(),
      children = listOf(target, other),
    )

    val result = TrailblazeNodeSelectorGenerator.resolveFromTap(root, 150, 130)
    assertNotNull(result)
    assertEquals(target.nodeId, result.targetNode.nodeId)
    assertTrue(result.roundTripValid)
  }
}
