package xyz.block.trailblaze.toolcalls.commands.memory

import ai.koog.agents.core.tools.annotations.LLMDescription
import kotlinx.serialization.Serializable
import xyz.block.trailblaze.api.TrailblazeNodeSelector
import xyz.block.trailblaze.toolcalls.ExecutableTrailblazeTool
import xyz.block.trailblaze.toolcalls.ReadOnlyTrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolClass
import xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult
import xyz.block.trailblaze.utils.parseNumberString

@Serializable
@TrailblazeToolClass(
  name = "rememberNumberBySelector",
  surfaceToLlm = false,
)
@LLMDescription(
  "Captures the number in the text of the element matching a selector into a memory variable, " +
    "with no LLM call.",
)
/**
 * ----- DO NOT GIVE THIS TOOL TO THE LLM -----
 *
 * The deterministic counterpart to [RememberNumberTrailblazeTool], and the numeric sibling of
 * [RememberTextBySelectorTrailblazeTool]: it captures the same selector-resolved text, then keeps
 * only the first number in it (via [parseNumberString], the same extraction `rememberNumber` uses),
 * so `$42.50` stores as `42.50` and stays comparable by `assertMath` / `assertEquals`.
 */
data class RememberNumberBySelectorTrailblazeTool(
  val reason: String? = null,
  /** Selector for the element whose number is captured. Required — [execute] enforces non-null. */
  val nodeSelector: TrailblazeNodeSelector? = null,
  /** Memory variable name the parsed number is stored under. */
  val variable: String,
) : ExecutableTrailblazeTool, ReadOnlyTrailblazeTool {

  override suspend fun execute(
    toolExecutionContext: TrailblazeToolExecutionContext,
  ): TrailblazeToolResult = when (
    val capture = captureSelectorText(TOOL_NAME, toolExecutionContext, nodeSelector)
  ) {
    is SelectorTextCapture.Failed -> capture.error
    is SelectorTextCapture.Captured -> {
      val number = parseNumberString(capture.text)
      if (number == null) {
        TrailblazeToolResult.Error.ExceptionThrown(
          errorMessage = "$TOOL_NAME: no number found in the matched element's text " +
            "'${capture.text}'.",
        )
      } else {
        toolExecutionContext.memory.remember(variable, number)
        TrailblazeToolResult.Success(
          message = "$TOOL_NAME: remembered $variable = " +
            renderCaptured(toolExecutionContext, variable, number),
        )
      }
    }
  }
}

private const val TOOL_NAME = "rememberNumberBySelector"
