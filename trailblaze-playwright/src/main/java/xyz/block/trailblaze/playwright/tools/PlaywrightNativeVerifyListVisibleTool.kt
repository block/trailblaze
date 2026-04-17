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
@TrailblazeToolClass("web_verify_list_visible")
@LLMDescription(
  """
Verify that a list or group of elements contains the expected items.
Checks that each expected item text is visible within the container element.
""",
)
class PlaywrightNativeVerifyListVisibleTool(
  @param:LLMDescription(
    "Element ID (e.g., 'e5'), ARIA descriptor (e.g., 'list'), " +
      "or CSS selector with css= prefix (e.g., 'css=#my-list').",
  )
  val ref: String,
  @param:LLMDescription("The expected item texts that should be visible in the list.")
  val items: List<String>,
  @param:LLMDescription("Human-readable description of the list being verified, for logging.")
  val element: String = "",
  override val reasoning: String? = null,
  val nodeSelector: TrailblazeNodeSelector? = null,
) : PlaywrightExecutableTool, ReasoningTrailblazeTool {
  override val elementDescriptor: String? get() = element.ifBlank { null }
  override val targetRef: String get() = ref
  override fun withNodeSelector(selector: TrailblazeNodeSelector): PlaywrightExecutableTool =
    PlaywrightNativeVerifyListVisibleTool(ref = ref, items = items, element = element, reasoning = reasoning, nodeSelector = selector)

  override suspend fun executeWithPlaywright(
    page: Page,
    context: TrailblazeToolExecutionContext,
  ): TrailblazeToolResult {
    val description = element.ifBlank { ref }
    reasoning?.let { Console.log("### Reasoning: $it") }
    Console.log("### Verifying list '$description' contains: $items")
    if (items.isEmpty()) {
      return TrailblazeToolResult.Error.ExceptionThrown(
        "No items provided to verify. Provide at least one expected item.",
      )
    }
    return try {
      val (container, error) =
        PlaywrightExecutableTool.validateAndResolveRef(page, ref, description, context, nodeSelector)
      if (error != null) return error
      assertThat(container!!.first()).isVisible()

      val missingItems = mutableListOf<String>()
      for (item in items) {
        val itemLocator = container.getByText(item)
        if (itemLocator.count() == 0) {
          missingItems.add(item)
        }
      }

      if (missingItems.isNotEmpty()) {
        return TrailblazeToolResult.Error.ExceptionThrown(
          "Assertion failed: list '$description' is missing items: ${missingItems.joinToString(", ") { "'$it'" }}.",
        )
      }

      TrailblazeToolResult.Success(
        message =
          "Verified list '$description' contains all ${items.size} expected items.",
      )
    } catch (e: AssertionError) {
      TrailblazeToolResult.Error.ExceptionThrown(
        "Assertion failed: list '$description' is not visible on the page.",
      )
    } catch (e: Exception) {
      TrailblazeToolResult.Error.ExceptionThrown("Verify list failed: ${e.message}")
    }
  }
}
