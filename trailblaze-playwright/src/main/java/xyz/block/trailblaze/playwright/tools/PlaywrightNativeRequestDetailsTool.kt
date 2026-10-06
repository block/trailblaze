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
 *
 * Occluded elements are filtered by default because Playwright's actionability check refuses
 * to click a non-topmost element, so a click on one times out.
 */
@Serializable
@TrailblazeToolClass("web_requestDetails", isRecordable = false)
@LLMDescription(
  """
Add detail to the NEXT snapshot only (all elements), then revert to the compact list.
- BOUNDS: {x,y,w,h} per element, for spatial reasoning or telling similar elements apart.
- CSS_SELECTORS: [css=...] for elements with an id or data-testid, including ones hidden from the compact list; target them with ref 'css=#id'.
- OFFSCREEN_ELEMENTS: include elements outside the viewport, marked (offscreen).
- OCCLUDED_ELEMENTS: include elements covered by a modal/popup/toast (clicks on them time out).
""",
)
data class PlaywrightNativeRequestDetailsTool(
  @param:LLMDescription("Detail types to include.")
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
