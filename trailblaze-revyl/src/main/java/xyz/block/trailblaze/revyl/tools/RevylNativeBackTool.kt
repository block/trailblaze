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
 * Presses the device back button on the Revyl cloud device.
 */
@Serializable
@TrailblazeToolClass("revyl_back")
@LLMDescription("Press the device back button to go to the previous screen.")
class RevylNativeBackTool(
  override val reasoning: String? = null,
) : RevylExecutableTool, ReasoningTrailblazeTool {

  override suspend fun executeWithRevyl(
    client: RevylCliClient,
    context: TrailblazeToolExecutionContext,
  ): TrailblazeToolResult {
    Console.log("### Pressing back")
    val result = client.back()
    return TrailblazeToolResult.Success(message = "Pressed back at (${result.x}, ${result.y})")
  }
}
