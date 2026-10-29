package xyz.block.trailblaze.playwright.tools

import ai.koog.agents.core.tools.annotations.LLMDescription
import com.microsoft.playwright.Page
import com.microsoft.playwright.options.ViewportSize
import kotlinx.serialization.Serializable
import xyz.block.trailblaze.toolcalls.ReasoningTrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolClass
import xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult
import xyz.block.trailblaze.util.Console

/**
 * Resizes the active page's viewport. Mirrors playwright-mcp's `browser_resize`
 * tool 1:1 — takes two numeric dimensions and calls Playwright's
 * [Page.setViewportSize].
 *
 * **Scope and limitation: viewport box ONLY.** Does not change `User-Agent`,
 * `deviceScaleFactor`, `isMobile`, or `hasTouch` — those are baked into the
 * [com.microsoft.playwright.BrowserContext] at construction time and can't be
 * swapped without recreating the context (which would drop cookies / localStorage
 * / current URL). For a full mobile-emulation profile (server-side UA sniffing,
 * mobile CSS variants, touch events), provision the device slot up front with
 * `trailblaze device create web --emulate "iPhone 15"` instead.
 *
 * Practical use case: stress-test your responsive CSS at different breakpoints
 * within one session, without losing page state.
 */
@Serializable
@TrailblazeToolClass("web_resize")
@LLMDescription(
  """
Resize the browser viewport, e.g. to test responsive breakpoints. Does not change the User-Agent or enable mobile emulation.
""",
)
data class PlaywrightNativeResizeTool(
  @param:LLMDescription("Viewport width in CSS pixels.")
  val width: Int,
  @param:LLMDescription("Viewport height in CSS pixels.")
  val height: Int,
  override val reasoning: String? = null,
) : PlaywrightExecutableTool, ReasoningTrailblazeTool {

  override suspend fun executeWithPlaywright(
    page: Page,
    context: TrailblazeToolExecutionContext,
  ): TrailblazeToolResult {
    reasoning?.let { Console.log("### Reasoning: $it") }
    if (width <= 0 || height <= 0) {
      return TrailblazeToolResult.Error.ExceptionThrown(
        "web_resize requires positive width and height (got ${width}x${height}).",
      )
    }
    Console.log("### Resizing viewport to ${width}x$height")
    return try {
      page.setViewportSize(width, height)
      val applied: ViewportSize? = page.viewportSize()
      val appliedDesc = applied?.let { "${it.width}x${it.height}" } ?: "${width}x$height"
      TrailblazeToolResult.Success(message = "Resized viewport to $appliedDesc.")
    } catch (e: Exception) {
      TrailblazeToolResult.Error.ExceptionThrown("Resize failed: ${e.message}")
    }
  }
}
