package xyz.block.trailblaze.toolcalls.commands

import kotlinx.serialization.Serializable
import maestro.orchestra.Command
import maestro.orchestra.ElementSelector
import maestro.orchestra.TapOnElementCommand
import xyz.block.trailblaze.toolcalls.MapsToMaestroCommands
import xyz.block.trailblaze.toolcalls.TrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolClass
import xyz.block.trailblaze.toolcalls.TrailblazeToolProperty
import xyz.block.trailblaze.toolcalls.TrailblazeTools.REQUIRED_TEXT_DESCRIPTION

@Serializable
@TrailblazeToolClass(
  name = "longPressOnElementWithText",
  description = """
Invoking this function will trigger a long press on the provided text. Ensure that you 
provide the entire string to this function to streamline finding the corresponding view.

The text argument is required. Only provide additional fields if the text provided exactly
matches elsewhere on the screen. In this case the additional fields will be used to identify
the specific view to long press on.
      """,
)
data class LongPressOnElementWithTextTrailblazeTool(
  @TrailblazeToolProperty(
    description = REQUIRED_TEXT_DESCRIPTION,
  )
  val text: String,
  val id: String? = null,
  val index: String? = null,
  val enabled: Boolean? = null,
  val selected: Boolean? = null,
) : TrailblazeTool,
  MapsToMaestroCommands {
  override fun toMaestroCommands(): List<Command> = listOf(
    TapOnElementCommand(
      selector = ElementSelector(
        textRegex = text,
        idRegex = id,
        index = index,
        enabled = enabled,
        selected = selected,
      ),
      longPress = true,
      retryIfNoChange = false,
    ),
  )
}
