package xyz.block.trailblaze.compose.driver.tools

import ai.koog.agents.core.tools.annotations.LLMDescription
import kotlinx.serialization.Serializable
import xyz.block.trailblaze.compose.target.ComposeTestTarget
import xyz.block.trailblaze.toolcalls.TrailblazeToolClass
import xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult
import xyz.block.trailblaze.util.Console

@Serializable
@TrailblazeToolClass("compose_click")
@LLMDescription("Click an element, identified by elementId (preferred), testTag, or text.")
data class ComposeClickTool(
  @param:LLMDescription("Element ID, e.g. 'e5'.")
  val elementId: String? = null,
  @param:LLMDescription("Element testTag.")
  val testTag: String? = null,
  @param:LLMDescription("Element text, exact whole-text match.")
  val text: String? = null,
  @param:LLMDescription("Short description of the element, for logs.")
  val element: String = "",
) : ComposeExecutableTool {

  override suspend fun executeWithCompose(
    target: ComposeTestTarget,
    context: TrailblazeToolExecutionContext,
  ): TrailblazeToolResult {
    val description = element.ifBlank { elementId ?: testTag ?: text ?: "unknown" }
    Console.log("### Clicking on: $description")
    return try {
      val matcher =
        ComposeExecutableTool.resolveElement(elementId, testTag, text, context)
          ?: return TrailblazeToolResult.Error.ExceptionThrown(
            "Must provide elementId, testTag, or text to identify the element."
          )
      val nthIndex = ComposeExecutableTool.getNthIndex(elementId, context)
      val node = ComposeExecutableTool.findNode(target, matcher, nthIndex)
      target.dispatchAndAwaitSettle { target.click(node) }
      TrailblazeToolResult.Success(message = "Clicked on '$description'.")
    } catch (e: Exception) {
      TrailblazeToolResult.Error.ExceptionThrown("Click failed on '$description': ${e.message}")
    }
  }
}
