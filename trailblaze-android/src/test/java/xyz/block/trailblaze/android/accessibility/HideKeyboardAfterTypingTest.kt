package xyz.block.trailblaze.android.accessibility

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class HideKeyboardAfterTypingTest {

  @Test
  fun `a failed hide says the text was already typed and keeps the cause`() {
    val e = assertFailsWith<IllegalStateException> {
      hideKeyboardAfterTyping { error("hideKeyboard failed: GLOBAL_ACTION_BACK was rejected") }
    }
    assertEquals(
      "The text was typed, but closing the keyboard afterwards failed: " +
        "hideKeyboard failed: GLOBAL_ACTION_BACK was rejected",
      e.message,
    )
  }

  @Test
  fun `a successful hide runs once and throws nothing`() {
    var hides = 0
    hideKeyboardAfterTyping { hides++ }
    assertEquals(1, hides)
  }
}
