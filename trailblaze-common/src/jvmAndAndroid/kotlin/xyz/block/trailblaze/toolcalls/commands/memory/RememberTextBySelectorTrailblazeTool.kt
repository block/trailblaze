package xyz.block.trailblaze.toolcalls.commands.memory

import ai.koog.agents.core.tools.annotations.LLMDescription
import kotlinx.serialization.Serializable
import xyz.block.trailblaze.api.TrailblazeNodeSelector
import xyz.block.trailblaze.toolcalls.ExecutableTrailblazeTool
import xyz.block.trailblaze.toolcalls.ReadOnlyTrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolClass
import xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult

@Serializable
@TrailblazeToolClass(
  name = "rememberTextBySelector",
  surfaceToLlm = false,
)
@LLMDescription(
  "Captures the text of the element matching a selector into a memory variable, with no LLM call.",
)
/**
 * ----- DO NOT GIVE THIS TOOL TO THE LLM -----
 *
 * The deterministic counterpart to [RememberTextTrailblazeTool]. Where `rememberText` describes the
 * element in natural language and spends an LLM call to pick a locator, this names the element with
 * the same [TrailblazeNodeSelector] grammar `assertVisibleBySelector` / `findMatches` use and reads
 * its text straight off the captured tree. Zero LLM calls, so a capture replays on a
 * recording-only leg and on iOS, where the prompt path's locator step fails outright.
 *
 * `rememberText` is unchanged and still the right tool when the value can only be described, not
 * selected.
 */
data class RememberTextBySelectorTrailblazeTool(
  val reason: String? = null,
  /** Selector for the element whose text is captured. Required — [execute] enforces non-null. */
  val nodeSelector: TrailblazeNodeSelector? = null,
  /** Memory variable name the captured text is stored under. */
  val variable: String,
) : ExecutableTrailblazeTool, ReadOnlyTrailblazeTool {

  override suspend fun execute(
    toolExecutionContext: TrailblazeToolExecutionContext,
  ): TrailblazeToolResult = when (
    val capture = captureSelectorText(TOOL_NAME, toolExecutionContext, nodeSelector)
  ) {
    is SelectorTextCapture.Failed -> capture.error
    is SelectorTextCapture.Captured -> {
      toolExecutionContext.memory.remember(variable, capture.text)
      TrailblazeToolResult.Success(
        message = "$TOOL_NAME: remembered $variable = " +
          renderCaptured(toolExecutionContext, variable, capture.text),
      )
    }
  }
}

private const val TOOL_NAME = "rememberTextBySelector"
