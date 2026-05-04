package xyz.block.trailblaze.android.accessibility

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import maestro.orchestra.CopyTextFromCommand
import maestro.orchestra.ElementSelector
import maestro.orchestra.PasteTextCommand
import maestro.orchestra.SetClipboardCommand
import xyz.block.trailblaze.api.DriverNodeMatch

/**
 * Tests [MaestroCommandConverter.convert] handling of clipboard commands:
 * [SetClipboardCommand], [PasteTextCommand], and [CopyTextFromCommand].
 */
class MaestroCommandConverterClipboardTest {

  @Test
  fun `converts SetClipboardCommand to SetClipboard action`() {
    val command = SetClipboardCommand(text = "Hello, World!")

    val actions = MaestroCommandConverter.convert(command)

    assertEquals(1, actions.size)
    val action = assertIs<AccessibilityAction.SetClipboard>(actions.single())
    assertEquals("Hello, World!", action.text)
  }

  @Test
  fun `converts PasteTextCommand to PasteText action`() {
    val command = PasteTextCommand()

    val actions = MaestroCommandConverter.convert(command)

    assertEquals(1, actions.size)
    assertIs<AccessibilityAction.PasteText>(actions.single())
  }

  @Test
  fun `converts CopyTextFromCommand to CopyTextFrom action`() {
    val command = CopyTextFromCommand(
      selector = ElementSelector(textRegex = "Account Balance"),
    )

    val actions = MaestroCommandConverter.convert(command)

    assertEquals(1, actions.size)
    val action = assertIs<AccessibilityAction.CopyTextFrom>(actions.single())
    val match = assertIs<DriverNodeMatch.AndroidAccessibility>(action.nodeSelector.driverMatch)
    assertEquals("Account Balance", match.textRegex)
  }

  @Test
  fun `converts CopyTextFromCommand with idRegex and state flags`() {
    val command = CopyTextFromCommand(
      selector = ElementSelector(
        idRegex = "com\\.example:id/balance_text",
        enabled = true,
      ),
    )

    val actions = MaestroCommandConverter.convert(command)

    assertEquals(1, actions.size)
    val action = assertIs<AccessibilityAction.CopyTextFrom>(actions.single())
    val match = assertIs<DriverNodeMatch.AndroidAccessibility>(action.nodeSelector.driverMatch)
    assertEquals("com\\.example:id/balance_text", match.resourceIdRegex)
    assertEquals(true, match.isEnabled)
  }

  @Test
  fun `converts CopyTextFromCommand with spatial relationship`() {
    val command = CopyTextFromCommand(
      selector = ElementSelector(
        textRegex = "\\$\\d+\\.\\d{2}",
        below = ElementSelector(textRegex = "Total"),
      ),
    )

    val actions = MaestroCommandConverter.convert(command)

    assertEquals(1, actions.size)
    val action = assertIs<AccessibilityAction.CopyTextFrom>(actions.single())
    val belowMatch =
      assertIs<DriverNodeMatch.AndroidAccessibility>(action.nodeSelector.below!!.driverMatch)
    assertEquals("Total", belowMatch.textRegex)
  }

  @Test
  fun `convertAll handles mixed clipboard commands`() {
    val commands = listOf(
      SetClipboardCommand(text = "test"),
      PasteTextCommand(),
      CopyTextFromCommand(selector = ElementSelector(textRegex = "Copy me")),
    )

    val actions = MaestroCommandConverter.convertAll(commands)

    assertEquals(3, actions.size)
    assertIs<AccessibilityAction.SetClipboard>(actions[0])
    assertIs<AccessibilityAction.PasteText>(actions[1])
    assertIs<AccessibilityAction.CopyTextFrom>(actions[2])
  }
}
