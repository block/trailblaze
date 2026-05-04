package xyz.block.trailblaze.toolcalls.commands

import ai.koog.agents.core.tools.annotations.LLMDescription
import kotlinx.serialization.Serializable
import xyz.block.trailblaze.toolcalls.ExecutableTrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolClass
import xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult
import xyz.block.trailblaze.util.Console

@Serializable
@TrailblazeToolClass("takeSnapshot")
@LLMDescription(
  """
Take a snapshot of the current page and save it under the provided screen name.
  """,
)
class TakeSnapshotTool(
  @param:LLMDescription("Name for the screen being captured (e.g., 'login_screen', 'payment_confirmation').")
  val screenName: String,
  @param:LLMDescription("Optional description of what this snapshot captures or why it was taken.")
  val description: String? = null,
) : ExecutableTrailblazeTool {
  companion object {
    private const val NO_SCREEN_STATE_PROVIDER_MESSAGE =
      "Snapshot '%s' not saved: no screen state provider available."
    private const val NO_SCREENSHOT_AVAILABLE_MESSAGE =
      "Snapshot '%s' captured (no screenshot available)."
    private const val SNAPSHOT_SAVED_MESSAGE = "Snapshot '%s' saved as %s."
  }

  override suspend fun execute(toolExecutionContext: TrailblazeToolExecutionContext): TrailblazeToolResult {
    return try {
      saveSnapshot(toolExecutionContext)
    } catch (e: Exception) {
      TrailblazeToolResult.Error.ExceptionThrown("Snapshot failed: ${e.message}")
    }
  }

  private fun saveSnapshot(context: TrailblazeToolExecutionContext): TrailblazeToolResult {
    val descriptionText = description?.let { " - $it" } ?: ""
    Console.log("### Taking snapshot and saving as $screenName$descriptionText")
    val screenStateProvider = context.screenStateProvider
    if (screenStateProvider == null) {
      Console.log("⚠️  No screenStateProvider available - snapshot not saved")
      return TrailblazeToolResult.Success(
        message = NO_SCREEN_STATE_PROVIDER_MESSAGE.format(screenName),
      )
    }
    val freshScreenState = screenStateProvider()
    val session = context.sessionProvider.invoke()

    val savedFilename = context.trailblazeLogger.logSnapshot(
      session = session,
      screenState = freshScreenState,
      displayName = screenName,
      traceId = context.traceId,
    )

    return if (savedFilename == null) {
      Console.log("⚠️  No screenshot available - snapshot not saved")
      TrailblazeToolResult.Success(
        message = NO_SCREENSHOT_AVAILABLE_MESSAGE.format(screenName),
      )
    } else {
      Console.log("✅ Snapshot saved: $savedFilename")
      TrailblazeToolResult.Success(
        message = SNAPSHOT_SAVED_MESSAGE.format(screenName, savedFilename),
      )
    }
  }
}
