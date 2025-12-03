package xyz.block.trailblaze.toolcalls.commands

import ai.koog.agents.core.tools.annotations.LLMDescription
import kotlinx.serialization.Serializable
import maestro.SwipeDirection
import maestro.orchestra.Command
import maestro.orchestra.ElementSelector
import maestro.orchestra.SwipeCommand
import xyz.block.trailblaze.AgentMemory
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.toolcalls.ExecutableTrailblazeTool
import xyz.block.trailblaze.toolcalls.MapsToMaestroCommands
import xyz.block.trailblaze.toolcalls.TrailblazeToolClass
import xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult

@Serializable
@TrailblazeToolClass("swipe")
@LLMDescription(
  """
Swipes the screen in the specified direction. This is useful for navigating through long lists or pages.
The start and end points are automatically calculated based on the direction and screen dimensions.
    """,
)
class SwipeTrailblazeTool(
  @param:LLMDescription("The direction to swipe. Default is 'DOWN'.")
  val direction: SwipeDirection = SwipeDirection.DOWN,
  @param:LLMDescription(
    """
The text value to swipe on. If not provided, the swipe will be performed on the center of the screen.
  """,
  )
  val swipeOnElementText: String? = null,
) : ExecutableTrailblazeTool {

  override suspend fun execute(toolExecutionContext: TrailblazeToolExecutionContext): TrailblazeToolResult {
    val memory = toolExecutionContext.trailblazeAgent.memory
    val devicePlatform = toolExecutionContext.screenState?.trailblazeDevicePlatform

    val (startRelative, endRelative) = when (direction) {
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

    val maestroCommands = when (devicePlatform) {
      TrailblazeDevicePlatform.IOS -> {
        // This is a temporary workaround to Support previous logic (TBZ-287)
        SwipeWithRelativeCoordinatesTool(
          startRelative = startRelative,
          endRelative = endRelative,
          swipeOnElementText = swipeOnElementText,
        ).toMaestroCommands(memory)
      }

      else -> listOf(
        SwipeCommand(
          elementSelector = swipeOnElementText?.let {
            ElementSelector(
              textRegex = swipeOnElementText,
            )
          },
          direction = direction,
        ),
      )
    }
    return toolExecutionContext.trailblazeAgent.runMaestroCommands(
      maestroCommands = maestroCommands,
      traceId = toolExecutionContext.traceId,
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

    println(
      "SwipeWithRelativeCoordinatesTool creating Maestro SwipeCommand: startRelative=$startRelative, endRelative=$endRelative, elementSelector=${command.elementSelector}",
    )

    return listOf(command)
  }
}
