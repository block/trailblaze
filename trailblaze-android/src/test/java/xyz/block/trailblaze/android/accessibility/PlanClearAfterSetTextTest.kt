package xyz.block.trailblaze.android.accessibility

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Coverage of [planClearAfterSetText], which decides whether `inputText`'s `clearFirst` emptied the
 * focused field. The case it exists for: a WebView password field reads back empty whatever it
 * holds, so its empty readback proves nothing, and a refused clear there must fail rather than let
 * the new text land after the old secret.
 */
class PlanClearAfterSetTextTest {

  @Test
  fun `a WebView password field that refuses the clear is not reported as cleared`() {
    assertEquals(
      ClearPlan.UNVERIFIABLE,
      planClearAfterSetText(accepted = false, readsEmpty = true, unreadable = true),
    )
  }

  @Test
  fun `a WebView password field that accepts the clear is cleared, though it reads empty either way`() {
    assertEquals(
      ClearPlan.CLEARED,
      planClearAfterSetText(accepted = true, readsEmpty = true, unreadable = true),
    )
  }

  @Test
  fun `a readable field that reads empty is cleared, even when it refused the set`() {
    // A native field already empty may refuse a set that changes nothing; its readback is proof.
    assertEquals(
      ClearPlan.CLEARED,
      planClearAfterSetText(accepted = false, readsEmpty = true, unreadable = false),
    )
  }

  @Test
  fun `a readable field still holding text is emptied key by key`() {
    // Masked native fields accept the set and ignore it; others refuse it outright.
    assertEquals(
      ClearPlan.DELETE_KEYS,
      planClearAfterSetText(accepted = true, readsEmpty = false, unreadable = false),
    )
    assertEquals(
      ClearPlan.DELETE_KEYS,
      planClearAfterSetText(accepted = false, readsEmpty = false, unreadable = false),
    )
  }
}
