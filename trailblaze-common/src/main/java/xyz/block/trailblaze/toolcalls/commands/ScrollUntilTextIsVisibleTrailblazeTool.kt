package xyz.block.trailblaze.toolcalls.commands

import ai.koog.agents.core.tools.annotations.LLMDescription
import kotlinx.serialization.Serializable
import maestro.ScrollDirection
import maestro.orchestra.Command
import maestro.orchestra.ElementSelector
import maestro.orchestra.ScrollUntilVisibleCommand
import xyz.block.trailblaze.toolcalls.MapsToMaestroCommands
import xyz.block.trailblaze.toolcalls.TrailblazeToolClass
import xyz.block.trailblaze.toolcalls.TrailblazeTools.REQUIRED_TEXT_DESCRIPTION

@Serializable
@TrailblazeToolClass("scrollUntilTextIsVisible")
@LLMDescription(
  """
Scrolls the screen in the specified direction until an element with the provided text becomes visible
in the view hierarchy. Ensure that you provide the entire string to this function to streamline finding 
the corresponding view.

The text argument is required. Only provide additional fields if the text provided exactly
matches elsewhere on the screen. In this case the additional fields will be used to identify
the specific view to expect to be visible while scrolling.
""",
)
class ScrollUntilTextIsVisibleTrailblazeTool(
  @LLMDescription(REQUIRED_TEXT_DESCRIPTION)
  val text: String,
  @LLMDescription("0-based index of the view to select among those that match all other criteria.")
  val index: Int = 0,
  @LLMDescription("Regex for selecting the view by id. This is helpful to disambiguate when multiple views have the same text.")
  val id: String? = null,
  @LLMDescription("Valid values: UP, DOWN, LEFT, RIGHT. If not provided, it will start scrolling towards the bottom of the screen (DOWN value).")
  val direction: ScrollDirection,
  @LLMDescription("Percentage of element visible in viewport.")
  val visibilityPercentage: Int = 100,
  @LLMDescription("Boolean to determine if it will attempt to stop scrolling when the element is closer to the screen center.")
  val centerElement: Boolean = false,
  val enabled: Boolean? = null,
  val selected: Boolean? = null,
) : MapsToMaestroCommands() {
  override fun toMaestroCommands(): List<Command> = listOf(
    ScrollUntilVisibleCommand(
      selector = ElementSelector(
        textRegex = text,
        idRegex = id,
        index = if (index == 0) null else index.toString(),
        enabled = enabled,
        selected = selected,
      ),
      direction = direction,
      visibilityPercentage = visibilityPercentage,
      centerElement = centerElement,
    ),
  )
}
