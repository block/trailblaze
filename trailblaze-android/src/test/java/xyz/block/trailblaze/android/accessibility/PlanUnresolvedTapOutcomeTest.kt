package xyz.block.trailblaze.android.accessibility

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import xyz.block.trailblaze.api.DriverNodeMatch
import xyz.block.trailblaze.api.TapDispatchRoute
import xyz.block.trailblaze.api.TrailblazeNodeSelector

/**
 * Contract for what happens to a tap whose selector never matched before its timeout expired —
 * the three exits of [planUnresolvedTapOutcome].
 *
 * The case that matters: a tap delivered at record-time coordinates reports success, so the route
 * it carries is the only thing distinguishing it from a tap whose selector resolved.
 */
class PlanUnresolvedTapOutcomeTest {

  private val selector = TrailblazeNodeSelector(
    androidAccessibility = DriverNodeMatch.AndroidAccessibility(textRegex = "Checkout"),
  )

  @Test
  fun `recorded coordinates are dispatched and marked as a selector miss`() {
    val outcome = planUnresolvedTapOutcome(
      AccessibilityAction.TapOnElement(
        nodeSelector = selector,
        fallbackX = 540,
        fallbackY = 1790,
      ),
    )

    val fallback = assertIs<UnresolvedTapOutcome.TapRecordedCoordinates>(outcome)
    assertEquals(540, fallback.x)
    assertEquals(1790, fallback.y)
    assertEquals(
      TapDispatchRoute.RECORDED_COORDINATES_AFTER_SELECTOR_MISS,
      fallback.route,
      "This tap is about to report success without resolving anything — the route is the only " +
        "record of that.",
    )
  }

  @Test
  fun `a long press at recorded coordinates is marked the same way`() {
    val outcome = planUnresolvedTapOutcome(
      AccessibilityAction.TapOnElement(
        nodeSelector = selector,
        longPress = true,
        fallbackX = 120,
        fallbackY = 640,
      ),
    )

    val fallback = assertIs<UnresolvedTapOutcome.TapRecordedCoordinates>(outcome)
    assertEquals(
      TapDispatchRoute.RECORDED_COORDINATES_AFTER_SELECTOR_MISS,
      fallback.route,
    )
  }

  @Test
  fun `an optional tap with no recorded coordinates is skipped`() {
    val outcome = planUnresolvedTapOutcome(
      AccessibilityAction.TapOnElement(nodeSelector = selector, optional = true),
    )

    assertEquals(UnresolvedTapOutcome.SkipOptional, outcome)
  }

  @Test
  fun `recorded coordinates win over optional`() {
    // An optional step that carries a recorded point still taps it: the tap is what the recording
    // asked for, and marking it beats silently skipping.
    val outcome = planUnresolvedTapOutcome(
      AccessibilityAction.TapOnElement(
        nodeSelector = selector,
        optional = true,
        fallbackX = 10,
        fallbackY = 20,
      ),
    )

    assertIs<UnresolvedTapOutcome.TapRecordedCoordinates>(outcome)
  }

  @Test
  fun `a tap with neither recorded coordinates nor optional fails`() {
    // The state every Android accessibility tap is in today, since no caller sets fallback
    // coordinates: a selector that resolves nothing fails the step rather than tapping blind.
    val outcome = planUnresolvedTapOutcome(
      AccessibilityAction.TapOnElement(nodeSelector = selector),
    )

    assertEquals(UnresolvedTapOutcome.Fail, outcome)
  }

  @Test
  fun `a half-specified recorded point is not treated as recorded coordinates`() {
    val outcome = planUnresolvedTapOutcome(
      AccessibilityAction.TapOnElement(nodeSelector = selector, fallbackX = 540),
    )

    assertEquals(UnresolvedTapOutcome.Fail, outcome)
  }
}
