package xyz.block.trailblaze.android.accessibility

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Pure-function coverage of [resolveDoubledInputCorrection] — the gate that decides whether a
 * post-`inputText` field is rewritten to undo duplicated input.
 *
 * The regression this pins: the `ACTION_SET_TEXT` fast path can land AFTER its verify window on a
 * busy emulator, so the keystroke-synthesis fallback enters a second copy of the input (a search
 * box reading "BagelBagel" filters the item list to nothing and fails the following tap). The
 * correction may fire ONLY on the exact expected-plus-whole-extra-copies shape — anything else
 * (already-correct content, masked readback, app-side transformations) must be left untouched.
 * The live `AccessibilityNodeInfo` read + corrective dispatch stay an integration concern; this
 * test pins only the decision.
 */
class ResolveDoubledInputCorrectionTest {

  @Test
  fun `exactly doubled input on an empty baseline is corrected`() {
    assertEquals(
      "Bagel",
      resolveDoubledInputCorrection(baselineText = "", text = "Bagel", currentText = "BagelBagel"),
    )
  }

  @Test
  fun `doubled input appended to existing content is corrected`() {
    assertEquals(
      "8:00 pm",
      resolveDoubledInputCorrection(baselineText = "8:00 ", text = "pm", currentText = "8:00 pmpm"),
    )
  }

  @Test
  fun `a retried keystroke burst on top of a landed set-text reads as three copies and is corrected`() {
    assertEquals(
      "Bagel",
      resolveDoubledInputCorrection(baselineText = "", text = "Bagel", currentText = "BagelBagelBagel"),
    )
  }

  @Test
  fun `the expected value is not a duplication`() {
    assertNull(resolveDoubledInputCorrection(baselineText = "", text = "Bagel", currentText = "Bagel"))
  }

  @Test
  fun `masked readback never matches so a password field is left untouched`() {
    assertNull(resolveDoubledInputCorrection(baselineText = "", text = "hunter2", currentText = "•••••••"))
  }

  @Test
  fun `app-transformed content is not rewritten`() {
    // An auto-formatting field (e.g. a card number gaining spaces) is longer than expected but
    // not whole extra copies of the input.
    assertNull(resolveDoubledInputCorrection(baselineText = "", text = "4242424242424242", currentText = "4242 4242 4242 4242"))
  }

  @Test
  fun `a partial extra copy is not a duplication`() {
    assertNull(resolveDoubledInputCorrection(baselineText = "", text = "Bagel", currentText = "BagelBag"))
  }

  @Test
  fun `extra content of the right length but wrong text is not a duplication`() {
    // The app appended something else entirely (same length as the input) — do not touch it.
    assertNull(resolveDoubledInputCorrection(baselineText = "", text = "ab", currentText = "abxy"))
  }

  @Test
  fun `content not starting with the expected value is not rewritten`() {
    // The field was changed by something other than our two dispatches — do not touch it.
    assertNull(resolveDoubledInputCorrection(baselineText = "old", text = "new", currentText = "newnew"))
  }

  @Test
  fun `a changed prefix is not rewritten even when the tail looks duplicated`() {
    // The baseline itself was edited out from under us; the doubled-looking tail must not
    // trigger a rewrite that would resurrect the stale baseline.
    assertNull(resolveDoubledInputCorrection(baselineText = "8:00 ", text = "pm", currentText = "9:00 pmpm"))
  }

  @Test
  fun `empty input never corrects`() {
    assertNull(resolveDoubledInputCorrection(baselineText = "abc", text = "", currentText = "abc"))
  }

  @Test
  fun `single-character input duplicated many times is corrected to one copy`() {
    assertEquals(
      "5",
      resolveDoubledInputCorrection(baselineText = "", text = "5", currentText = "555"),
    )
  }
}
