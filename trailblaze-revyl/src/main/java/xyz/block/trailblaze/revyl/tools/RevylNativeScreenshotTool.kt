package xyz.block.trailblaze.revyl.tools

import ai.koog.agents.core.tools.annotations.LLMDescription
import kotlinx.serialization.Serializable
import xyz.block.trailblaze.host.revyl.RevylCliClient
import xyz.block.trailblaze.toolcalls.TrailblazeToolClass
import xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult
import xyz.block.trailblaze.util.Console

/**
 * Captures a screenshot of the Revyl cloud device screen.
 *
 * This tool is intended for internal use by the agent framework
 * and is not included in the LLM-facing tool set.
 */
@Serializable
@TrailblazeToolClass("revyl_screenshot")
@LLMDescription("Take a screenshot of the current device screen to see what's on it.")
class RevylNativeScreenshotTool(
  override val reasoning: String? = null,
) : RevylExecutableTool() {

  override suspend fun executeWithRevyl(
    revylClient: RevylCliClient,
    context: TrailblazeToolExecutionContext,
  ): TrailblazeToolResult {
    Console.log("### Taking screenshot")
    revylClient.screenshot()
    return TrailblazeToolResult.Success(message = "Screenshot captured.")
  }
}
