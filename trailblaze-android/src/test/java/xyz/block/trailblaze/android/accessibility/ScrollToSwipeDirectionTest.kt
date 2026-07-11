package xyz.block.trailblaze.android.accessibility

import kotlin.test.Test
import kotlin.test.assertEquals
import maestro.ScrollDirection
import maestro.SwipeDirection
import maestro.toSwipeDirection

/**
 * Locks [scrollToSwipeDirection] to Maestro's own `ScrollDirection.toSwipeDirection()` mapping —
 * the contract the instrumentation driver (Orchestra) already honors. Scrolling DOWN (revealing
 * content below the viewport) means the finger swipes UP; before this mapping existed, the
 * accessibility driver's scrollUntilVisible swiped in the scroll direction itself and moved every
 * list the wrong way.
 */
class ScrollToSwipeDirectionTest {

  /** The mapping itself, stated literally so the contract is legible without reading Maestro. */
  @Test
  fun `scrolling reveals content by swiping the opposite way`() {
    assertEquals(AccessibilityAction.Direction.UP, scrollToSwipeDirection(AccessibilityAction.Direction.DOWN))
    assertEquals(AccessibilityAction.Direction.DOWN, scrollToSwipeDirection(AccessibilityAction.Direction.UP))
    assertEquals(AccessibilityAction.Direction.RIGHT, scrollToSwipeDirection(AccessibilityAction.Direction.LEFT))
    assertEquals(AccessibilityAction.Direction.LEFT, scrollToSwipeDirection(AccessibilityAction.Direction.RIGHT))
  }

  @Test
  fun `matches Maestro's scroll-to-swipe inversion for every direction`() {
    ScrollDirection.entries.forEach { scrollDirection ->
      val expected = scrollDirection.toSwipeDirection().toAccessibilityDirection()
      val actual = scrollToSwipeDirection(scrollDirection.toAccessibilityDirection())
      assertEquals(
        expected,
        actual,
        "scroll $scrollDirection should swipe ${expected.name} (Maestro parity)",
      )
    }
  }

  /** Same name-wise mapping [MaestroCommandConverter.convertScrollDirection] applies. */
  private fun ScrollDirection.toAccessibilityDirection(): AccessibilityAction.Direction =
    AccessibilityAction.Direction.valueOf(name)

  private fun SwipeDirection.toAccessibilityDirection(): AccessibilityAction.Direction =
    AccessibilityAction.Direction.valueOf(name)
}
