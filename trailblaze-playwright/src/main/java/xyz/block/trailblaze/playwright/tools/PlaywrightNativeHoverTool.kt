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
@TrailblazeToolClass("web_hover")
@LLMDescription("Hover over a web element, e.g. to open a tooltip or hover menu.")
data class PlaywrightNativeHoverTool(
  @param:LLMDescription("Element ID ('e5'), ARIA descriptor ('link \"About\"'), or 'css=<selector>'.")
  val ref: String? = null,
  override val reasoning: String? = null,
  val nodeSelector: TrailblazeNodeSelector? = null,
) : PlaywrightExecutableTool, ReasoningTrailblazeTool {
  override val targetRef: String? get() = ref
  override val targetNodeSelector: TrailblazeNodeSelector? get() = nodeSelector
  override fun withNodeSelector(selector: TrailblazeNodeSelector): PlaywrightExecutableTool =
    PlaywrightNativeHoverTool(ref = null, reasoning = reasoning, nodeSelector = selector)

  override suspend fun executeWithPlaywright(
    page: Page,
    context: TrailblazeToolExecutionContext,
  ): TrailblazeToolResult {
    val description = PlaywrightExecutableTool.describeTarget(nodeSelector, ref)
    reasoning?.let { Console.log("### Reasoning: $it") }
    Console.log("### Hovering over: $description")
    return try {
      val (locator, error) =
        PlaywrightExecutableTool.validateAndResolveRef(page, ref, description, context, nodeSelector)
      if (error != null) return error
      locator!!.hover()
      TrailblazeToolResult.Success(message = "Hovered over '$description'.")
    } catch (e: Exception) {
      TrailblazeToolResult.Error.ExceptionThrown("Hover failed on '$description': ${e.message}")
    }
  }
}
