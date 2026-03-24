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
 * Opens a URL or deep link on the Revyl cloud device.
 */
@Serializable
@TrailblazeToolClass("revyl_navigate")
@LLMDescription("Open a URL or deep link on the device.")
class RevylNativeNavigateTool(
  @param:LLMDescription("The URL or deep link to open.")
  val url: String,
  override val reasoning: String? = null,
) : RevylExecutableTool, ReasoningTrailblazeTool {

  override suspend fun executeWithRevyl(
    client: RevylCliClient,
    context: TrailblazeToolExecutionContext,
  ): TrailblazeToolResult {
    Console.log("### Navigating to: $url")
    client.navigate(url)
    return TrailblazeToolResult.Success(message = "Navigated to $url")
  }
}
