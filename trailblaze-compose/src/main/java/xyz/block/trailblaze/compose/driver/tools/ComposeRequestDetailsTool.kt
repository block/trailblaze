package xyz.block.trailblaze.compose.driver.tools

import ai.koog.agents.core.tools.annotations.LLMDescription
import kotlinx.serialization.Serializable
import xyz.block.trailblaze.compose.driver.ComposeViewHierarchyDetail
import xyz.block.trailblaze.compose.target.ComposeTestTarget
import xyz.block.trailblaze.toolcalls.TrailblazeToolClass
import xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult
import xyz.block.trailblaze.util.Console

/**
 * Tool that lets the LLM request a higher-fidelity view hierarchy on the next turn.
 *
 * By default, the LLM receives a compact semantics tree optimized for token efficiency. When the
 * LLM needs more information (e.g., element positions for spatial reasoning), it calls this tool to
 * upgrade the next snapshot.
 *
 * The enrichment applies to the **entire** view hierarchy for one turn, then automatically reverts
 * to the compact default. This progressive disclosure pattern keeps most turns lightweight while
 * giving the LLM access to full detail when needed.
 */
@Serializable
@TrailblazeToolClass("compose_request_details", isRecordable = false)
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
""",
)
class ComposeRequestDetailsTool(
  @param:LLMDescription(
    "List of detail types to include in the next view hierarchy. " +
      "Supported: [\"BOUNDS\"]. " +
      "Example: [\"BOUNDS\"] to see element positions.",
  )
  val include: List<ComposeViewHierarchyDetail>,
) : ComposeExecutableTool {

  override suspend fun executeWithCompose(
    target: ComposeTestTarget,
    context: TrailblazeToolExecutionContext,
  ): TrailblazeToolResult {
    Console.log("### Requesting enriched view hierarchy: ${include.joinToString(", ")}")

    if (include.isEmpty()) {
      return TrailblazeToolResult.Error.ExceptionThrown(
        "No detail types specified. Provide at least one detail type (e.g., BOUNDS).",
      )
    }

    val detailNames = include.joinToString(", ") { it.name }
    return TrailblazeToolResult.Success(
      message =
        "The next view hierarchy will include: $detailNames. " +
          "Proceed with your next action — the enriched snapshot will be provided automatically.",
    )
  }
}
