package xyz.block.trailblaze.revyl.tools

import ai.koog.agents.core.tools.annotations.LLMDescription
import kotlinx.serialization.Serializable
import xyz.block.trailblaze.revyl.RevylCliClient
import xyz.block.trailblaze.toolcalls.TrailblazeToolClass
import xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult
import xyz.block.trailblaze.util.Console

/**
 * Taps (or long-presses) a UI element on the Revyl cloud device using natural
 * language targeting.
 *
 * The Revyl CLI resolves the target description to screen coordinates via
 * AI-powered visual grounding, then performs the action. The resolved (x, y)
 * coordinates are returned in the success message for overlay rendering.
 */
@Serializable
@TrailblazeToolClass("revyl_tap")
@LLMDescription("Tap or long-press an element.")
data class RevylNativeTapTool(
  @param:LLMDescription("Element to tap, in natural language, e.g. 'Sign In button'.")
  val target: String,
  @param:LLMDescription("Long-press instead of tap.")
  val longPress: Boolean = false,
  override val reasoning: String? = null,
) : RevylExecutableTool() {

  override suspend fun executeWithRevyl(
    revylClient: RevylCliClient,
    context: TrailblazeToolExecutionContext,
  ): TrailblazeToolResult {
    val action = if (longPress) "Long-pressing" else "Tapping"
    Console.log("### $action: $target")
    val result = if (longPress) revylClient.longPress(target) else revylClient.tapTarget(target)
    val verb = if (longPress) "Long-pressed" else "Tapped"
    val feedback = "$verb '$target' at (${result.x}, ${result.y})"
    Console.log("### $feedback")
    return TrailblazeToolResult.Success(message = feedback)
  }
}
