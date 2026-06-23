package xyz.block.trailblaze.android.accessibility

import kotlin.test.Test
import kotlin.test.assertEquals
import xyz.block.trailblaze.api.DriverNodeDetail
import xyz.block.trailblaze.api.TrailblazeNode

/**
 * Pure-function coverage of [pickPreferredMatch] — the tie-breaker that decides which node a
 * selector acts on when it matches more than one.
 *
 * The motivating case: merged-window capture surfaces the same label from a popup/dialog window
 * (the on-screen control) and the base application window (an off-screen, occluded node with
 * identical text). The resolver orders matches by `bounds.top`, so the off-screen node can sort
 * first; a blind `first()` would tap off-screen and silently no-op. [pickPreferredMatch] must
 * prefer the visible match while preserving `first()` behavior when no visibility signal exists.
 */
class PickPreferredMatchTest {

  @Test
  fun `prefers the visible match when an earlier match is not visible to user`() {
    val offScreen = node(TrailblazeNode.Bounds(0, 631, 1080, 967), androidA11y("Service charges", isVisibleToUser = false))
    val onScreen = node(TrailblazeNode.Bounds(512, 643, 764, 698), androidA11y("Service charges", isVisibleToUser = true))

    assertEquals(onScreen, pickPreferredMatch(listOf(offScreen, onScreen)))
  }

  @Test
  fun `keeps first when it is already visible`() {
    val first = node(TrailblazeNode.Bounds(0, 0, 100, 100), androidA11y("A", isVisibleToUser = true))
    val second = node(TrailblazeNode.Bounds(0, 200, 100, 300), androidA11y("A", isVisibleToUser = true))

    assertEquals(first, pickPreferredMatch(listOf(first, second)))
  }

  @Test
  fun `falls back to first when no match reports visible to user`() {
    val a = node(TrailblazeNode.Bounds(0, 0, 100, 100), androidA11y("A", isVisibleToUser = false))
    val b = node(TrailblazeNode.Bounds(0, 200, 100, 300), androidA11y("A", isVisibleToUser = false))

    assertEquals(a, pickPreferredMatch(listOf(a, b)))
  }

  // --- Test helpers ---

  private fun node(bounds: TrailblazeNode.Bounds, detail: DriverNodeDetail): TrailblazeNode =
    TrailblazeNode(bounds = bounds, driverDetail = detail)

  private fun androidA11y(
    text: String,
    isVisibleToUser: Boolean,
  ): DriverNodeDetail.AndroidAccessibility = DriverNodeDetail.AndroidAccessibility(
    className = "android.widget.TextView",
    text = text,
    isVisibleToUser = isVisibleToUser,
  )
}
