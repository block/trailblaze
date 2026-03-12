package xyz.block.trailblaze.playwright.tools

import ai.koog.agents.core.tools.annotations.LLMDescription
import com.microsoft.playwright.Page
import kotlinx.serialization.Serializable
import xyz.block.trailblaze.playwright.ViewHierarchyDetail
import xyz.block.trailblaze.toolcalls.ReasoningTrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolClass
import xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult
import xyz.block.trailblaze.util.Console

/**
 * Tool that lets the LLM request a higher-fidelity view hierarchy on the next turn.
 *
 * By default, the LLM receives a compact ARIA element list optimized for token efficiency.
 * When the LLM needs more information (e.g., element positions for spatial reasoning,
 * or CSS selectors for elements without ARIA semantics), it calls this tool to upgrade
 * the next snapshot.
 *
 * The enrichment applies to the **entire** view hierarchy for one turn, then automatically
 * reverts to the compact default. This progressive disclosure pattern keeps most turns
 * lightweight while giving the LLM access to full detail when needed.
 *
 * Example usage by LLM:
 * - Needs to determine which of two similar buttons is on the left → requests BOUNDS
 * - Needs to verify an element is within the viewport → requests BOUNDS
 * - Spatial reasoning ("click the button below the form") → requests BOUNDS
 * - Needs to interact with a div that has no ARIA label → requests CSS_SELECTORS
 * - Page has poor accessibility and elements can't be found by role → requests CSS_SELECTORS
 */
@Serializable
@TrailblazeToolClass("playwright_request_details", isRecordable = false)
@LLMDescription(
  """
Request additional detail in the next view hierarchy snapshot.
Call this when you need more information than the default compact element list provides.
The next turn's view hierarchy will include the requested details for ALL elements,
then automatically revert to the compact format on subsequent turns.

Available detail types:
- BOUNDS: Include bounding box coordinates {x,y,w,h} for each element.
  Useful for spatial reasoning, determining element positions, checking viewport visibility,
  or disambiguating visually similar elements by location.
- CSS_SELECTORS: Include CSS selectors for elements and surface hidden elements.
  Adds [css=...] annotations to existing elements that have an HTML id or data-testid.
  Also discovers elements that are invisible in the default compact list (e.g., unnamed
  divs with id or data-testid attributes) and lists them with their CSS selectors.
  Use the css= prefix in ref fields to target these elements (e.g., ref: 'css=#my-panel').
""",
)
class PlaywrightNativeRequestDetailsTool(
  @param:LLMDescription(
    "List of detail types to include in the next view hierarchy. " +
      "Supported: [\"BOUNDS\"], [\"CSS_SELECTORS\"], or both [\"BOUNDS\", \"CSS_SELECTORS\"]. " +
      "Example: [\"CSS_SELECTORS\"] to discover elements without ARIA semantics.",
  )
  val include: List<ViewHierarchyDetail>,
  override val reasoning: String? = null,
) : PlaywrightExecutableTool, ReasoningTrailblazeTool {

  override suspend fun executeWithPlaywright(
    page: Page,
    context: TrailblazeToolExecutionContext,
  ): TrailblazeToolResult {
    reasoning?.let { Console.log("### Reasoning: $it") }
    Console.log("### Requesting enriched view hierarchy: ${include.joinToString(", ")}")

    if (include.isEmpty()) {
      return TrailblazeToolResult.Error.ExceptionThrown(
        "No detail types specified. Provide at least one detail type (e.g., BOUNDS).",
      )
    }

    // The actual detail forwarding is handled by PlaywrightTrailblazeAgent
    // after this tool executes — it reads `include` and calls
    // browserManager.requestDetails(). This tool just validates and returns.

    val detailNames = include.joinToString(", ") { it.name }
    return TrailblazeToolResult.Success(
      message = "The next view hierarchy will include: $detailNames. " +
        "Proceed with your next action — the enriched snapshot will be provided automatically.",
    )
  }
}
