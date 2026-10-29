package xyz.block.trailblaze.playwright.tools

import ai.koog.agents.core.tools.annotations.LLMDescription
import com.microsoft.playwright.Page
import kotlinx.serialization.Serializable
import xyz.block.trailblaze.toolcalls.ReasoningTrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolClass
import xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult
import xyz.block.trailblaze.toolcalls.commands.TakeSnapshotTool
import xyz.block.trailblaze.toolcalls.isSuccess
import xyz.block.trailblaze.util.Console

@Serializable
@TrailblazeToolClass("web_snapshot")
@LLMDescription("Save a named screenshot and accessibility tree of the current page to the logs.")
data class PlaywrightNativeSnapshotTool(
  @param:LLMDescription("Screen name, e.g. 'login_page'.")
  val screenName: String,
  override val reasoning: String? = null,
) : PlaywrightExecutableTool, ReasoningTrailblazeTool {

  override suspend fun executeWithPlaywright(
    page: Page,
    context: TrailblazeToolExecutionContext,
  ): TrailblazeToolResult {
    reasoning?.let { Console.log("### Reasoning: $it") }
    Console.log("### Taking web snapshot via TakeSnapshotTool: $screenName")
    return try {
      val snapshotResult =
        TakeSnapshotTool(
          screenName = screenName,
          description = "Captured from playwright_snapshot.",
        ).execute(context)
      if (!snapshotResult.isSuccess()) {
        return snapshotResult
      }
      TrailblazeToolResult.Success(message = "Snapshot '$screenName' captured.")
    } catch (e: Exception) {
      TrailblazeToolResult.Error.ExceptionThrown("Snapshot failed: ${e.message}")
    }
  }
}
