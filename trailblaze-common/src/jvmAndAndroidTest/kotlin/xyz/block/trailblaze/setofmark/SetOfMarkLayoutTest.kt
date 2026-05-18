package xyz.block.trailblaze.setofmark

import xyz.block.trailblaze.viewhierarchy.ViewHierarchyFilter
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Regression guards on the geometry helpers shared between `HostCanvasSetOfMark` (AWT) and
 * `AndroidCanvasSetOfMark`. Failing here flags a future change that would silently shift
 * label placement on only one platform.
 */
class SetOfMarkLayoutTest {

  @Test
  fun `bottomRightLabelRect anchors the label box to the element's bottom-right corner`() {
    // Canonical case: 100×80 element, 40×20 label box. Label rect sits at the bottom-right,
    // its bottom-right corner coinciding with the element's bottom-right corner.
    val elementBounds = ViewHierarchyFilter.Bounds(x1 = 200, y1 = 100, x2 = 300, y2 = 180)
    val rect = SetOfMarkLayout.bottomRightLabelRect(
      elementBounds = elementBounds,
      boxWidth = 40,
      boxHeight = 20,
    )
    // Right edge = element's x2; bottom edge = element's y2.
    assertEquals(300, rect.x2)
    assertEquals(180, rect.y2)
    // Left/top derive from boxWidth/Height.
    assertEquals(260, rect.x1)
    assertEquals(160, rect.y1)
  }

  @Test
  fun `bottomRightLabelRect produces a box exactly boxWidth wide and boxHeight tall`() {
    // The label rect's width and height match the requested box dimensions exactly —
    // pinned so a future "extend by a few px for breathing room" tweak gets caught and
    // applied consistently across both canvases.
    val rect = SetOfMarkLayout.bottomRightLabelRect(
      elementBounds = ViewHierarchyFilter.Bounds(0, 0, 500, 500),
      boxWidth = 73,
      boxHeight = 29,
    )
    assertEquals(73, rect.x2 - rect.x1)
    assertEquals(29, rect.y2 - rect.y1)
  }

  @Test
  fun `bottomRightLabelRect does not clamp to canvas — caller's responsibility`() {
    // The helper intentionally doesn't clamp the rect to a canvas. Host's
    // `pickNonCollidingLabelRect` applies `clampToCanvas` after picking; Android relies
    // on upstream bitmap bounds. If a label box would extend negative-left/negative-top
    // because the element starts at or near the origin, the helper returns those negative
    // coordinates as-is — caller decides whether to clamp, clip, or ignore.
    val rect = SetOfMarkLayout.bottomRightLabelRect(
      elementBounds = ViewHierarchyFilter.Bounds(5, 10, 20, 25),
      boxWidth = 50, // larger than the element
      boxHeight = 30,
    )
    assertEquals(-30, rect.x1) // 20 - 50 = -30, NOT clamped to 0
    assertEquals(-5, rect.y1)  // 25 - 30 = -5,  NOT clamped to 0
    assertEquals(20, rect.x2)
    assertEquals(25, rect.y2)
  }
}
