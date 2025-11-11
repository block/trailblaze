package xyz.block.trailblaze.toolcalls.commands

import ai.koog.agents.core.tools.annotations.LLMDescription
import kotlinx.serialization.Serializable
import maestro.orchestra.Command
import maestro.orchestra.ElementSelector
import maestro.orchestra.TapOnElementCommand
import xyz.block.trailblaze.AgentMemory
import xyz.block.trailblaze.toolcalls.MapsToMaestroCommands
import xyz.block.trailblaze.toolcalls.TrailblazeToolClass
import xyz.block.trailblaze.toolcalls.TrailblazeTools.REQUIRED_TEXT_DESCRIPTION

@Serializable
@TrailblazeToolClass("tapOnElementWithText")
@LLMDescription(
  """
Invoking this function will trigger a tap/click on an element containing the provided text. 
The text does not need to be an exact match - it will find elements where the provided text 
appears anywhere within the element's text.

The text argument is required. Only provide additional fields if multiple elements contain the same text.
In this case the additional fields will be used to identify the specific view to tap on.

NOTE:
- This will only work if the item is actually visible in the screenshot, even if the item is in the view hierarchy.
- You may need to scroll down the page or close the keyboard if it is not visible in the screenshot.
""",
)
@Deprecated("Use [TapOnElementByNodeIdTrailblazeTool] or [TapOnByElementSelector].")
data class TapOnElementWithTextTrailblazeTool(
  @LLMDescription(REQUIRED_TEXT_DESCRIPTION)
  val text: String,
  @LLMDescription("0-based index of the view to select among those that match all other criteria.")
  val index: Int = 0,
  @LLMDescription("Regex for selecting the view by id.  This is helpful to disambiguate when multiple views have the same text.")
  val id: String? = null,
  val enabled: Boolean? = null,
  val selected: Boolean? = null,
) : MapsToMaestroCommands() {
  override fun toMaestroCommands(memory: AgentMemory): List<Command> = listOf(
    TapOnElementCommand(
      selector = ElementSelector(
        textRegex = ".*${Regex.escape(memory.interpolateVariables(text))}.*",
        idRegex = id,
        index = if (index == 0) null else index.toString(),
        enabled = enabled,
        selected = selected,
      ),
    ),
  )
}
