package xyz.block.trailblaze.playwright.tools

import ai.koog.agents.core.tools.annotations.LLMDescription
import com.microsoft.playwright.Page
import kotlinx.serialization.Serializable
import xyz.block.trailblaze.api.TrailblazeNodeSelector
import xyz.block.trailblaze.toolcalls.ReasoningTrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolClass
import xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult
import xyz.block.trailblaze.util.Console

@Serializable
@TrailblazeToolClass("web_type")
@LLMDescription("Type text into a web input. Clears the field first unless clearFirst is false.")
data class PlaywrightNativeTypeTool(
  @param:LLMDescription("Text to type.") val text: String,
  @param:LLMDescription("Element ID ('e5'), ARIA descriptor ('textbox \"Email\"'), or 'css=<selector>'.")
  val ref: String? = null,
  @param:LLMDescription("Clear the field before typing (default true); false appends.")
  val clearFirst: Boolean = true,
  override val reasoning: String? = null,
  val nodeSelector: TrailblazeNodeSelector? = null,
) : PlaywrightExecutableTool, ReasoningTrailblazeTool {
  override val targetRef: String? get() = ref
  override val targetNodeSelector: TrailblazeNodeSelector? get() = nodeSelector
  override fun withNodeSelector(selector: TrailblazeNodeSelector): PlaywrightExecutableTool =
    PlaywrightNativeTypeTool(text = text, ref = null, clearFirst = clearFirst, reasoning = reasoning, nodeSelector = selector)

  override suspend fun executeWithPlaywright(
    page: Page,
    context: TrailblazeToolExecutionContext,
  ): TrailblazeToolResult {
    // {{var}}/${var} tokens are resolved by the dispatch boundary (interpolateMemoryInTool)
    // before execution, so `text` arrives resolved here.
    val description = PlaywrightExecutableTool.describeTarget(nodeSelector, ref)
    reasoning?.let { Console.log("### Reasoning: $it") }
    Console.log("### Typing into $description: $text")
    return try {
      val (locator, error) =
        PlaywrightExecutableTool.validateAndResolveRef(page, ref, description, context, nodeSelector)
      if (error != null) return error
      if (clearFirst) {
        locator!!.fill(text)
      } else {
        locator!!.pressSequentially(text)
      }
      val action = if (clearFirst) "Filled" else "Typed"
      TrailblazeToolResult.Success(message = "$action '$text' into '$description'.")
    } catch (e: Exception) {
      TrailblazeToolResult.Error.ExceptionThrown("Type failed on '$description': ${e.message}")
    }
  }
}
