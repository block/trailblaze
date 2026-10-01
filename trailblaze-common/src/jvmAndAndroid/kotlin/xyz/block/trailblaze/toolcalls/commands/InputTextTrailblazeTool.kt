package xyz.block.trailblaze.toolcalls.commands

import ai.koog.agents.core.tools.annotations.LLMDescription
import kotlinx.serialization.Serializable
import maestro.orchestra.InputTextCommand
import xyz.block.trailblaze.api.TrailblazeNodeSelector
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
  /**
   * The field to type into. When set, the field is tapped first with the same selector-resolved tap
   * as `tapOnElementBySelector`, so a field that reacts to a click still gets one; the Android
   * accessibility driver then also focuses it directly when it is an editable field, so the text
   * can't land in another one. A selector naming the field's label, or a hint that focusing hides,
   * types into the field the tap focused. When null, types into the focused field, as recorded
   * trails always have.
   *
   * Hidden from the LLM with every other selector param: it picks fields by `tap` ref.
   */
  @param:LLMDescription("Tap this field first, then type into it. Omit to type into the focused field.")
  val selector: TrailblazeNodeSelector? = null,
) : ExecutableTrailblazeTool, ReasoningTrailblazeTool {

  override suspend fun execute(toolExecutionContext: TrailblazeToolExecutionContext): TrailblazeToolResult {
    // {{var}}/${var} tokens are resolved by the dispatch boundary (interpolateMemoryInTool)
    // before execute() runs, so `text` arrives resolved here.
    if (selector != null) {
      // Tap first, as the tapOnElementBySelector step this replaces did.
      val tapResult = TapOnByElementSelector(reason = reasoning, nodeSelector = selector)
        .execute(toolExecutionContext)
      if (!tapResult.isSuccess()) return tapResult
      // A driver that can focus the field directly types there; the rest type into what the tap focused.
      toolExecutionContext.maestroTrailblazeAgent
        ?.executeNodeSelectorInputText(selector, text, hideKeyboardAfter, toolExecutionContext.traceId)
        ?.let { return it }
    }
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
