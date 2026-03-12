package xyz.block.trailblaze.compose.driver.tools

import ai.koog.agents.core.tools.annotations.LLMDescription
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.performScrollToIndex
import kotlinx.serialization.Serializable
import xyz.block.trailblaze.toolcalls.TrailblazeToolClass
import xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult
import xyz.block.trailblaze.util.Console

@OptIn(ExperimentalTestApi::class)
@Serializable
@TrailblazeToolClass("compose_scroll")
@LLMDescription(
  """
Scroll a scrollable container to bring content into view.
Identify the container using its element ID or text.
Leave all identifiers empty to scroll the first scrollable container found.
""",
)
class ComposeScrollTool(
  @param:LLMDescription("Element ID from the view hierarchy, e.g., 'e2'.")
  val elementId: String? = null,
  @param:LLMDescription("Accessibility identifier of the scrollable container.")
  val testTag: String? = null,
  @param:LLMDescription("The text content to scroll towards.")
  val text: String? = null,
  @param:LLMDescription("Index to scroll to within the scrollable container. Defaults to 0.")
  val index: Int = 0,
) : ComposeExecutableTool {

  override suspend fun executeWithCompose(
    composeUiTest: ComposeUiTest,
    context: TrailblazeToolExecutionContext,
  ): TrailblazeToolResult {
    val description = elementId ?: testTag ?: text ?: "scrollable container"
    Console.log("### Scrolling $description to index $index")
    return try {
      val matcher =
        ComposeExecutableTool.resolveElement(elementId, testTag, text, context)
          ?: hasScrollAction()
      val nthIndex = ComposeExecutableTool.getNthIndex(elementId, context)
      if (nthIndex > 0) {
        composeUiTest.onAllNodes(matcher).get(nthIndex).performScrollToIndex(index)
      } else {
        composeUiTest.onNode(matcher).performScrollToIndex(index)
      }
      composeUiTest.waitForIdle()
      TrailblazeToolResult.Success(message = "Scrolled '$description' to index $index.")
    } catch (e: Exception) {
      TrailblazeToolResult.Error.ExceptionThrown("Scroll failed on '$description': ${e.message}")
    }
  }
}
