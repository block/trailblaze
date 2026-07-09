package xyz.block.trailblaze.android.accessibility

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Coverage for [AccessibilityDeviceManager.failIfGestureNotDispatched] — the check every raw
 * gesture dispatch (`tap`, `longPress`, `swipe`, `scroll`, and the ACTION_CLICK gesture-fallback)
 * funnels through. Before this check existed, a cancelled or timed-out `dispatchGesture()` call
 * was silently treated as a successful tap with zero device effect (see
 * `AccessibilityDeviceManagerDispatchTest` for the settle-runs-even-on-throw contract this relies
 * on downstream).
 *
 * Pure boolean-check-and-throw logic — no Android APIs involved — so this is testable on a plain
 * JVM without instrumentation, same rationale as [PlanActionClickRouteTest].
 */
class FailIfGestureNotDispatchedTest {

  private val manager = AccessibilityDeviceManager()

  @Test
  fun `does not throw when the gesture was dispatched`() {
    manager.failIfGestureNotDispatched(dispatched = true, description = "tap at (1, 2)")
  }

  @Test
  fun `throws when the gesture was cancelled or timed out`() {
    val thrown = assertFailsWith<IllegalStateException> {
      manager.failIfGestureNotDispatched(dispatched = false, description = "tap at (1, 2)")
    }
    assertTrue(
      thrown.message?.contains("tap at (1, 2)") == true,
      "error message must carry the caller-supplied description so a triager can tell which " +
        "dispatch call failed: ${thrown.message}",
    )
    assertTrue(
      thrown.message?.contains("dispatchGesture()") == true,
      "error message must name the underlying failure so it isn't confused with an unrelated " +
        "IllegalStateException elsewhere in the dispatch chain: ${thrown.message}",
    )
  }
}
