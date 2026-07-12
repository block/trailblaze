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
   * Whether to dismiss the soft keyboard after typing. Default `true` preserves the
   * batch-trail-run / LLM-driven behavior: each `inputText` step is self-contained and
   * leaves the device ready for the next step (next tap can land cleanly without the
   * keyboard occluding the target).
   *
   * **Pass `false` from interactive / live-forwarding paths** (the wasm `/devices` viewer's
   * per-keystroke flush). The user is still typing — they don't want the keyboard
   * dismissed after every word. And on the accessibility driver the daemon's `inputText`
   * routes through `ACTION_SET_TEXT` directly on the focused node, which sidesteps the
   * soft IME entirely on the happy path (no synthesized key events ever bring up a
   * keyboard window). With no soft keyboard up, `HideKeyboardCommand` falls through to
   * a `BACK` keycode that navigates the current activity backwards instead. That's how
   * "typing 'sam' navigated away from the screen" reproduced — Sam's repro on PR #3021
   * caught it.
   *
   * Recorded trail YAMLs continue to omit this field, so replay keeps the existing
   * dismiss-keyboard behavior intact. Only direct in-process callers that pass `false`
   * see the change.
   */
  val hideKeyboardAfter: Boolean = true,
) : ExecutableTrailblazeTool, ReasoningTrailblazeTool {

  override suspend fun execute(toolExecutionContext: TrailblazeToolExecutionContext): TrailblazeToolResult {
    // {{var}}/${var} tokens are resolved by the dispatch boundary (interpolateMemoryInTool)
    // before execute() runs, so `text` arrives resolved here.
    val maestroCommands = if (hideKeyboardAfter) {
      listOf(InputTextCommand(text)) +
        HideKeyboardTrailblazeTool.hideKeyboardCommands(
          platform = toolExecutionContext.screenState?.trailblazeDevicePlatform,
          orientation = toolExecutionContext.trailblazeDeviceInfo.orientation,
        )
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
