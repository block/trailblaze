package xyz.block.trailblaze.android.accessibility

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import xyz.block.trailblaze.api.DriverNodeDetail
import xyz.block.trailblaze.api.TrailblazeNode
import xyz.block.trailblaze.model.TapRouteOverride

/**
 * Pure-function coverage of [planTextLinkClickRoute] — the gate that routes a tap on a
 * synthetic in-text-link child (see `AccessibilityNodeExt`'s link-span capture) through
 * [TrailblazeAccessibilityService.clickTextLinkSpan] instead of the ACTION_CLICK/gesture
 * paths. The span-click dispatch itself needs a live accessibility tree, so it stays an
 * integration concern; this pins only the routing decision.
 */
class PlanTextLinkClickRouteTest {

  @Test
  fun `routes a text-link node to the span-click path with its link text`() {
    val linkText = planTextLinkClickRoute(
      node = linkNode(text = "Terms of Service"),
      longPress = false,
    )
    assertEquals("Terms of Service", linkText)
  }

  @Test
  fun `long-press never routes through the span-click path`() {
    assertNull(planTextLinkClickRoute(node = linkNode(text = "Terms of Service"), longPress = true))
  }

  @Test
  fun `an explicit tap-route pin disables the span-click path`() {
    // A pinned route means the author took manual control of routing for this step; the
    // span heuristic must defer exactly like the ACTION_CLICK heuristics do.
    for (pin in TapRouteOverride.entries) {
      assertNull(
        planTextLinkClickRoute(node = linkNode(text = "Terms of Service"), longPress = false, tapRoute = pin),
        "pin $pin must disable the span route",
      )
    }
  }

  @Test
  fun `non-link nodes use normal tap routing`() {
    val node = TrailblazeNode(
      bounds = TrailblazeNode.Bounds(0, 0, 100, 40),
      driverDetail = DriverNodeDetail.AndroidAccessibility(
        className = "android.widget.Button",
        text = "Submit",
        isClickable = true,
      ),
    )
    assertNull(planTextLinkClickRoute(node = node, longPress = false))
  }

  @Test
  fun `text-link node without usable text falls back to normal routing`() {
    // The span-click lookup is keyed on the link's substring; without it there is nothing
    // to match against the live tree, so the gesture path (span bounds center) is the
    // only option.
    assertNull(planTextLinkClickRoute(node = linkNode(text = null), longPress = false))
    assertNull(planTextLinkClickRoute(node = linkNode(text = "  "), longPress = false))
  }

  @Test
  fun `a link that is not visible to the user falls back to normal routing`() {
    // The remote span transport would activate a link hidden behind a modal or scrim;
    // a real gesture there hits the overlay instead, and the ACTION_CLICK route already
    // declines invisible nodes for the same reason.
    assertNull(
      planTextLinkClickRoute(
        node = linkNode(text = "Terms of Service", isVisibleToUser = false),
        longPress = false,
      ),
    )
  }

  @Test
  fun `a disabled link falls back to normal routing`() {
    // A physical tap on a span inside a disabled TextView is a no-op; the span transport
    // must not succeed where the real gesture could not.
    assertNull(
      planTextLinkClickRoute(
        node = linkNode(text = "Terms of Service", isEnabled = false),
        longPress = false,
      ),
    )
  }

  private fun linkNode(
    text: String?,
    isEnabled: Boolean = true,
    isVisibleToUser: Boolean = true,
  ): TrailblazeNode = TrailblazeNode(
    bounds = TrailblazeNode.Bounds(207, 689, 308, 705),
    driverDetail = DriverNodeDetail.AndroidAccessibility(
      className = "android.widget.TextView",
      text = text,
      isClickable = true,
      isTextLink = true,
      isEnabled = isEnabled,
      isVisibleToUser = isVisibleToUser,
    ),
  )
}
