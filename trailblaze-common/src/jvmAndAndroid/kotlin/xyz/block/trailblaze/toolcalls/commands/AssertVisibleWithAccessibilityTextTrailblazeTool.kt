package xyz.block.trailblaze.toolcalls.commands

import ai.koog.agents.core.tools.annotations.LLMDescription
import kotlinx.serialization.Serializable
import maestro.orchestra.AssertConditionCommand
import maestro.orchestra.Command
import maestro.orchestra.Condition
import maestro.orchestra.ElementSelector
import xyz.block.trailblaze.AgentMemory
import xyz.block.trailblaze.toolcalls.MapsToMaestroCommands
import xyz.block.trailblaze.toolcalls.TrailblazeToolClass
import xyz.block.trailblaze.toolcalls.TrailblazeTools.REQUIRED_ACCESSIBILITY_TEXT_DESCRIPTION

@Serializable
@TrailblazeToolClass("assertVisibleWithAccessibilityText", isForLlm = false, isVerification = true)
@LLMDescription(
  """
Assert that an element with the provided accessibility text is visible on the screen. The accessibilityText argument is required. Only provide additional fields if the accessibility text matches elsewhere on the screen — the additional fields disambiguate the specific view.

NOTE:
- Waits for the item to appear if it is not visible yet.
- You may need to scroll down the page or close the keyboard if it is not visible in the screenshot.
- Reach for this tool when an objective begins with the word expect, verify, confirm, or assert (case-insensitive).
""",
)
@Deprecated("Use [AssertVisibleTrailblazeTool].")
data class AssertVisibleWithAccessibilityTextTrailblazeTool(
  @LLMDescription(REQUIRED_ACCESSIBILITY_TEXT_DESCRIPTION)
  val accessibilityText: String,
  @LLMDescription("Regex for selecting the view by id. This is helpful to disambiguate when multiple views have the same accessibility text.")
  val id: String? = null,
  @LLMDescription("0-based index of the view to select among those that match all other criteria.")
  val index: Int = 0,
  val enabled: Boolean? = null,
  val selected: Boolean? = null,
) : MapsToMaestroCommands() {
  override fun toMaestroCommands(memory: AgentMemory): List<Command> = listOf(
    AssertConditionCommand(
      condition = Condition(
        visible = ElementSelector(
          textRegex = accessibilityText,
          idRegex = id,
          index = if (index == 0) null else index.toString(),
          enabled = enabled,
          selected = selected,
        ),
      ),
    ),
  )
}
