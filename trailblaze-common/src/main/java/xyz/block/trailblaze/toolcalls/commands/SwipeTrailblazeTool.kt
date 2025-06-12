package xyz.block.trailblaze.toolcalls.commands

import ai.koog.agents.core.tools.annotations.LLMDescription
import kotlinx.serialization.Serializable
import maestro.SwipeDirection
import maestro.orchestra.Command
import maestro.orchestra.ElementSelector
import maestro.orchestra.SwipeCommand
import xyz.block.trailblaze.toolcalls.MapsToMaestroCommands
import xyz.block.trailblaze.toolcalls.TrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolClass

@Serializable
@TrailblazeToolClass("swipe")
@LLMDescription(
  """
Swipes the screen in the specified direction. This is useful for navigating through long lists or pages.
    """,
)
class SwipeTrailblazeTool(
  @LLMDescription("Valid values: UP, DOWN, LEFT, RIGHT")
  val direction: String,
  @LLMDescription(
    """
The text value to swipe on. If not provided, the swipe will be performed on the center of the screen.
  """,
  )
  val swipeOnElementText: String? = null,
) : TrailblazeTool,
  MapsToMaestroCommands {
  override fun toMaestroCommands(): List<Command> = listOf(
    SwipeCommand(
      direction = SwipeDirection.valueOf(direction),
    ).let {
      if (swipeOnElementText != null) {
        it.copy(
          elementSelector = ElementSelector(
            textRegex = swipeOnElementText,
          ),
        )
      } else {
        it
      }
    },
  )
}
