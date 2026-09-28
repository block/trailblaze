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
- NOTE: This does nothing unless an editable text field is focused. If the field isn't focused, tap it first.
- NOTE: A number pad, PIN pad, or button grid has no text field — inputText won't work there, so tap each digit button instead.
- NOTE: If the field already contains text you want to replace, use eraseText first.
- NOTE: After typing, consider closing the soft keyboard to avoid issues with the app.
      """,
)
data class InputTextTrailblazeTool(
  @param:LLMDescription(REQUIRED_TEXT_DESCRIPTION) val text: String,
  override val reasoning: String? = null,
  /**
   * Whether to dismiss the soft keyboard after typing. Default `true`: each `inputText` step
   * leaves the device ready for the next one, with no keyboard covering the next tap's target.
   * Recorded trails omit this field, so they replay with the dismissal.
   *
   * The wasm `/devices` viewer passes `false` on its per-keystroke flush, because the user is
   * still typing.
   */
  @param:LLMDescription(
    "Close the soft keyboard after typing. Defaults to true; pass false to keep typing into the same field.",
  )
  val hideKeyboardAfter: Boolean = true,
) : ExecutableTrailblazeTool, ReasoningTrailblazeTool {

  override suspend fun execute(toolExecutionContext: TrailblazeToolExecutionContext): TrailblazeToolResult {
    // {{var}}/${var} tokens are resolved by the dispatch boundary (interpolateMemoryInTool)
    // before execute() runs, so `text` arrives resolved here.
    val maestroCommands = if (hideKeyboardAfter) {
      listOf(InputTextCommand(text)) +
        HideKeyboardTrailblazeTool.hideKeyboardCommands(toolExecutionContext.trailblazeDeviceInfo)
    } else {
      listOf(InputTextCommand(text))
    }
    val result = toolExecutionContext.trailblazeAgent.runMaestroCommands(
      maestroCommands = maestroCommands,
      traceId = toolExecutionContext.traceId,
    )
    if (result.isSuccess()) return TrailblazeToolResult.Success(message = "Typed '$text'")
    return result
  }
}
