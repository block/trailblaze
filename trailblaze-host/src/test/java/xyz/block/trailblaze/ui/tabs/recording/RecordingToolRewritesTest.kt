package xyz.block.trailblaze.ui.tabs.recording

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import xyz.block.trailblaze.api.DriverNodeMatch
import xyz.block.trailblaze.api.TrailblazeNodeSelector
import xyz.block.trailblaze.model.TapRouteOverride
import xyz.block.trailblaze.toolcalls.commands.TapOnByElementSelector

/**
 * Pins what the recording UI's selector picker is allowed to change when an author re-targets a
 * recorded tap: the selector, and nothing else.
 *
 * The picker exists to answer "which element does this step tap", so every other decision already
 * recorded on the step has to survive it. `tapRoute` is the one that bites — a step pinned to a
 * dispatch route was pinned because it was measured to need it, and silently reverting that turns
 * a passing tap back into an absorbed one with nothing in the diff pointing at the cause.
 */
class RecordingToolRewritesTest {

  private fun selector(text: String) =
    TrailblazeNodeSelector(androidAccessibility = DriverNodeMatch.AndroidAccessibility(textRegex = text))

  @Test
  fun `re-targeting keeps the pinned route`() {
    val pinned = TapOnByElementSelector(
      reason = "Select the option row in the open dropdown sheet.",
      tapRoute = TapRouteOverride.ACTION_CLICK,
      nodeSelector = selector("All add-ons"),
    )

    val retargeted = pinned.retargetedAt(selector("Every add-on"))

    assertEquals(TapRouteOverride.ACTION_CLICK, retargeted.tapRoute)
    assertEquals(selector("Every add-on"), retargeted.nodeSelector)
  }

  /**
   * Negative control: the pin is carried, not manufactured. An unpinned tap has to come out
   * unpinned, otherwise the assertion above would pass just as happily against a rewrite that
   * hardcoded a route.
   */
  @Test
  fun `re-targeting an unpinned tap leaves it unpinned`() {
    val unpinned = TapOnByElementSelector(
      reason = "Open the category dropdown.",
      nodeSelector = selector("For your business"),
    )

    val retargeted = unpinned.retargetedAt(selector("Categories"))

    assertNull(retargeted.tapRoute)
    assertEquals(selector("Categories"), retargeted.nodeSelector)
  }

  @Test
  fun `re-targeting keeps the author's other recorded decisions`() {
    val original = TapOnByElementSelector(
      reason = "Long-press the row to open its context menu.",
      longPress = true,
      tapRoute = TapRouteOverride.GESTURE,
      nodeSelector = selector("Team Management"),
    )

    val retargeted = original.retargetedAt(selector("Payroll"))

    assertEquals(original.copy(nodeSelector = selector("Payroll")), retargeted)
    assertEquals("Long-press the row to open its context menu.", retargeted.reason)
    assertEquals(true, retargeted.longPress)
    assertEquals(TapRouteOverride.GESTURE, retargeted.tapRoute)
  }
}
