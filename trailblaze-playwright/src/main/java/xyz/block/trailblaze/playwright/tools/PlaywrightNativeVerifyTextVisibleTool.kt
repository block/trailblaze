package xyz.block.trailblaze.playwright.tools

import ai.koog.agents.core.tools.annotations.LLMDescription
import com.microsoft.playwright.Page
import com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat
import kotlinx.serialization.Serializable
import xyz.block.trailblaze.toolcalls.ReasoningTrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolClass
import xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult
import xyz.block.trailblaze.util.Console

@Serializable
@TrailblazeToolClass("web_verify_text_visible")
@LLMDescription(
  """
Verify that specific text is visible on the current page.
This is a test assertion — it will fail the test if the text is not found.
""",
)
class PlaywrightNativeVerifyTextVisibleTool(
  @param:LLMDescription("The text content to verify is visible on the page.")
  val text: String,
  override val reasoning: String? = null,
) : PlaywrightExecutableTool, ReasoningTrailblazeTool {

  override suspend fun executeWithPlaywright(
    page: Page,
    context: TrailblazeToolExecutionContext,
  ): TrailblazeToolResult {
    reasoning?.let { Console.log("### Reasoning: $it") }
    Console.log("### Verifying text visible: $text")
    return try {
      val locator = page.getByText(text)
      assertThat(locator.first()).isVisible()
      TrailblazeToolResult.Success(message = "Verified text '$text' is visible on the page.")
    } catch (e: AssertionError) {
      TrailblazeToolResult.Error.ExceptionThrown(
        "Assertion failed: text '$text' is not visible on the page.",
      )
    } catch (e: Exception) {
      TrailblazeToolResult.Error.ExceptionThrown("Verify text failed: ${e.message}")
    }
  }
}
