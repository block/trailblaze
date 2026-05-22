package xyz.block.trailblaze.host.devices

import org.junit.Test
import xyz.block.trailblaze.devices.WebInstanceIds
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Coverage for [WebBrowserManager]'s book-keeping seams that don't involve
 * launching a real Chromium:
 *
 *  - [WebBrowserManager.MAX_NAMED_SLOTS] enforcement on named instances.
 *  - Reserved IDs ([WebInstanceIds.PLAYWRIGHT_NATIVE] / [WebInstanceIds.PLAYWRIGHT_ELECTRON])
 *    never count toward the cap.
 *  - [WebBrowserManager.setViewportSpec] / [WebBrowserManager.getViewportSpec]
 *    round-trip and cap-aware provisioning.
 *
 * Launch / close paths are exercised by `BasePlaywrightNativeTest` + the various
 * Playwright-driven `PlaywrightNativeEvalTests`; this layer is intentionally
 * scoped to the pure-bookkeeping API so it runs in milliseconds.
 */
class WebBrowserManagerTest {

  @Test
  fun `provisioning up to MAX_NAMED_SLOTS distinct named ids succeeds`() {
    val manager = WebBrowserManager()
    repeat(WebBrowserManager.MAX_NAMED_SLOTS) { i ->
      // setViewportSpec is the cheapest call that goes through slotFor() — it just
      // records the spec on the slot and returns. No browser is launched.
      manager.setViewportSpec("named-$i", "iPhone 14")
      assertEquals("iPhone 14", manager.getViewportSpec("named-$i"))
    }
  }

  @Test
  fun `MAX_NAMED_SLOTS + 1 distinct named ids throws with a message containing the cap`() {
    val manager = WebBrowserManager()
    repeat(WebBrowserManager.MAX_NAMED_SLOTS) { i ->
      manager.setViewportSpec("named-$i", null)
    }
    val error = assertFailsWith<IllegalStateException> {
      manager.setViewportSpec("named-${WebBrowserManager.MAX_NAMED_SLOTS}", null)
    }
    val message = error.message.orEmpty()
    assertTrue(
      message.contains(WebBrowserManager.MAX_NAMED_SLOTS.toString()),
      "error must surface the cap value so the user knows what to compare against: '$message'",
    )
    assertTrue(
      message.contains("named-${WebBrowserManager.MAX_NAMED_SLOTS}"),
      "error must include the offending instance id: '$message'",
    )
  }

  @Test
  fun `reserved ids never count toward the cap and always succeed`() {
    val manager = WebBrowserManager()
    // Fill the cap with named instances.
    repeat(WebBrowserManager.MAX_NAMED_SLOTS) { i ->
      manager.setViewportSpec("named-$i", null)
    }
    // Reserved ids must still resolve to slots — provisioning the singleton or
    // electron slot after the cap is hit is the supported way to keep the
    // desktop UI and the recording tab usable in pathological multi-slot setups.
    manager.setViewportSpec(WebInstanceIds.PLAYWRIGHT_NATIVE, "iPhone 14")
    manager.setViewportSpec(WebInstanceIds.PLAYWRIGHT_ELECTRON, "Pixel 7")
    assertEquals("iPhone 14", manager.getViewportSpec(WebInstanceIds.PLAYWRIGHT_NATIVE))
    assertEquals("Pixel 7", manager.getViewportSpec(WebInstanceIds.PLAYWRIGHT_ELECTRON))
  }

  @Test
  fun `setViewportSpec then getViewportSpec round-trips for both forms`() {
    val manager = WebBrowserManager()

    manager.setViewportSpec("foo", "iPhone 14")
    assertEquals("iPhone 14", manager.getViewportSpec("foo"))

    // Updating overwrites — same instance reused.
    manager.setViewportSpec("foo", "375x812")
    assertEquals("375x812", manager.getViewportSpec("foo"))

    // null clears the recorded spec.
    manager.setViewportSpec("foo", null)
    assertNull(manager.getViewportSpec("foo"))
  }

  @Test
  fun `getViewportSpec on an unknown id returns null without provisioning`() {
    val manager = WebBrowserManager()
    assertNull(manager.getViewportSpec("never-touched"))
    // No slot was created, so the cap still allows MAX_NAMED_SLOTS more.
    repeat(WebBrowserManager.MAX_NAMED_SLOTS) { i ->
      manager.setViewportSpec("named-$i", null)
    }
  }
}
