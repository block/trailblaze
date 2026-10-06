package xyz.block.trailblaze.toolcalls.commands

import ai.koog.agents.core.tools.annotations.LLMDescription
import kotlinx.serialization.Serializable
import maestro.SwipeDirection
import maestro.orchestra.Command
import maestro.orchestra.ElementSelector
import maestro.orchestra.SwipeCommand
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
@LLMDescription("Swipe the screen in a direction, e.g. to move through long lists or pages.")
data class SwipeTrailblazeTool(
  @param:LLMDescription(
    "Finger direction, not scroll direction: UP reveals content below, DOWN reveals content " +
      "above. Default DOWN.",
  )
  @Serializable(with = LenientSwipeDirectionSerializer::class)
  val direction: SwipeDirection = SwipeDirection.DOWN,
  @param:LLMDescription("Text of the element to swipe on. Omit to swipe from the screen center.")
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
@Deprecated(
  "Not for new code, but not going away: the recording pipeline constructs this directly " +
    "(MaestroInteractionToolFactory, OnDeviceRpcDeviceScreenStream), so those sites suppress this.",
)
@Serializable
@TrailblazeToolClass(
  name = "swipeWithRelativeCoordinates",
  surfaceToLlm = false,
)
@LLMDescription("Swipes using relative coordinates. Internal tool only.")
data class SwipeWithRelativeCoordinatesTool(
  val startRelative: String,
  val endRelative: String,
  val swipeOnElementText: String? = null,
  /**
   * How long the swipe takes on the device, end-to-end. Mirrors Maestro's
   * `SwipeCommand.duration`; null falls through to Maestro's 400ms default. The recording
   * UI populates this with the actual wall-clock duration of the user's gesture so a fast
   * flick replays as a flick and a slow drag replays as a drag.
   */
  val durationMs: Long? = null,
) : MapsToMaestroCommands() {
  override fun toMaestroCommands(): List<Command> {
    val command = if (durationMs != null) {
      SwipeCommand(
        startRelative = startRelative,
        endRelative = endRelative,
        elementSelector = swipeOnElementText?.let {
          ElementSelector(textRegex = swipeOnElementText)
        },
        duration = durationMs,
      )
    } else {
      SwipeCommand(
        startRelative = startRelative,
        endRelative = endRelative,
        elementSelector = swipeOnElementText?.let {
          ElementSelector(textRegex = swipeOnElementText)
        },
      )
    }

    Console.log(
      "SwipeWithRelativeCoordinatesTool creating Maestro SwipeCommand: startRelative=$startRelative, endRelative=$endRelative, elementSelector=${command.elementSelector}, durationMs=$durationMs",
    )

    return listOf(command)
  }
}
