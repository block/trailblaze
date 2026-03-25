package xyz.block.trailblaze.revyl.tools

import ai.koog.agents.core.tools.annotations.LLMDescription
import kotlinx.serialization.Serializable
import xyz.block.trailblaze.revyl.RevylCliClient
import xyz.block.trailblaze.toolcalls.TrailblazeToolClass
import xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult
import xyz.block.trailblaze.util.Console

/**
 * Double-taps a UI element on the Revyl cloud device using natural language targeting.
 *
 * Useful for zoom-in gestures on maps, image viewers, and double-tap UI patterns.
 * The Revyl CLI resolves the target description to screen coordinates via
 * AI-powered visual grounding, then performs the double-tap.
 */
@Serializable
@TrailblazeToolClass("revyl_doubleTap")
@LLMDescription(
  "Double-tap a UI element on the device screen. Describe the element in natural language. " +
    "Use for zoom-in gestures on maps, image viewers, or any double-tap UI pattern.",
)
class RevylNativeDoubleTapTool(
  @param:LLMDescription("Element to double-tap, described in natural language.")
  val target: String,
  override val reasoning: String? = null,
) : RevylExecutableTool() {

  override suspend fun executeWithRevyl(
    revylClient: RevylCliClient,
    context: TrailblazeToolExecutionContext,
  ): TrailblazeToolResult {
    Console.log("### Double-tapping: $target")
    val result = revylClient.doubleTap(target)
    val feedback = "Double-tapped '$target' at (${result.x}, ${result.y})"
    Console.log("### $feedback")
    return TrailblazeToolResult.Success(message = feedback)
  }
}
