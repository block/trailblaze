package xyz.block.trailblaze.compose.driver.tools

import ai.koog.agents.core.tools.annotations.LLMDescription
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import kotlinx.serialization.Serializable
import xyz.block.trailblaze.toolcalls.TrailblazeToolClass
import xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult
import xyz.block.trailblaze.util.Console

@OptIn(ExperimentalTestApi::class)
@Serializable
@TrailblazeToolClass("compose_verify_text_visible")
@LLMDescription(
  """
Verify that specific text is visible on screen.
This is a test assertion — it will fail if the text is not found.
""",
)
class ComposeVerifyTextVisibleTool(
  @param:LLMDescription("The text content to verify is visible.")
  val text: String,
) : ComposeExecutableTool {

  override suspend fun executeWithCompose(
    composeUiTest: ComposeUiTest,
    context: TrailblazeToolExecutionContext,
  ): TrailblazeToolResult {
    val interpolatedText = context.memory.interpolateVariables(text)
    Console.log("### Verifying text visible: $interpolatedText")
    return try {
      val nodes =
        composeUiTest.onAllNodes(hasText(interpolatedText, substring = false)).fetchSemanticsNodes()
      if (nodes.isEmpty()) {
        TrailblazeToolResult.Error.ExceptionThrown(
          "Assertion failed: text '$interpolatedText' is not visible in the Compose UI."
        )
      } else {
        TrailblazeToolResult.Success(message = "Verified text '$interpolatedText' is visible.")
      }
    } catch (e: Exception) {
      TrailblazeToolResult.Error.ExceptionThrown("Verify text failed: ${e.message}")
    }
  }
}
