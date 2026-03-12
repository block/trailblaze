package xyz.block.trailblaze.playwright.tools

import ai.koog.agents.core.tools.annotations.LLMDescription
import com.microsoft.playwright.Page
import kotlinx.serialization.Serializable
import xyz.block.trailblaze.toolcalls.ReasoningTrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolClass
import xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult
import xyz.block.trailblaze.util.Console

/**
 * Custom tool that verifies the Goose desktop Electron app is launched and connected
 * via Playwright CDP.
 *
 * When a trail uses `driver: PLAYWRIGHT_ELECTRON` with an `electron.command` pointing
 * to Goose, the framework handles launching the app and connecting via CDP before
 * any trail steps execute. This tool verifies that connection is live and returns
 * the current page state so the LLM knows the app is ready for interaction.
 */
@Serializable
@TrailblazeToolClass("playwright_desktop_launchGoose")
@LLMDescription(
  """
Launch and connect to the Goose desktop application.
Verifies that the Goose Electron app is running and accessible via Playwright.
Call this tool as the first step when testing the Goose desktop app.
Returns the current page URL and title confirming the app is ready for interaction.
""",
)
class PlaywrightDesktopLaunchGooseTool(
  override val reasoning: String? = null,
) : PlaywrightExecutableTool, ReasoningTrailblazeTool {

  override suspend fun executeWithPlaywright(
    page: Page,
    context: TrailblazeToolExecutionContext,
  ): TrailblazeToolResult {
    reasoning?.let { Console.log("### Reasoning: $it") }
    Console.log("### Verifying Goose desktop app is running...")

    return try {
      val url = page.url()
      val title = page.title()
      val message = buildString {
        append("Goose desktop app is running and connected via Playwright.")
        append(" Current page: '$title' at $url")
      }
      Console.log("### $message")
      TrailblazeToolResult.Success(message = message)
    } catch (e: Exception) {
      TrailblazeToolResult.Error.ExceptionThrown(
        "Failed to connect to Goose desktop app: ${e.message}"
      )
    }
  }
}
