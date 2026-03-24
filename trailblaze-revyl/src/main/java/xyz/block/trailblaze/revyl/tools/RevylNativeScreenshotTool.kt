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
 * Captures a screenshot of the Revyl cloud device screen.
 */
@Serializable
@TrailblazeToolClass("revyl_screenshot")
@LLMDescription("Take a screenshot of the current device screen to see what's on it.")
class RevylNativeScreenshotTool(
  override val reasoning: String? = null,
) : RevylExecutableTool, ReasoningTrailblazeTool {

  override suspend fun executeWithRevyl(
    client: RevylCliClient,
    context: TrailblazeToolExecutionContext,
  ): TrailblazeToolResult {
    Console.log("### Taking screenshot")
    client.screenshot()
    return TrailblazeToolResult.Success(message = "Screenshot captured.")
  }
}
