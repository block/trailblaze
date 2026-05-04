package xyz.block.trailblaze.toolcalls.commands

import ai.koog.agents.core.tools.annotations.LLMDescription
import kotlinx.serialization.Serializable
import maestro.SwipeDirection
import maestro.orchestra.Command
import maestro.orchestra.ElementSelector
import maestro.orchestra.SwipeCommand
import xyz.block.trailblaze.AgentMemory
import xyz.block.trailblaze.toolcalls.ExecutableTrailblazeTool
import xyz.block.trailblaze.toolcalls.MapsToMaestroCommands
import xyz.block.trailblaze.toolcalls.ReasoningTrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolClass
import xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult
import xyz.block.trailblaze.toolcalls.isSuccess
import xyz.block.trailblaze.util.Console

@Serializable
@TrailblazeToolClass("swipe")
@LLMDescription(
  """
Swipe the screen in the specified direction to navigate long lists or pages. Start and end points
are calculated automatically from the direction and screen dimensions.
    """,
)
class SwipeTrailblazeTool(
  @param:LLMDescription(
    """The direction of the finger swipe gesture (not the scroll direction).
To see more content BELOW (scroll down), use 'UP' (finger swipes upward).
To see more content ABOVE (scroll up), use 'DOWN' (finger swipes downward).
Default is 'DOWN'.""",
  )
  val direction: SwipeDirection = SwipeDirection.DOWN,
  @param:LLMDescription(
    """
The text value to swipe on. If not provided, the swipe will be performed on the center of the screen.
  """,
  )
  val swipeOnElementText: String? = null,
  override val reasoning: String? = null,
) : ExecutableTrailblazeTool, ReasoningTrailblazeTool {

  override suspend fun execute(toolExecutionContext: TrailblazeToolExecutionContext): TrailblazeToolResult {
    Console.log(
      "SwipeTrailblazeTool delegating: direction=$direction, swipeOnElementText=$swipeOnElementText",
    )

    val maestroCommands = listOf(
      SwipeCommand(
        elementSelector = swipeOnElementText?.let {
          ElementSelector(
            textRegex = swipeOnElementText,
          )
        },
        direction = direction,
      ),
    )
    val result = toolExecutionContext.trailblazeAgent.runMaestroCommands(
      maestroCommands = maestroCommands,
      traceId = toolExecutionContext.traceId,
    )
    if (result.isSuccess()) {
      val target = swipeOnElementText?.let { " on '$it'" } ?: ""
      return TrailblazeToolResult.Success(message = "Swiped $direction$target")
    }
    return result
  }
}

/**
 * Internal tool that executes swipe commands with relative coordinates.
 * This tool is not directly exposed to the LLM but is used internally by SwipeTrailblazeTool.
 *
 * ----- DO NOT GIVE THIS TOOL TO THE LLM -----
 * This is a tool that should be delegated to, not registered to the LLM.
 */
@Deprecated("This is only being used by a few handwritten tests and may go away in the future.")
@Serializable
@TrailblazeToolClass(
  name = "swipeWithRelativeCoordinates",
  isForLlm = false,
)
@LLMDescription("Swipes using relative coordinates. Internal tool only.")
data class SwipeWithRelativeCoordinatesTool(
  val startRelative: String,
  val endRelative: String,
  val swipeOnElementText: String? = null,
) : MapsToMaestroCommands() {
  override fun toMaestroCommands(memory: AgentMemory): List<Command> {
    val command = SwipeCommand(
      startRelative = startRelative,
      endRelative = endRelative,
      elementSelector = swipeOnElementText?.let {
        ElementSelector(
          textRegex = swipeOnElementText,
        )
      },
    )

    Console.log(
      "SwipeWithRelativeCoordinatesTool creating Maestro SwipeCommand: startRelative=$startRelative, endRelative=$endRelative, elementSelector=${command.elementSelector}",
    )

    return listOf(command)
  }
}
