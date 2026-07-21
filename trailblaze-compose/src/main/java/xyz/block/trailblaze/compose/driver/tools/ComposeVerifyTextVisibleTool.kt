package xyz.block.trailblaze.compose.driver.tools

import ai.koog.agents.core.tools.annotations.LLMDescription
import androidx.compose.ui.test.hasText
import kotlinx.serialization.Serializable
import xyz.block.trailblaze.compose.target.ComposeTestTarget
import xyz.block.trailblaze.toolcalls.TrailblazeToolClass
import xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult
import xyz.block.trailblaze.util.Console

@Serializable
@TrailblazeToolClass("compose_verify_text_visible", isVerification = true)
@LLMDescription(
  """
Verify that specific text is visible on screen.
This is a test assertion — it will fail if the text is not found.
""",
)
data class ComposeVerifyTextVisibleTool(
  @param:LLMDescription("The text content to verify is visible.")
  val text: String,
) : ComposeExecutableTool {

  override suspend fun executeWithCompose(
    target: ComposeTestTarget,
    context: TrailblazeToolExecutionContext,
  ): TrailblazeToolResult {
    // {{var}}/${var} tokens are resolved by the dispatch boundary (interpolateMemoryInTool)
    // before execution, so `text` arrives resolved here.
    Console.log("### Verifying text visible: $text")
    return try {
      val matcher = hasText(text, substring = false)
      val nodes = ComposeExecutableTool.findNodes(target, matcher)
      if (nodes.isEmpty()) {
        TrailblazeToolResult.Error.ExceptionThrown(
          "Assertion failed: text '$text' is not visible in the Compose UI."
        )
      } else {
        TrailblazeToolResult.Success(message = "Verified text '$text' is visible.")
      }
    } catch (e: Exception) {
      TrailblazeToolResult.Error.ExceptionThrown("Verify text failed: ${e.message}")
    }
  }
}
