package xyz.block.trailblaze.playwright.tools

import ai.koog.agents.core.tools.annotations.LLMDescription
import com.microsoft.playwright.Page
import kotlinx.serialization.Serializable
import xyz.block.trailblaze.toolcalls.TrailblazeToolClass
import xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult

@Serializable
@TrailblazeToolClass("web_currentUrl")
@LLMDescription("Return the current page URL, e.g. to see where a redirect landed.")
object PlaywrightNativeCurrentUrlTool : PlaywrightExecutableTool {

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
