package xyz.block.trailblaze.toolcalls.commands

import ai.koog.agents.core.tools.annotations.LLMDescription
import kotlinx.serialization.Serializable
import maestro.orchestra.InputTextCommand
import xyz.block.trailblaze.toolcalls.ExecutableTrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolClass
import xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult
import xyz.block.trailblaze.toolcalls.TrailblazeTools.REQUIRED_TEXT_DESCRIPTION

@Serializable
@TrailblazeToolClass("inputText")
@LLMDescription(
  """
This will type characters into the currently focused text field. This is useful for entering text.
- NOTE: If the text input field is not currently focused, please tap on the text field to focus it before using this command.
- NOTE: If the text field already contains text that you want to replace, use eraseText first to clear it before typing new text.
- NOTE: After typing text, consider closing the soft keyboard to avoid any issues with the app.
      """,
)
data class InputTextTrailblazeTool(
  @param:LLMDescription(REQUIRED_TEXT_DESCRIPTION) val text: String,
) : ExecutableTrailblazeTool {

  override suspend fun execute(toolExecutionContext: TrailblazeToolExecutionContext): TrailblazeToolResult {
    val maestroCommands = listOf(
      InputTextCommand(toolExecutionContext.memory.interpolateVariables(text)),
    ) +
      HideKeyboardTrailblazeTool.hideKeyboardCommands(
        platform = toolExecutionContext.screenState?.trailblazeDevicePlatform,
        orientation = toolExecutionContext.trailblazeDeviceInfo.orientation,
      )
    return toolExecutionContext.trailblazeAgent.runMaestroCommands(
      maestroCommands = maestroCommands,
      traceId = toolExecutionContext.traceId,
    )
  }
}
