package xyz.block.trailblaze.toolcalls.commands

import ai.koog.agents.core.tools.annotations.LLMDescription
import kotlinx.serialization.Serializable
import xyz.block.trailblaze.toolcalls.ExecutableTrailblazeTool
import xyz.block.trailblaze.toolcalls.ReasoningTrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolClass
import xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult
import xyz.block.trailblaze.util.Console
import xyz.block.trailblaze.viewhierarchy.NativeViewHierarchyDetail

/**
 * Tool that lets the LLM request a higher-fidelity view hierarchy on the next turn.
 *
 * By default, the LLM receives a compact element list optimized for token efficiency.
 * When the LLM needs more information (e.g., element positions for spatial reasoning,
 * or full structural nodes for layout understanding), it calls this tool to upgrade
 * the next snapshot.
 *
 * The enrichment applies to the **entire** view hierarchy for one turn, then automatically
 * reverts to the compact default. This progressive disclosure pattern keeps most turns
 * lightweight while giving the LLM access to full detail when needed.
 */
@Serializable
@TrailblazeToolClass("requestViewHierarchyDetails", isRecordable = false)
@LLMDescription(
  """
Request additional detail in the next view hierarchy snapshot.
Call this when you need more information than the default compact element list provides.
The next turn's view hierarchy will include the requested details for ALL elements,
then automatically revert to the compact format on subsequent turns.

Available detail types:
- FULL_HIERARCHY: Include ALL nodes (even empty structural containers), bounding box
  coordinates (center: x,y), dimensions (size: WxH), and enabled/disabled state.
  Useful for spatial reasoning, determining element positions, understanding layout
  structure, or disambiguating visually similar elements by location.
- OFFSCREEN_ELEMENTS: Include all elements regardless of screen position.
  By default, elements outside the screen are filtered out to save tokens. Request this
  to see all elements with offscreen ones annotated as (offscreen). Useful when you need
  to find elements that require scrolling to reach.
""",
)
class RequestViewHierarchyDetailsTrailblazeTool(
  @param:LLMDescription(
    "List of detail types to include in the next view hierarchy. " +
      "Supported: FULL_HIERARCHY, OFFSCREEN_ELEMENTS (or both). " +
      "Example: [\"FULL_HIERARCHY\"] to see all nodes with bounds and dimensions, " +
      "or [\"OFFSCREEN_ELEMENTS\"] to see elements outside the visible screen area.",
  )
  val include: List<NativeViewHierarchyDetail>,
  override val reasoning: String? = null,
) : ExecutableTrailblazeTool, ReasoningTrailblazeTool {

  override suspend fun execute(
    toolExecutionContext: TrailblazeToolExecutionContext,
  ): TrailblazeToolResult {
    reasoning?.let { Console.log("### Reasoning: $it") }
    Console.log("### Requesting enriched view hierarchy: ${include.joinToString(", ")}")

    if (include.isEmpty()) {
      return TrailblazeToolResult.Error.ExceptionThrown(
        "No detail types specified. Provide at least one detail type (e.g., FULL_HIERARCHY).",
      )
    }

    // The actual detail forwarding is handled by MaestroTrailblazeAgent
    // after this tool executes — it reads `include` and stores it as
    // pendingViewHierarchyDetails. This tool just validates and returns.

    val detailNames = include.joinToString(", ") { it.name }
    return TrailblazeToolResult.Success(
      message = "The next view hierarchy will include: $detailNames. " +
        "Proceed with your next action — the enriched snapshot will be provided automatically.",
    )
  }
}
