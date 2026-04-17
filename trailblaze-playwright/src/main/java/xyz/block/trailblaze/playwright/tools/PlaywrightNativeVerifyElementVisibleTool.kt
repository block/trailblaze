package xyz.block.trailblaze.playwright.tools

import ai.koog.agents.core.tools.annotations.LLMDescription
import com.microsoft.playwright.Page
import com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat
import kotlinx.serialization.Serializable
import xyz.block.trailblaze.api.TrailblazeNodeSelector
import xyz.block.trailblaze.toolcalls.ReasoningTrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolClass
import xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult
import xyz.block.trailblaze.util.Console

@Serializable
@TrailblazeToolClass("web_verify_element_visible")
@LLMDescription(
  """
Verify that an element identified by its element ID, ARIA descriptor, or CSS selector is visible on the page.
This is a test assertion — it will fail the test if the element is not visible.
""",
)
class PlaywrightNativeVerifyElementVisibleTool(
  @param:LLMDescription(
    "Element ID (e.g., 'e5'), ARIA descriptor (e.g., 'button \"Submit\"'), " +
      "or CSS selector with css= prefix (e.g., 'css=#my-element').",
  )
  val ref: String,
  @param:LLMDescription("Human-readable description of the element being verified, for logging.")
  val element: String = "",
  override val reasoning: String? = null,
  val nodeSelector: TrailblazeNodeSelector? = null,
) : PlaywrightExecutableTool, ReasoningTrailblazeTool {
  override val elementDescriptor: String? get() = element.ifBlank { null }
  override val targetRef: String get() = ref
  override fun withNodeSelector(selector: TrailblazeNodeSelector): PlaywrightExecutableTool =
    PlaywrightNativeVerifyElementVisibleTool(ref = ref, element = element, reasoning = reasoning, nodeSelector = selector)

  override suspend fun executeWithPlaywright(
    page: Page,
    context: TrailblazeToolExecutionContext,
  ): TrailblazeToolResult {
    val description = element.ifBlank { ref }
    reasoning?.let { Console.log("### Reasoning: $it") }
    Console.log("### Verifying element visible: $description")
    return try {
      val (locator, error) =
        PlaywrightExecutableTool.validateAndResolveRef(page, ref, description, context, nodeSelector)
      if (error != null) return error
      assertThat(locator!!.first()).isVisible()
      TrailblazeToolResult.Success(message = "Verified element '$description' is visible.")
    } catch (e: AssertionError) {
      TrailblazeToolResult.Error.ExceptionThrown(
        "Assertion failed: element '$description' is not visible on the page.",
      )
    } catch (e: Exception) {
      TrailblazeToolResult.Error.ExceptionThrown("Verify element failed: ${e.message}")
    }
  }
}
