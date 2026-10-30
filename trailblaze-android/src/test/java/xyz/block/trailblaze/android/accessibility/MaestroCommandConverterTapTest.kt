package xyz.block.trailblaze.android.accessibility

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import maestro.TapRepeat
import maestro.orchestra.Command
import maestro.orchestra.ElementSelector
import maestro.orchestra.TapOnElementCommand
import maestro.orchestra.TapOnPointV2Command

/**
 * Tests [MaestroCommandConverter] handling of [TapOnElementCommand], with particular focus on
 * the `optional` flag — silently dropped in a prior version of `convertTapOnElement`, which
 * caused legacy recordings that gate on transient runtime permission dialogs (e.g. an "Allow
 * BLUETOOTH_CONNECT" prompt that only appears on cold launch) to fail on warm devices where
 * the dialog wasn't present.
 */
class MaestroCommandConverterTapTest {

  /** Unwraps a supported command's conversion, failing the test if it reads as unsupported. */
  private fun convert(command: Command): List<AccessibilityAction> =
    assertNotNull(MaestroCommandConverter.convert(command))

  @Test
  fun `convertTapOnElement preserves optional=true`() {
    val command = TapOnElementCommand(
      selector = ElementSelector(textRegex = "^ALLOW$"),
      optional = true,
    )

    val actions = convert(command)

    assertEquals(1, actions.size)
    val tap = assertIs<AccessibilityAction.TapOnElement>(actions.single())
    assertTrue(tap.optional, "optional=true on the Maestro command must propagate to the action")
  }

  @Test
  fun `convertTapOnElement defaults optional to false when unset`() {
    val command = TapOnElementCommand(
      selector = ElementSelector(textRegex = "Continue"),
    )

    val actions = convert(command)

    assertEquals(1, actions.size)
    val tap = assertIs<AccessibilityAction.TapOnElement>(actions.single())
    assertFalse(tap.optional, "optional must default to false when not specified")
  }

  @Test
  fun `a point tap with repeat 2 converts to a double-tap at that point`() {
    val tap = assertIs<AccessibilityAction.Tap>(
      convert(TapOnPointV2Command(point = "100,65", repeat = TapRepeat(2, 0))).single(),
    )

    assertEquals(AccessibilityAction.Tap(x = 100, y = 65, repeat = 2), tap)
  }

  @Test
  fun `a percent tap with repeat 2 converts to a double-tap at that percent`() {
    val tap = assertIs<AccessibilityAction.TapRelative>(
      convert(TapOnPointV2Command(point = "50%,26%", repeat = TapRepeat(2, 0))).single(),
    )

    assertEquals(AccessibilityAction.TapRelative(percentX = 50.0, percentY = 26.0, repeat = 2), tap)
  }

  @Test
  fun `a decimal percent tap keeps its fraction`() {
    assertEquals(
      AccessibilityAction.TapRelative(percentX = 50.5, percentY = 26.25),
      convert(TapOnPointV2Command(point = "50.5%,26.25%")).single(),
    )
  }

  @Test
  fun `a point tap without repeat stays a single tap`() {
    assertEquals(
      AccessibilityAction.Tap(x = 10, y = 20),
      convert(TapOnPointV2Command(point = "10,20")).single(),
    )
    assertEquals(
      AccessibilityAction.TapRelative(percentX = 50.0, percentY = 26.0),
      convert(TapOnPointV2Command(point = "50%,26%")).single(),
    )
  }
}
