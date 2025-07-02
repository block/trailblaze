package xyz.block.trailblaze.toolcalls.commands

import ai.koog.agents.core.tools.annotations.LLMDescription
import kotlinx.serialization.Serializable
import maestro.orchestra.AssertConditionCommand
import maestro.orchestra.Command
import maestro.orchestra.Condition
import maestro.orchestra.ElementSelector
import xyz.block.trailblaze.toolcalls.MapsToMaestroCommands
import xyz.block.trailblaze.toolcalls.TrailblazeToolClass
import xyz.block.trailblaze.toolcalls.TrailblazeTools.REQUIRED_TEXT_DESCRIPTION

@Serializable
@TrailblazeToolClass("assertVisibleWithText")
@LLMDescription(
  """
Asserts that an element with the provided text is visible on the screen. The text argument is required. Only provide additional fields if the text provided exactly matches elsewhere on the screen. In this case, the additional fields will be used to identify the specific view to assert visibility for.

NOTE:
- This will wait for the item to appear if it is not visible yet.
- You may need to scroll down the page or close the keyboard if it is not visible in the screenshot.
- Use this tool whenever an objective begins with the word expect, verify, confirm, or assert (case-insensitive).
""",
)
data class AssertVisibleWithTextTrailblazeTool(
  @LLMDescription(REQUIRED_TEXT_DESCRIPTION)
  val text: String,
  @LLMDescription("0-based index of the view to select among those that match all other criteria.")
  val index: Int = 0,
  @LLMDescription("Regex for selecting the view by id. This is helpful to disambiguate when multiple views have the same text.")
  val id: String? = null,
  val enabled: Boolean? = null,
  val selected: Boolean? = null,
) : MapsToMaestroCommands() {
  override fun toMaestroCommands(): List<Command> = listOf(
    AssertConditionCommand(
      condition = Condition(
        visible = ElementSelector(
          textRegex = text,
          idRegex = id,
          index = if (index == 0) null else index.toString(),
          enabled = enabled,
          selected = selected,
        ),
      ),
    ),
  )
}
