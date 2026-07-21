package xyz.block.trailblaze.playwright.tools

import ai.koog.agents.core.tools.annotations.LLMDescription
import com.microsoft.playwright.BrowserContext.StorageStateOptions
import com.microsoft.playwright.Page
import kotlinx.serialization.Serializable
import xyz.block.trailblaze.toolcalls.TrailblazeToolClass
import xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult

@Serializable
@TrailblazeToolClass("web_getStorageState")
@LLMDescription(
  """
Returns the current browser context's storage state (cookies + per-origin localStorage) as a
JSON string in Playwright's standard format. Callers typically persist this to disk so a
later session can be replayed via web_applyCookies instead of running a full login flow.
""",
)
object PlaywrightNativeGetStorageStateTool : PlaywrightExecutableTool {

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
