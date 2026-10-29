package xyz.block.trailblaze.playwright.tools

import ai.koog.agents.core.tools.annotations.LLMDescription
import com.microsoft.playwright.Page
import kotlinx.serialization.Serializable
import xyz.block.trailblaze.toolcalls.ReasoningTrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolClass
import xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult
import xyz.block.trailblaze.util.Console

@Serializable
@TrailblazeToolClass("web_wait")
@LLMDescription("Pause for a fixed number of seconds, e.g. for an animation or page load to finish.")
data class PlaywrightNativeWaitTool(
  @param:LLMDescription("Seconds to wait, 1 to 30 (default 1).")
  val seconds: Int = 1,
  override val reasoning: String? = null,
) : PlaywrightExecutableTool, ReasoningTrailblazeTool {

  override suspend fun executeWithPlaywright(
    page: Page,
    context: TrailblazeToolExecutionContext,
  ): TrailblazeToolResult {
    val cappedSeconds = seconds.coerceIn(1, 30)
    reasoning?.let { Console.log("### Reasoning: $it") }
    Console.log("### Waiting for $cappedSeconds seconds")
    page.waitForTimeout((cappedSeconds * 1000).toDouble())
    return TrailblazeToolResult.Success(message = "Waited $cappedSeconds seconds.")
  }
}
