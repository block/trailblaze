package xyz.block.trailblaze.playwright.tools

import ai.koog.agents.core.tools.annotations.LLMDescription
import com.microsoft.playwright.BrowserContext.StorageStateOptions
import com.microsoft.playwright.Page
import kotlinx.serialization.Serializable
import xyz.block.trailblaze.history.SecretResultTrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolClass
import xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult

@Serializable
@TrailblazeToolClass("web_getStorageState")
@LLMDescription(
  """
Return the browser's storage state (cookies + localStorage) as Playwright storageState JSON. web_applyCookies restores only its cookies.
""",
)
// The result is a signed-in session, so `ctx.history` keeps the call but masks what it returned.
object PlaywrightNativeGetStorageStateTool : PlaywrightExecutableTool, SecretResultTrailblazeTool {

  override suspend fun executeWithPlaywright(
    page: Page,
    context: TrailblazeToolExecutionContext,
  ): TrailblazeToolResult =
    try {
      val json = page.context().storageState(StorageStateOptions())
      TrailblazeToolResult.Success(message = json)
    } catch (e: Exception) {
      TrailblazeToolResult.Error.ExceptionThrown("web_getStorageState failed: ${e.message}")
    }
}
