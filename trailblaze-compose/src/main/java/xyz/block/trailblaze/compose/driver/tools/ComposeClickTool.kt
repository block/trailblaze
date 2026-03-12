package xyz.block.trailblaze.compose.driver.tools

import ai.koog.agents.core.tools.annotations.LLMDescription
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.performClick
import kotlinx.serialization.Serializable
import xyz.block.trailblaze.toolcalls.TrailblazeToolClass
import xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult
import xyz.block.trailblaze.util.Console

@OptIn(ExperimentalTestApi::class)
@Serializable
@TrailblazeToolClass("compose_click")
@LLMDescription(
  """
Click on a UI element.
Identify the element using its element ID from the view hierarchy (e.g., 'e5'),
or by text content.
""",
)
class ComposeClickTool(
  @param:LLMDescription("Element ID from the view hierarchy, e.g., 'e5'. Preferred method.")
  val elementId: String? = null,
  @param:LLMDescription("Accessibility identifier of the element to click.")
  val testTag: String? = null,
  @param:LLMDescription("The text content of the element to click.")
  val text: String? = null,
  @param:LLMDescription("Human-readable description of the element being clicked, for logging.")
  val element: String = "",
) : ComposeExecutableTool {

  override suspend fun executeWithCompose(
    composeUiTest: ComposeUiTest,
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
      if (nthIndex > 0) {
        composeUiTest.onAllNodes(matcher).get(nthIndex).performClick()
      } else {
        composeUiTest.onNode(matcher).performClick()
      }
      composeUiTest.waitForIdle()
      TrailblazeToolResult.Success(message = "Clicked on '$description'.")
    } catch (e: Exception) {
      TrailblazeToolResult.Error.ExceptionThrown("Click failed on '$description': ${e.message}")
    }
  }
}
