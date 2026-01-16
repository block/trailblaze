package xyz.block.trailblaze.toolcalls.commands

import ai.koog.agents.core.tools.annotations.LLMDescription
import kotlinx.serialization.Serializable
import xyz.block.trailblaze.toolcalls.ExecutableTrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolClass
import xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult

@Serializable
@TrailblazeToolClass("takeSnapshot")
@LLMDescription(
  """
This tool will take a snapshot of the current page and save it with the provided screen name.
  """,
)
class TakeSnapshotTool(
  @LLMDescription("Name for the screen being captured (e.g., 'login_screen', 'payment_confirmation').")
  val screenName: String,
  @LLMDescription("Optional description of what this snapshot captures or why it was taken.")
  val description: String? = null,
) : ExecutableTrailblazeTool {

  override suspend fun execute(toolExecutionContext: TrailblazeToolExecutionContext): TrailblazeToolResult {
    saveSnapshot(toolExecutionContext)
    return TrailblazeToolResult.Success
  }

  private fun saveSnapshot(context: TrailblazeToolExecutionContext) {
    val descriptionText = description?.let { " - $it" } ?: ""
    println("### Taking snapshot and saving as $screenName$descriptionText")
    
    // Get current session from the agent
    val session = context.trailblazeAgent.sessionProvider.invoke()
    
    // Capture a fresh screenshot from the device using the screenStateProvider
    val screenStateProvider = context.screenStateProvider
    if (screenStateProvider == null) {
      println("⚠️  No screenStateProvider available - snapshot not saved")
      return
    }
    
    val freshScreenState = screenStateProvider()

    val savedFilename = context.trailblazeAgent.trailblazeLogger.logSnapshot(session, freshScreenState, screenName)
    
    if (savedFilename == null) {
      println("⚠️  No screenshot available - snapshot not saved")
    } else {
      println("✅ Snapshot saved: $savedFilename")
    }
  }
}
