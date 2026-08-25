package xyz.block.trailblaze.android.accessibility

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import maestro.orchestra.Command
import maestro.orchestra.InputTextCommand
import xyz.block.trailblaze.api.DriverNodeMatch
import xyz.block.trailblaze.api.TrailblazeNodeSelector

/**
 * Tests [MaestroCommandConverter.convert] handling of text-input commands.
 *
 * [AccessibilityAction.InputText] can now name the field to type into, but a Maestro
 * `inputText:` carries no selector — so these pin that the Maestro lowering keeps producing the
 * focused-field shape, which is what every existing trail replays.
 */
class MaestroCommandConverterInputTextTest {

  /** Unwraps a supported command's conversion, failing the test if it reads as unsupported. */
  private fun convert(command: Command): List<AccessibilityAction> =
    assertNotNull(MaestroCommandConverter.convert(command))

  @Test
  fun `converts InputTextCommand to a focused-field InputText action`() {
    val actions = convert(InputTextCommand(text = "hello"))

    val action = assertIs<AccessibilityAction.InputText>(actions.single())
    assertEquals("hello", action.text)
    assertNull(
      action.nodeSelector,
      "A Maestro inputText carries no selector, so it must keep typing into the focused field.",
    )
  }

  @Test
  fun `only an InputText that names a field mentions a target in its description`() {
    // The description is the action-log line an oncall reads to tell "typed into the field I
    // named" apart from "typed into whatever was focused", so the two shapes have to be
    // distinguishable from it. Asserted by substring, not exact wording — the sentence itself
    // is a diagnostic, not a contract.
    val selector = TrailblazeNodeSelector.withMatch(
      DriverNodeMatch.AndroidAccessibility(resourceIdRegex = "com\\.example:id/password"),
    )

    val focusedField = AccessibilityAction.InputText(text = "hunter2").description
    val namedField =
      AccessibilityAction.InputText(text = "hunter2", nodeSelector = selector).description

    assertFalse(
      selector.description() in focusedField,
      "A focused-field inputText has no target to name: $focusedField",
    )
    assertTrue(
      selector.description() in namedField,
      "A selector-bearing inputText must name the field it will focus: $namedField",
    )
  }
}
