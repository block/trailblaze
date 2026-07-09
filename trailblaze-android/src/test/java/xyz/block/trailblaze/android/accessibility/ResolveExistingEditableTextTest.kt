package xyz.block.trailblaze.android.accessibility

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pure-function coverage of [resolveExistingEditableText] — the gate that decides what the
 * additive `inputText` path treats as the field's existing content.
 *
 * The regression this pins: `AccessibilityNodeInfo.getText()` returns the hint string itself for
 * an empty field displaying its placeholder (with `isShowingHintText()` true). The append path
 * must read that as empty, not prepend the placeholder to the input. The live
 * `AccessibilityNodeInfo` read + `ACTION_SET_TEXT` dispatch stay an integration concern; this
 * test pins only the resolve decision.
 */
class ResolveExistingEditableTextTest {

  @Test
  fun `hint-showing field reads as empty even though getText returns the hint`() {
    assertEquals(
      "",
      resolveExistingEditableText(rawText = "Enter amount", isShowingHintText = true),
    )
  }

  @Test
  fun `real content is preserved so input appends onto it`() {
    assertEquals(
      "1234",
      resolveExistingEditableText(rawText = "1234", isShowingHintText = false),
    )
  }

  @Test
  fun `null text reads as empty`() {
    assertEquals(
      "",
      resolveExistingEditableText(rawText = null, isShowingHintText = false),
    )
  }

  @Test
  fun `empty text reads as empty`() {
    assertEquals(
      "",
      resolveExistingEditableText(rawText = "", isShowingHintText = false),
    )
  }

  @Test
  fun `hint flag wins even when text is non-empty and would otherwise be appended`() {
    // Composing the full append shows the observable fix: hint-showing field yields just the input.
    val existing = resolveExistingEditableText(rawText = "Search products", isShowingHintText = true)
    assertEquals("shoes", existing + "shoes")
  }
}
