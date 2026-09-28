package xyz.block.trailblaze.android.accessibility

import kotlin.test.Test
import kotlin.test.assertEquals
import maestro.orchestra.HideKeyboardCommand
import maestro.orchestra.InputRandomCommand
import maestro.orchestra.InputTextCommand
import maestro.orchestra.PressKeyCommand
import maestro.KeyCode
import xyz.block.trailblaze.api.AgentDriverAction
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult

/**
 * `inputText` lowers to "type, then hide the keyboard". These pin that the accessibility driver
 * runs that pair as ONE action (one pre-action capture) while keeping every other
 * command, and every non-adjacent hide, as its own action in its recorded order.
 */
class FoldKeyboardHideIntoInputTextTest {

  private val typed = AccessibilityAction.InputText(text = "battery")

  @Test
  fun `a hide right after typing folds into the typing action`() {
    val folded = MaestroCommandConverter.foldKeyboardHideIntoInputText(
      listOf(typed, AccessibilityAction.HideKeyboard),
    )

    assertEquals(listOf(typed.copy(hideKeyboardAfter = true)), folded)
  }

  @Test
  fun `only the adjacent hide folds, and only once`() {
    val actions = listOf(
      AccessibilityAction.HideKeyboard,
      typed,
      AccessibilityAction.PressBack,
      AccessibilityAction.HideKeyboard,
      typed,
      AccessibilityAction.HideKeyboard,
      AccessibilityAction.HideKeyboard,
    )

    assertEquals(
      listOf(
        AccessibilityAction.HideKeyboard,
        typed,
        AccessibilityAction.PressBack,
        AccessibilityAction.HideKeyboard,
        typed.copy(hideKeyboardAfter = true),
        AccessibilityAction.HideKeyboard,
      ),
      MaestroCommandConverter.foldKeyboardHideIntoInputText(actions),
    )
  }

  @Test
  fun `typing commands dispatch together with the hide right after them, everything else alone`() {
    val type = InputTextCommand(text = "battery")
    val random = InputRandomCommand()
    val hide = HideKeyboardCommand()
    val enter = PressKeyCommand(code = KeyCode.ENTER)

    val units = AccessibilityTrailblazeAgent.dispatchUnits(
      listOf(hide, type, hide, random, hide, enter, type, enter, hide),
    )

    assertEquals(
      listOf(listOf(hide), listOf(type, hide), listOf(random, hide), listOf(enter), listOf(type), listOf(enter), listOf(hide)),
      units,
    )
  }

  @Test
  fun `the logged action says the typing step also hid the keyboard`() {
    fun logged(action: AccessibilityAction.InputText) = AccessibilityTrailRunner.mapToAgentDriverAction(
      action = action,
      executionResult = AccessibilityDeviceManager.ExecutionResult(),
      toolResult = TrailblazeToolResult.Success(),
    )

    assertEquals(
      AgentDriverAction.EnterText(text = "battery", hideKeyboardAfter = true),
      logged(typed.copy(hideKeyboardAfter = true)),
      "The folded hide has no log entry of its own, so the report can only learn of it here.",
    )
    assertEquals(AgentDriverAction.EnterText(text = "battery"), logged(typed))
  }
}
