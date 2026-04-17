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
import xyz.block.trailblaze.toolcalls.isSuccess

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

    val result = toolExecutionContext.trailblazeAgent.runMaestroCommands(
      maestroCommands = maestroCommands,
      traceId = toolExecutionContext.traceId,
    )
    if (result.isSuccess()) return TrailblazeToolResult.Success(message = "Keyboard hidden")
    return result
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
     * Dismisses the keyboard on iOS with a short, gentle downward swipe above the keyboard.
     *
     * In landscape, Maestro's native hideKeyboard swipes at 50%,50%, which lands on the keyboard
     * on iPad, so we instead swipe from 50%,47% to 50%,50% to stay just above the keyboard while
     * still triggering dismissal.
     *
     * In portrait, we use a similarly short downward swipe from 50%,60% to 50%,63%, starting just
     * above where the keyboard typically appears and moving slightly downward to gently nudge the
     * scrollable content without aggressively scrolling the screen.
     */
    fun hideIosKeyboardWithGentleScrollCommands(orientation: TrailblazeDeviceOrientation): List<Command> {
      return when (orientation) {
        TrailblazeDeviceOrientation.LANDSCAPE -> listOf(
          SwipeCommand(
            startRelative = "50%,47%",
            endRelative = "50%,50%",
            duration = 50,
          )
        )

        TrailblazeDeviceOrientation.PORTRAIT -> listOf(
          SwipeCommand(
            startRelative = "50%,60%",
            endRelative = "50%,63%",
            duration = 50,
          )
        )
      }
    }
  }
}
