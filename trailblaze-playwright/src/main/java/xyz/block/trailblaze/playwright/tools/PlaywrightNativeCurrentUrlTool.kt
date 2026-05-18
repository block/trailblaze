package xyz.block.trailblaze.playwright.tools

import ai.koog.agents.core.tools.annotations.LLMDescription
import com.microsoft.playwright.Page
import kotlinx.serialization.Serializable
import xyz.block.trailblaze.toolcalls.TrailblazeToolClass
import xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult

@Serializable
@TrailblazeToolClass("web_currentUrl")
@LLMDescription(
  """
Returns the current page's URL as a plain string. Useful for asserting where a navigation
ended up after redirects (e.g. detecting a bounce to `/login` when replaying an expired
session).
""",
)
class PlaywrightNativeCurrentUrlTool : PlaywrightExecutableTool {

  override suspend fun executeWithPlaywright(
    page: Page,
    context: TrailblazeToolExecutionContext,
  ): TrailblazeToolResult =
    try {
      TrailblazeToolResult.Success(message = page.url())
    } catch (e: Exception) {
      TrailblazeToolResult.Error.ExceptionThrown("web_currentUrl failed: ${e.message}")
    }
}
