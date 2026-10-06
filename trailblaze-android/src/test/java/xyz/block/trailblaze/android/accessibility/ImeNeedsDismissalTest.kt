package xyz.block.trailblaze.android.accessibility

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

class ImeNeedsDismissalTest {

  @Test
  fun `no IME window among enumerated windows means nothing to dismiss, whatever dumpsys says`() {
    // A hardware-keyboard emulator: dumpsys reports the IME shown, but it draws no window. A BACK
    // here would reach the app and close the screen the text was just typed into.
    assertFalse(imeNeedsDismissal(imeWindowOnScreen = false) { fail("dumpsys must not be consulted") })
  }

  @Test
  fun `an IME window on screen is dismissed without asking dumpsys`() {
    assertTrue(imeNeedsDismissal(imeWindowOnScreen = true) { fail("dumpsys must not be consulted") })
  }

  @Test
  fun `when windows cannot be enumerated, dumpsys decides`() {
    assertTrue(imeNeedsDismissal(imeWindowOnScreen = null) { true })
    assertFalse(imeNeedsDismissal(imeWindowOnScreen = null) { false })
  }

  @Test
  fun `an IME window that draws nothing counts as no keyboard, so dumpsys is not consulted`() {
    assertEquals(false, imeWindowOnScreen(ImeWindowLookup.Undrawn))
    assertFalse(
      imeNeedsDismissal(imeWindowOnScreen(ImeWindowLookup.Undrawn)) { fail("dumpsys must not be consulted") },
    )
  }

  @Test
  fun `only windows that cannot be enumerated defer to dumpsys`() {
    assertEquals(false, imeWindowOnScreen(ImeWindowLookup.Absent))
    assertNull(imeWindowOnScreen(ImeWindowLookup.Unavailable))
  }
}
