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
@LLMDescription(
  """
Take a snapshot of the current page state and save it with the provided screen name.
This captures a screenshot and the page's accessibility tree for logging and debugging.
""",
)
class PlaywrightNativeSnapshotTool(
  @param:LLMDescription("Name for the screen being captured (e.g., 'login_page', 'dashboard').")
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
