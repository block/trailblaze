package xyz.block.trailblaze.toolcalls.commands

import ai.koog.agents.core.tools.annotations.LLMDescription
import kotlinx.serialization.Serializable
import maestro.orchestra.HideKeyboardCommand
import maestro.orchestra.SwipeCommand
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
    val isIOS = toolExecutionContext.screenState?.trailblazeDevicePlatform == TrailblazeDevicePlatform.IOS
    val maestroCommands = if (isIOS) gentleScroll() else listOf(HideKeyboardCommand())

    return toolExecutionContext.trailblazeAgent.runMaestroCommands(
      maestroCommands = maestroCommands,
      traceId = toolExecutionContext.traceId,
    )
  }

  /**
   * Scrolls down the back up by a small amount and returns to the original position.
   * This dismisses the keyboard on iOS since Maestro's command is flaky on that platform.
   */
  private fun gentleScroll() = listOf(
    SwipeCommand(
      startRelative = "50%,50%",
      endRelative = "50%,55%",
    ),
    SwipeCommand(
      startRelative = "50%,50%",
      endRelative = "50%,45%",
    ),
  )
}
