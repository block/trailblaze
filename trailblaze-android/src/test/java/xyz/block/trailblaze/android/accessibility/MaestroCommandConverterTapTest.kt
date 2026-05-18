package xyz.block.trailblaze.android.accessibility

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import maestro.orchestra.ElementSelector
import maestro.orchestra.TapOnElementCommand

/**
 * Tests [MaestroCommandConverter] handling of [TapOnElementCommand], with particular focus on
 * the `optional` flag — silently dropped in a prior version of `convertTapOnElement`, which
 * caused legacy recordings that gate on transient runtime permission dialogs (e.g. an "Allow
 * BLUETOOTH_CONNECT" prompt that only appears on cold launch) to fail on warm devices where
 * the dialog wasn't present.
 */
class MaestroCommandConverterTapTest {

  @Test
  fun `convertTapOnElement preserves optional=true`() {
    val command = TapOnElementCommand(
      selector = ElementSelector(textRegex = "^ALLOW$"),
      optional = true,
    )

    val actions = MaestroCommandConverter.convert(command)

    assertEquals(1, actions.size)
    val tap = assertIs<AccessibilityAction.TapOnElement>(actions.single())
    assertTrue(tap.optional, "optional=true on the Maestro command must propagate to the action")
  }

  @Test
  fun `convertTapOnElement defaults optional to false when unset`() {
    val command = TapOnElementCommand(
      selector = ElementSelector(textRegex = "Continue"),
    )

    val actions = MaestroCommandConverter.convert(command)

    assertEquals(1, actions.size)
    val tap = assertIs<AccessibilityAction.TapOnElement>(actions.single())
    assertFalse(tap.optional, "optional must default to false when not specified")
  }
}
