package xyz.block.trailblaze.mcp.agent

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit tests for [KoogProgressTracker] — the loop/stuck detector that restores the agent's
 * awareness of its own repetition (which the prune/compress machinery otherwise hides).
 */
class KoogProgressTrackerTest {

  @Test
  fun `no nudge before the threshold is reached`() {
    val tracker = KoogProgressTracker(repeatThreshold = 3)
    assertNull(tracker.observe("Tap(selector=Login)"), "1st identical call should not nudge")
    assertNull(tracker.observe("Tap(selector=Login)"), "2nd identical call should not nudge")
  }

  @Test
  fun `nudge fires once the same signature repeats threshold times`() {
    val tracker = KoogProgressTracker(repeatThreshold = 3)
    tracker.observe("Tap(selector=Login)")
    tracker.observe("Tap(selector=Login)")
    val nudge = tracker.observe("Tap(selector=Login)")
    assertNotNull(nudge, "3rd identical call should nudge at threshold 3")
    assertTrue(nudge.contains("Loop detected"), "nudge should explain the loop: $nudge")
    assertTrue(nudge.contains("objectiveStatus"), "nudge should offer the FAILED escape hatch: $nudge")
  }

  @Test
  fun `a different action resets the streak`() {
    val tracker = KoogProgressTracker(repeatThreshold = 3)
    tracker.observe("Tap(selector=Login)")
    tracker.observe("Tap(selector=Login)")
    assertNull(tracker.observe("InputText(text=hello)"), "different action resets the streak")
    assertNull(tracker.observe("InputText(text=hello)"), "only 2 of the new action so far")
    assertNotNull(tracker.observe("InputText(text=hello)"), "3rd of the new action nudges")
  }

  @Test
  fun `nudge keeps firing while stuck and reports the growing count`() {
    val tracker = KoogProgressTracker(repeatThreshold = 2)
    assertNull(tracker.observe("Swipe(dir=up)"))
    assertTrue(tracker.observe("Swipe(dir=up)")!!.contains("2 "), "should report count 2")
    assertTrue(tracker.observe("Swipe(dir=up)")!!.contains("3 "), "should report count 3")
  }

  @Test
  fun `threshold of zero or less disables detection`() {
    val tracker = KoogProgressTracker(repeatThreshold = 0)
    repeat(10) { assertNull(tracker.observe("Tap(selector=Login)"), "detection disabled at threshold 0") }
  }

  @Test
  fun `default threshold is three`() {
    assertEquals(3, KoogProgressTracker.DEFAULT_REPEAT_THRESHOLD, "default threshold")
    val tracker = KoogProgressTracker() // uses the default
    assertNull(tracker.observe("a"))
    assertNull(tracker.observe("a"))
    assertNotNull(tracker.observe("a"), "3rd identical call nudges at the default threshold")
  }
}
