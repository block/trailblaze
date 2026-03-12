package xyz.block.trailblaze.playwright.tools

import ai.koog.agents.core.tools.annotations.LLMDescription
import com.microsoft.playwright.Page
import kotlinx.serialization.Serializable
import xyz.block.trailblaze.api.TrailblazeNodeSelector
import xyz.block.trailblaze.toolcalls.ReasoningTrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolClass
import xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult
import xyz.block.trailblaze.util.Console

@Serializable
@TrailblazeToolClass("playwright_click")
@LLMDescription(
  """
Click on a web element identified by its element ID, ARIA descriptor, or CSS selector.
Use the element ID from the page elements list (e.g., 'e5'), an ARIA descriptor (e.g., 'button "Submit"'),
or a CSS selector prefixed with 'css=' (e.g., 'css=#my-button', 'css=[data-testid="submit"]').
""",
)
class PlaywrightNativeClickTool(
  @param:LLMDescription(
    "Element ID (e.g., 'e5'), ARIA descriptor (e.g., 'button \"Submit\"'), " +
      "or CSS selector with css= prefix (e.g., 'css=#my-id', 'css=[data-testid=\"btn\"]').",
  )
  val ref: String,
  @param:LLMDescription("Human-readable description of the element being clicked, for logging.")
  val element: String = "",
  override val reasoning: String? = null,
  val nodeSelector: TrailblazeNodeSelector? = null,
) : PlaywrightExecutableTool, ReasoningTrailblazeTool {
  override val elementDescriptor: String? get() = element.ifBlank { null }
  override val targetRef: String get() = ref
  override fun withNodeSelector(selector: TrailblazeNodeSelector): PlaywrightExecutableTool =
    PlaywrightNativeClickTool(ref = ref, element = element, reasoning = reasoning, nodeSelector = selector)

  override suspend fun executeWithPlaywright(
    page: Page,
    context: TrailblazeToolExecutionContext,
  ): TrailblazeToolResult {
    val description = element.ifBlank { ref }
    reasoning?.let { Console.log("### Reasoning: $it") }
    Console.log("### Clicking on: $description")
    return try {
      val urlBefore = page.url()
      val (locator, error) =
        PlaywrightExecutableTool.validateAndResolveRef(page, ref, description, context, nodeSelector)
      if (error != null) return error
      locator!!.click()

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
    } catch (e: Exception) {
      TrailblazeToolResult.Error.ExceptionThrown("Click failed on '$description': ${e.message}")
    }
  }
}
