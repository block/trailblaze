package xyz.block.trailblaze.playwright.tools

import ai.koog.agents.core.tools.annotations.LLMDescription
import com.microsoft.playwright.Locator
import com.microsoft.playwright.Page
import com.microsoft.playwright.assertions.LocatorAssertions
import com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat
import kotlinx.serialization.Serializable
import xyz.block.trailblaze.api.TrailblazeNodeSelector
import xyz.block.trailblaze.toolcalls.ReasoningTrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolClass
import xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult
import xyz.block.trailblaze.util.Console

@Serializable
@TrailblazeToolClass("web_verifyListVisible", isVerification = true)
@LLMDescription(
  """
Verify that a list or group of elements contains the expected items.
Checks that each expected item text is visible within the container element.
""",
)
data class PlaywrightNativeVerifyListVisibleTool(
  @param:LLMDescription(
    "Element ID (e.g., 'e5'), ARIA descriptor (e.g., 'list'), " +
      "or CSS selector with css= prefix (e.g., 'css=#my-list').",
  )
  val ref: String? = null,
  @param:LLMDescription("The expected item texts that should be visible in the list.")
  val items: List<String>,
  override val reasoning: String? = null,
  val nodeSelector: TrailblazeNodeSelector? = null,
) : PlaywrightExecutableTool, ReasoningTrailblazeTool {
  override val targetRef: String? get() = ref
  override val targetNodeSelector: TrailblazeNodeSelector? get() = nodeSelector
  override fun withNodeSelector(selector: TrailblazeNodeSelector): PlaywrightExecutableTool =
    PlaywrightNativeVerifyListVisibleTool(ref = null, items = items, reasoning = reasoning, nodeSelector = selector)

  companion object {
    /**
     * Per-item auto-wait budget. Long enough to absorb rows hydrating after the
     * container renders; short enough that a list missing N items reports in ~2N
     * seconds rather than N default assertion timeouts.
     */
    internal const val ITEM_VISIBILITY_TIMEOUT_MS: Double = 2_000.0
  }

  override suspend fun executeWithPlaywright(
    page: Page,
    context: TrailblazeToolExecutionContext,
  ): TrailblazeToolResult {
    val description = PlaywrightExecutableTool.describeTarget(nodeSelector, ref)
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
      assertThat(container!!).isVisible()

      // Auto-wait per item instead of a fail-fast count() probe: rows in server-backed
      // lists routinely hydrate after the container renders, and a snapshot check at
      // call time flags them missing. Each item gets a bounded wait so a genuinely
      // missing list doesn't pay the full default assertion timeout per entry.
      val missingItems = mutableListOf<String>()
      for (item in items) {
        try {
          // Filter to VISIBLE matches before narrowing. Taking `.first()` of the raw text
          // match pins the assertion to the first node in DOM order, so a hidden duplicate
          // ahead of the real row (`<li hidden>Latte</li><li>Latte</li>`) reports the item
          // missing. The contract is "some visible row shows this text", not "the first
          // node carrying it is visible".
          assertThat(
            container.getByText(item)
              .filter(Locator.FilterOptions().setVisible(true))
              .first(),
          ).isVisible(
            LocatorAssertions.IsVisibleOptions().setTimeout(ITEM_VISIBILITY_TIMEOUT_MS),
          )
        } catch (_: AssertionError) {
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
