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
 * Taps a UI element on the Revyl cloud device using natural language targeting.
 *
 * The Revyl CLI resolves the target description to screen coordinates via
 * AI-powered visual grounding, then performs the tap. The resolved (x, y)
 * coordinates are returned in the success message for overlay rendering.
 */
@Serializable
@TrailblazeToolClass("revyl_tap")
@LLMDescription(
  "Tap a UI element on the device screen. Describe the element in natural language " +
    "(e.g. 'Sign In button', 'search icon', 'first product card').",
)
class RevylNativeTapTool(
  @param:LLMDescription("Element to tap, described in natural language.")
  val target: String,
  override val reasoning: String? = null,
) : RevylExecutableTool, ReasoningTrailblazeTool {

  override suspend fun executeWithRevyl(
    client: RevylCliClient,
    context: TrailblazeToolExecutionContext,
  ): TrailblazeToolResult {
    Console.log("### Tapping: $target")
    val result = client.tapTarget(target)
    val feedback = "Tapped '$target' at (${result.x}, ${result.y})"
    Console.log("### $feedback")
    return TrailblazeToolResult.Success(message = feedback)
  }
}
