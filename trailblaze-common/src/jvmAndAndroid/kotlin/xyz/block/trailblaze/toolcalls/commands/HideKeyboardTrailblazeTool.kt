package xyz.block.trailblaze.toolcalls.commands

import ai.koog.agents.core.tools.annotations.LLMDescription
import kotlinx.serialization.Serializable
import maestro.orchestra.Command
import maestro.orchestra.HideKeyboardCommand
import maestro.orchestra.SwipeCommand
import xyz.block.trailblaze.devices.TrailblazeDeviceOrientation
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.toolcalls.ExecutableTrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolClass
import xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult

@Serializable
@TrailblazeToolClass("hideKeyboard")
@LLMDescription(
  """
This hide the keyboard on the screen. This is useful to do after entering text into an input field.
  """,
)
class HideKeyboardTrailblazeTool : ExecutableTrailblazeTool {

  override fun equals(other: Any?): Boolean = this === other

  override fun hashCode(): Int = System.identityHashCode(this)

  override suspend fun execute(toolExecutionContext: TrailblazeToolExecutionContext): TrailblazeToolResult {
    val maestroCommands = hideKeyboardCommands(
      platform = toolExecutionContext.screenState?.trailblazeDevicePlatform,
      orientation = toolExecutionContext.trailblazeDeviceInfo.orientation,
    )

    return toolExecutionContext.trailblazeAgent.runMaestroCommands(
      maestroCommands = maestroCommands,
      traceId = toolExecutionContext.traceId,
    )
  }

  companion object {
    fun hideKeyboardCommands(
      platform: TrailblazeDevicePlatform?,
      orientation: TrailblazeDeviceOrientation,
    ): List<Command> {
      return if (platform == TrailblazeDevicePlatform.IOS) {
        hideIosKeyboardWithGentleScrollCommands(orientation)
      } else {
        listOf(HideKeyboardCommand())
      }
    }
    
    /**
     * Dismisses the keyboard on iOS landscape with a fast downward swipe above the keyboard.
     * Maestro's native hideKeyboard swipes at 50%,50% which lands on the keyboard
     * in iPad landscape, so we swipe at 50%,30% (above center-y) instead.
     */
    fun hideIosKeyboardWithGentleScrollCommands(orientation: TrailblazeDeviceOrientation): List<Command> {
      return when (orientation) {
        TrailblazeDeviceOrientation.LANDSCAPE -> listOf(
          SwipeCommand(
            startRelative = "50%,30%",
            endRelative = "50%,33%",
            duration = 50,
          )
        )

        TrailblazeDeviceOrientation.PORTRAIT -> listOf(HideKeyboardCommand())
      }
    }
  }
}
