package xyz.block.trailblaze.toolcalls.commands

import ai.koog.agents.core.tools.annotations.LLMDescription
import kotlinx.serialization.Serializable
import maestro.SwipeDirection
import maestro.orchestra.Command
import maestro.orchestra.ElementSelector
import maestro.orchestra.SwipeCommand
import xyz.block.trailblaze.AgentMemory
import xyz.block.trailblaze.toolcalls.DelegatingTrailblazeTool
import xyz.block.trailblaze.toolcalls.ExecutableTrailblazeTool
import xyz.block.trailblaze.toolcalls.MapsToMaestroCommands
import xyz.block.trailblaze.toolcalls.TrailblazeToolClass
import xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext

@Serializable
@TrailblazeToolClass("swipe")
@LLMDescription(
  """
Swipes the screen in the specified direction. This is useful for navigating through long lists or pages.
The start and end points are automatically calculated based on the direction and screen dimensions.
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
) : DelegatingTrailblazeTool {

  override fun toExecutableTrailblazeTools(executionContext: TrailblazeToolExecutionContext): List<ExecutableTrailblazeTool> {
    // Calculate start and end coordinates based on direction
    // These percentages match the logic in MaestroAndroidUiAutomatorDriver
    val (startRelative, endRelative) = when (SwipeDirection.valueOf(direction)) {
      SwipeDirection.UP -> {
        // Start at center, move up to 40% height
        "50%,50%" to "50%,30%"
      }

      SwipeDirection.DOWN -> {
        // Start at center, move down to 60% height
        "50%,50%" to "50%,70%"
      }

      SwipeDirection.LEFT -> {
        // Start at 90% width (right side), move left to 10%
        "90%,50%" to "10%,50%"
      }

      SwipeDirection.RIGHT -> {
        // Start at 10% width (left side), move right to 90%
        "10%,50%" to "90%,50%"
      }
    }

    println(
      "SwipeTrailblazeTool delegating: direction=$direction, startRelative=$startRelative, endRelative=$endRelative, swipeOnElementText=$swipeOnElementText",
    )

    return listOf(
      SwipeWithRelativeCoordinatesTool(
        startRelative = startRelative,
        endRelative = endRelative,
        swipeOnElementText = swipeOnElementText,
      ),
    )
  }
}

/**
 * Internal tool that executes swipe commands with relative coordinates.
 * This tool is not directly exposed to the LLM but is used internally by SwipeTrailblazeTool.
 *
 * ----- DO NOT GIVE THIS TOOL TO THE LLM -----
 * This is a tool that should be delegated to, not registered to the LLM.
 */
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
    }

    println(
      "SwipeWithRelativeCoordinatesTool creating Maestro SwipeCommand: startRelative=$startRelative, endRelative=$endRelative, elementSelector=${command.elementSelector}",
    )

    return listOf(command)
  }
}
