package xyz.block.trailblaze.compose.driver.tools

import ai.koog.agents.core.tools.annotations.LLMDescription
import androidx.compose.ui.test.hasScrollAction
import kotlinx.serialization.Serializable
import xyz.block.trailblaze.compose.target.ComposeTestTarget
import xyz.block.trailblaze.toolcalls.TrailblazeToolClass
import xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult
import xyz.block.trailblaze.util.Console

@Serializable
@TrailblazeToolClass("compose_scroll")
@LLMDescription(
  """
Scroll a container to an item index. Identify the container by elementId, testTag, or text; omit all to use the first scrollable container.
""",
)
data class ComposeScrollTool(
  @param:LLMDescription("Container element ID, e.g. 'e2'.")
  val elementId: String? = null,
  @param:LLMDescription("Container testTag.")
  val testTag: String? = null,
  @param:LLMDescription("Container text, exact whole-text match.")
  val text: String? = null,
  @param:LLMDescription("Item index to scroll to (default 0).")
  val index: Int = 0,
) : ComposeExecutableTool {

  override suspend fun executeWithCompose(
    target: ComposeTestTarget,
    context: TrailblazeToolExecutionContext,
  ): TrailblazeToolResult {
    val description = elementId ?: testTag ?: text ?: "scrollable container"
    Console.log("### Scrolling $description to index $index")
    return try {
      val matcher =
        ComposeExecutableTool.resolveElement(elementId, testTag, text, context)
          ?: hasScrollAction()
      val nthIndex = ComposeExecutableTool.getNthIndex(elementId, context)
      val node = ComposeExecutableTool.findNode(target, matcher, nthIndex)
      target.dispatchAndAwaitSettle { target.scrollToIndex(node, index) }
      TrailblazeToolResult.Success(message = "Scrolled '$description' to index $index.")
    } catch (e: Exception) {
      TrailblazeToolResult.Error.ExceptionThrown("Scroll failed on '$description': ${e.message}")
    }
  }
}
