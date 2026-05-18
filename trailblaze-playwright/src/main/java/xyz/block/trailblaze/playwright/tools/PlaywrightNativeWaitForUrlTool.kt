package xyz.block.trailblaze.playwright.tools

import ai.koog.agents.core.tools.annotations.LLMDescription
import com.microsoft.playwright.Page
import java.util.regex.Pattern
import kotlinx.serialization.Serializable
import xyz.block.trailblaze.toolcalls.TrailblazeToolClass
import xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult

/**
 * Waits for the current page's URL to match [pattern] (Java regex) within [timeoutMs].
 * Thin wrapper over `Page.waitForURL(Pattern, ...)` — useful in scripted tools that need
 * to assert a navigation landed on the expected page (e.g. post-login dashboard redirect
 * landing on `/dashboard|/home|/orders`).
 *
 * Returns the actual URL once the wait completes so callers can branch on it (e.g. detect
 * a `/login` bounce after a stale-cookie replay).
 */
@Serializable
@TrailblazeToolClass("web_waitForUrl")
@LLMDescription(
  """
Wait until the current page's URL matches the given regex pattern. Returns the matched URL.
Use after web_navigate or web_click when the navigation is async and the next step depends
on the final URL (e.g. waiting for a post-login redirect to settle).
""",
)
data class PlaywrightNativeWaitForUrlTool(
  @param:LLMDescription(
    "Java regex pattern the URL must match (e.g. \".*(dashboard|home|orders).*\").",
  )
  val pattern: String,
  @param:LLMDescription(
    "Maximum time to wait in milliseconds. Defaults to 30000ms.",
  )
  val timeoutMs: Long = 30_000,
) : PlaywrightExecutableTool {

  override suspend fun executeWithPlaywright(
    page: Page,
    context: TrailblazeToolExecutionContext,
  ): TrailblazeToolResult =
    try {
      page.waitForURL(
        Pattern.compile(pattern),
        Page.WaitForURLOptions().setTimeout(timeoutMs.toDouble()),
      )
      TrailblazeToolResult.Success(message = page.url())
    } catch (e: Exception) {
      TrailblazeToolResult.Error.ExceptionThrown(
        "web_waitForUrl timed out (pattern='$pattern', timeoutMs=$timeoutMs): ${e.message}"
      )
    }
}
