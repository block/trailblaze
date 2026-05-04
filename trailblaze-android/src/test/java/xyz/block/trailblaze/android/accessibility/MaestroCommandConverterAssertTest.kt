package xyz.block.trailblaze.android.accessibility

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import maestro.orchestra.AssertConditionCommand
import maestro.orchestra.Condition
import maestro.orchestra.ElementSelector
import xyz.block.trailblaze.api.DriverNodeMatch

/**
 * Tests [MaestroCommandConverter.convert] handling of [AssertConditionCommand].
 *
 * These commands arrive from:
 * - Trail file playback (`executeMaestroCommands` → `MaestroCommandConverter`)
 * - `MapsToMaestroCommands` tools (`AssertVisibleBySelectorTrailblazeTool`,
 *   `AssertNotVisibleWithTextTrailblazeTool`)
 *
 * Previously these were silently skipped as unsupported, producing a `Success()` no-op.
 */
class MaestroCommandConverterAssertTest {

  @Test
  fun `converts visible condition to AssertVisible action`() {
    val command = AssertConditionCommand(
      condition = Condition(
        visible = ElementSelector(textRegex = "Submit"),
      ),
    )

    val actions = MaestroCommandConverter.convert(command)

    assertEquals(1, actions.size)
    val action = assertIs<AccessibilityAction.AssertVisible>(actions.single())
    val match = assertIs<DriverNodeMatch.AndroidAccessibility>(action.nodeSelector.driverMatch)
    assertEquals("Submit", match.textRegex)
  }

  @Test
  fun `converts notVisible condition to AssertNotVisible action`() {
    val command = AssertConditionCommand(
      condition = Condition(
        notVisible = ElementSelector(textRegex = "Loading\\.\\.\\."),
      ),
    )

    val actions = MaestroCommandConverter.convert(command)

    assertEquals(1, actions.size)
    val action = assertIs<AccessibilityAction.AssertNotVisible>(actions.single())
    val match = assertIs<DriverNodeMatch.AndroidAccessibility>(action.nodeSelector.driverMatch)
    assertEquals("Loading\\.\\.\\.", match.textRegex)
  }

  @Test
  fun `converts visible condition with idRegex and state flags`() {
    val command = AssertConditionCommand(
      condition = Condition(
        visible = ElementSelector(
          idRegex = "com\\.example:id/btn_confirm",
          enabled = true,
          selected = false,
        ),
      ),
    )

    val actions = MaestroCommandConverter.convert(command)

    assertEquals(1, actions.size)
    val action = assertIs<AccessibilityAction.AssertVisible>(actions.single())
    val match = assertIs<DriverNodeMatch.AndroidAccessibility>(action.nodeSelector.driverMatch)
    assertEquals("com\\.example:id/btn_confirm", match.resourceIdRegex)
    assertEquals(true, match.isEnabled)
    assertEquals(false, match.isSelected)
  }

  @Test
  fun `converts notVisible condition with text and idRegex`() {
    val command = AssertConditionCommand(
      condition = Condition(
        notVisible = ElementSelector(
          textRegex = "Error",
          idRegex = "com\\.example:id/error_banner",
        ),
      ),
    )

    val actions = MaestroCommandConverter.convert(command)

    assertEquals(1, actions.size)
    val action = assertIs<AccessibilityAction.AssertNotVisible>(actions.single())
    val match = assertIs<DriverNodeMatch.AndroidAccessibility>(action.nodeSelector.driverMatch)
    assertEquals("Error", match.textRegex)
    assertEquals("com\\.example:id/error_banner", match.resourceIdRegex)
  }

  @Test
  fun `converts visible condition with index`() {
    val command = AssertConditionCommand(
      condition = Condition(
        visible = ElementSelector(
          textRegex = "Item",
          index = "2",
        ),
      ),
    )

    val actions = MaestroCommandConverter.convert(command)

    assertEquals(1, actions.size)
    val action = assertIs<AccessibilityAction.AssertVisible>(actions.single())
    assertEquals(2, action.nodeSelector.index)
  }

  @Test
  fun `converts visible condition with spatial relationships`() {
    val command = AssertConditionCommand(
      condition = Condition(
        visible = ElementSelector(
          textRegex = "OK",
          below = ElementSelector(textRegex = "Are you sure?"),
        ),
      ),
    )

    val actions = MaestroCommandConverter.convert(command)

    assertEquals(1, actions.size)
    val action = assertIs<AccessibilityAction.AssertVisible>(actions.single())
    val belowMatch =
      assertIs<DriverNodeMatch.AndroidAccessibility>(action.nodeSelector.below!!.driverMatch)
    assertEquals("Are you sure?", belowMatch.textRegex)
  }

  @Test
  fun `skips unsupported condition and returns empty list`() {
    // A condition with neither visible nor notVisible set
    val command = AssertConditionCommand(
      condition = Condition(),
    )

    val actions = MaestroCommandConverter.convert(command)

    assertTrue(actions.isEmpty())
  }

  @Test
  fun `convertAll handles mixed commands including assertions`() {
    val commands = listOf(
      AssertConditionCommand(
        condition = Condition(visible = ElementSelector(textRegex = "Hello")),
      ),
      AssertConditionCommand(
        condition = Condition(notVisible = ElementSelector(textRegex = "Goodbye")),
      ),
    )

    val actions = MaestroCommandConverter.convertAll(commands)

    assertEquals(2, actions.size)
    assertIs<AccessibilityAction.AssertVisible>(actions[0])
    assertIs<AccessibilityAction.AssertNotVisible>(actions[1])
  }
}
