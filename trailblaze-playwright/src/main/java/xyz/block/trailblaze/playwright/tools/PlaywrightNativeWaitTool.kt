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
@TrailblazeToolClass("playwright_wait")
@LLMDescription(
  """
Wait for a specified number of seconds before continuing.
Use this when you need to wait for animations, network requests, or page transitions to complete.
""",
)
class PlaywrightNativeWaitTool(
  @param:LLMDescription("Number of seconds to wait (e.g., 1, 2, 5). Maximum 30 seconds.")
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
