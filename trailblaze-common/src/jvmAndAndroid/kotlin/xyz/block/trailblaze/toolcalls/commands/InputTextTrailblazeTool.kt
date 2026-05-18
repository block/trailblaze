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
  /**
   * Whether to dismiss the soft keyboard after typing. Default `true` preserves the
   * batch-trail-run / LLM-driven behavior: each `inputText` step is self-contained and
   * leaves the device ready for the next step (next tap can land cleanly without the
   * keyboard occluding the target).
   *
   * **Pass `false` from interactive / live-forwarding paths** (the wasm `/devices` viewer's
   * per-keystroke flush). The user is still typing — they don't want the keyboard
   * dismissed after every word. And with the daemon's `AndroidSoftKeyboardSuppressor`
   * active, there is no soft keyboard to hide; `HideKeyboardCommand` on Android falls
   * through to a `BACK` keycode that navigates the current activity backwards instead.
   * That's how "typing 'sam' navigated away from the screen" reproduced — Sam's repro on
   * PR #3021 caught it.
   *
   * Recorded trail YAMLs continue to omit this field, so replay keeps the existing
   * dismiss-keyboard behavior intact. Only direct in-process callers that pass `false`
   * see the change.
   */
  val hideKeyboardAfter: Boolean = true,
) : ExecutableTrailblazeTool, ReasoningTrailblazeTool {

  override suspend fun execute(toolExecutionContext: TrailblazeToolExecutionContext): TrailblazeToolResult {
    val interpolated = toolExecutionContext.memory.interpolateVariables(text)
    val maestroCommands = if (hideKeyboardAfter) {
      listOf(InputTextCommand(interpolated)) +
        HideKeyboardTrailblazeTool.hideKeyboardCommands(
          platform = toolExecutionContext.screenState?.trailblazeDevicePlatform,
          orientation = toolExecutionContext.trailblazeDeviceInfo.orientation,
        )
    } else {
      listOf(InputTextCommand(interpolated))
    }
    val result = toolExecutionContext.trailblazeAgent.runMaestroCommands(
      maestroCommands = maestroCommands,
      traceId = toolExecutionContext.traceId,
    )
    if (result.isSuccess()) return TrailblazeToolResult.Success(message = "Typed '$interpolated'")
    return result
  }
}
