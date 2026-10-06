package xyz.block.trailblaze.playwright.tools

import ai.koog.agents.core.tools.annotations.LLMDescription
import com.microsoft.playwright.Locator
import com.microsoft.playwright.Page
import com.microsoft.playwright.Request
import com.microsoft.playwright.TimeoutError
import java.util.function.Consumer
import kotlinx.serialization.Serializable
import xyz.block.trailblaze.api.TrailblazeNodeSelector
import xyz.block.trailblaze.toolcalls.ReasoningTrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolClass
import xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult
import xyz.block.trailblaze.util.Console

@Serializable
@TrailblazeToolClass("web_click")
@LLMDescription("Click a web element.")
data class PlaywrightNativeClickTool(
  @param:LLMDescription("Element ID ('e5'), ARIA descriptor ('button \"Submit\"'), or 'css=<selector>'.")
  val ref: String? = null,
  override val reasoning: String? = null,
  val nodeSelector: TrailblazeNodeSelector? = null,
) : PlaywrightExecutableTool, ReasoningTrailblazeTool {
  override val targetRef: String? get() = ref
  override val targetNodeSelector: TrailblazeNodeSelector? get() = nodeSelector
  override fun withNodeSelector(selector: TrailblazeNodeSelector): PlaywrightExecutableTool =
    PlaywrightNativeClickTool(ref = null, reasoning = reasoning, nodeSelector = selector)

  override suspend fun executeWithPlaywright(
    page: Page,
    context: TrailblazeToolExecutionContext,
  ): TrailblazeToolResult {
    val description = PlaywrightExecutableTool.describeTarget(nodeSelector, ref)
    reasoning?.let { Console.log("### Reasoning: $it") }
    Console.log("### Clicking on: $description")
    var target: Locator? = null
    var navigationUrl: String? = null
    return try {
      val urlBefore = page.url()
      val (locator, error) =
        PlaywrightExecutableTool.validateAndResolveRef(page, ref, description, context, nodeSelector)
      if (error != null) return error
      target = locator
      // Use Playwright's locator-driven click rather than `page.mouse().click(coords)`.
      // This inherits the full actionability gate (visible, stable, **receives events**,
      // enabled) so clicks that would silently land on the wrong element (e.g. an
      // animating overlay, a slot-projected label, a not-yet-hydrated component) error
      // out loudly with a diagnostic instead of firing into the void. The agent already
      // pre-resolves the click center in `resolveToolCenter` for the screenshot overlay,
      // so we don't need to compute coords here.
      // A click that starts a navigation also waits for it to load, so a timeout can come after
      // the click landed. Diagnosing the target then would describe the page it opened instead.
      // Only the page's own navigations count: an iframe that reloads itself says nothing here.
      // `frame()` throws for a request sent before its frame exists, which is never the main frame.
      val onRequest = Consumer<Request> {
        if (it.isNavigationRequest && runCatching { it.frame() }.getOrNull() == page.mainFrame()) {
          navigationUrl = it.url()
        }
      }
      page.onRequest(onRequest)
      try {
        locator!!.click()
      } finally {
        page.offRequest(onRequest)
      }

      val urlAfter = page.url()
      val navigated = urlBefore != urlAfter
      val feedback = buildString {
        append("Clicked on '$description'.")
        if (navigated) {
          append(" Page navigated to: $urlAfter")
        } else {
          append(" Page URL unchanged ($urlAfter). The click may have triggered an in-page update.")
        }
      }
      Console.log("### Click result: $feedback")
      TrailblazeToolResult.Success(message = feedback)
    } catch (e: TimeoutError) {
      // Playwright's message is its retry log, often thousands of characters that bury the reason.
      val url = navigationUrl
      val reason = if (url == null) target?.let { PlaywrightExecutableTool.describeWhyNotActionable(page, it) } else null
      TrailblazeToolResult.Error.ExceptionThrown(
        when {
          url != null -> "Click on '$description' timed out while the page was loading $url, which did not finish in time."
          reason != null -> "Click failed on '$description': timed out because $reason."
          else -> "Click failed on '$description': ${e.message}"
        },
      )
    } catch (e: Exception) {
      TrailblazeToolResult.Error.ExceptionThrown("Click failed on '$description': ${e.message}")
    }
  }
}
