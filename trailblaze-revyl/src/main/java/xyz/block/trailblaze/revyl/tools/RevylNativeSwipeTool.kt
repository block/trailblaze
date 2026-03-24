package xyz.block.trailblaze.revyl.tools

import ai.koog.agents.core.tools.annotations.LLMDescription
import kotlinx.serialization.Serializable
import xyz.block.trailblaze.host.revyl.RevylCliClient
import xyz.block.trailblaze.toolcalls.ReasoningTrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolClass
import xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult
import xyz.block.trailblaze.util.Console

/**
 * Swipes on the Revyl cloud device in a given direction.
 *
 * Optionally starts from a targeted element. Returns the origin coordinates.
 */
@Serializable
@TrailblazeToolClass("revyl_swipe")
@LLMDescription(
  "Swipe on the device screen in a direction (up, down, left, right). " +
    "Optionally start from a specific element.",
)
class RevylNativeSwipeTool(
  @param:LLMDescription("Swipe direction: 'up', 'down', 'left', or 'right'.")
  val direction: String,
  @param:LLMDescription("Optional element to start the swipe from.")
  val target: String = "",
  override val reasoning: String? = null,
) : RevylExecutableTool, ReasoningTrailblazeTool {

  override suspend fun executeWithRevyl(
    client: RevylCliClient,
    context: TrailblazeToolExecutionContext,
  ): TrailblazeToolResult {
    Console.log("### Swiping $direction")
    val result = client.swipe(direction, target.ifBlank { null })
    val feedback = "Swiped $direction from (${result.x}, ${result.y})"
    Console.log("### $feedback")
    return TrailblazeToolResult.Success(message = feedback)
  }
}
