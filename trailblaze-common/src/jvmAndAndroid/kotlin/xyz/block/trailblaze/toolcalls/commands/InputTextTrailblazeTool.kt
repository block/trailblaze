package xyz.block.trailblaze.toolcalls.commands

import ai.koog.agents.core.tools.annotations.LLMDescription
import kotlinx.serialization.Serializable
import maestro.orchestra.InputTextCommand
import xyz.block.trailblaze.toolcalls.ExecutableTrailblazeTool
import xyz.block.trailblaze.toolcalls.ReasoningTrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolClass
import xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult
import xyz.block.trailblaze.toolcalls.TrailblazeTools.REQUIRED_TEXT_DESCRIPTION
import xyz.block.trailblaze.toolcalls.isSuccess

@Serializable
@TrailblazeToolClass("inputText")
@LLMDescription(
  """
Type characters into the currently focused text field.
- NOTE: If the text field is not focused, tap on it first.
- NOTE: If the field already contains text you want to replace, use eraseText first.
- NOTE: After typing, consider closing the soft keyboard to avoid issues with the app.
      """,
)
data class InputTextTrailblazeTool(
  @param:LLMDescription(REQUIRED_TEXT_DESCRIPTION) val text: String,
  override val reasoning: String? = null,
) : ExecutableTrailblazeTool, ReasoningTrailblazeTool {

  override suspend fun execute(toolExecutionContext: TrailblazeToolExecutionContext): TrailblazeToolResult {
    val interpolated = toolExecutionContext.memory.interpolateVariables(text)
    val maestroCommands = listOf(InputTextCommand(interpolated)) +
      HideKeyboardTrailblazeTool.hideKeyboardCommands(
        platform = toolExecutionContext.screenState?.trailblazeDevicePlatform,
        orientation = toolExecutionContext.trailblazeDeviceInfo.orientation,
      )
    val result = toolExecutionContext.trailblazeAgent.runMaestroCommands(
      maestroCommands = maestroCommands,
      traceId = toolExecutionContext.traceId,
    )
    if (result.isSuccess()) return TrailblazeToolResult.Success(message = "Typed '$interpolated'")
    return result
  }
}
