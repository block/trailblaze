package xyz.block.trailblaze.playwright.tools

import ai.koog.agents.core.tools.annotations.LLMDescription
import com.microsoft.playwright.Page
import kotlinx.serialization.Serializable
import xyz.block.trailblaze.toolcalls.TrailblazeToolClass
import xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult

/**
 * Executes an arbitrary JavaScript expression in the current page's context and returns the
 * result, stringified. Thin wrapper over `Page.evaluate(...)`.
 *
 * Intentionally NOT in any LLM-facing toolset by default — arbitrary `eval`-in-page is a
 * sharp tool. Scripted tools call it via `client.callTool("web_evaluate", ...)` directly,
 * which bypasses toolset filtering. Pack authors who want to expose it to the LLM must add
 * it to a toolset explicitly.
 */
@Serializable
@TrailblazeToolClass("web_evaluate")
@LLMDescription(
  """
Executes a JavaScript expression in the current page context and returns the result as a
string. Useful for reaching into in-page globals (e.g. analytics SDKs, feature flags) that
aren't exposed via the DOM. Scripted-tool only by default.
""",
)
data class PlaywrightNativeEvaluateTool(
  @param:LLMDescription(
    "JavaScript expression or IIFE. Use `(() => { ... })()` shape if you need statements.",
  )
  val script: String,
) : PlaywrightExecutableTool {

  override suspend fun executeWithPlaywright(
    page: Page,
    context: TrailblazeToolExecutionContext,
  ): TrailblazeToolResult =
    try {
      val result = page.evaluate(script)
      TrailblazeToolResult.Success(message = result?.toString() ?: "")
    } catch (e: Exception) {
      TrailblazeToolResult.Error.ExceptionThrown("web_evaluate failed: ${e.message}")
    }
}
