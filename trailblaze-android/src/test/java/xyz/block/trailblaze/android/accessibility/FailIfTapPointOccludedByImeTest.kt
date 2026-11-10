package xyz.block.trailblaze.android.accessibility

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Coverage for [AccessibilityDeviceManager.failIfTapPointOccludedByIme], the pre-tap check that a
 * resolved target is not under the soft keyboard. By default it retries a dismissal and resumes
 * the tap once the keyboard is gone. A step that set `hideKeyboardBefore: false` kept the keyboard
 * up because closing it moves the screen, so a covered target fails at once instead: dismissing it
 * then would tap at coordinates resolved before the move.
 *
 * The IME probe and the dismissal are injected, so this runs on a plain JVM.
 */
class FailIfTapPointOccludedByImeTest {

  private val manager = AccessibilityDeviceManager()

  /** A keyboard that covers the point until it is dismissed, and dismisses on the first try. */
  private class DismissibleKeyboard {
    var shown = true
    var dismissals = 0

    fun signal(@Suppress("UNUSED_PARAMETER") x: Int, @Suppress("UNUSED_PARAMETER") y: Int): String? =
      if (shown) "IME window covers the point" else null

    fun dismiss(): Boolean {
      dismissals++
      shown = false
      return true
    }
  }

  @Test
  fun `by default a covered target retries the dismissal and resumes the tap`() {
    val keyboard = DismissibleKeyboard()

    manager.failIfTapPointOccludedByIme(
      x = 10,
      y = 20,
      targetDescription = "Price",
      retryDismissal = true,
      occlusionSignal = keyboard::signal,
      dismiss = keyboard::dismiss,
    )

    assertEquals(1, keyboard.dismissals)
  }

  @Test
  fun `a step that keeps the keyboard up fails a covered target without dismissing it`() {
    val keyboard = DismissibleKeyboard()

    val thrown = assertFailsWith<IllegalStateException> {
      manager.failIfTapPointOccludedByIme(
        x = 10,
        y = 20,
        targetDescription = "Price",
        retryDismissal = false,
        occlusionSignal = keyboard::signal,
        dismiss = keyboard::dismiss,
      )
    }

    assertEquals(0, keyboard.dismissals, "the keyboard the step asked to keep up must stay up")
    assertTrue(thrown.message?.contains("'Price' at (10, 20)") == true, "${thrown.message}")
    assertTrue(thrown.message?.contains("hideKeyboardBefore=false") == true, "${thrown.message}")
  }

  @Test
  fun `a clear target is tapped either way`() {
    val keyboard = DismissibleKeyboard().apply { shown = false }

    for (retryDismissal in listOf(true, false)) {
      manager.failIfTapPointOccludedByIme(
        x = 10,
        y = 20,
        targetDescription = "Price",
        retryDismissal = retryDismissal,
        occlusionSignal = keyboard::signal,
        dismiss = keyboard::dismiss,
      )
    }

    assertEquals(0, keyboard.dismissals)
  }
}
