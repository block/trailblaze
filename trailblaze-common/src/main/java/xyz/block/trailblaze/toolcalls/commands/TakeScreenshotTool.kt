package xyz.block.trailblaze.toolcalls.commands

import ai.koog.agents.core.tools.annotations.LLMDescription
import kotlinx.serialization.Serializable
import xyz.block.trailblaze.toolcalls.ExecutableTrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolClass
import xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult

@Serializable
@TrailblazeToolClass("takeScreenshot")
@LLMDescription(
  """
This tool will take a screenshot of the current page and save it with the provided filename.
  """,
)
class TakeScreenshotTool(
  @LLMDescription("Filename for the screenshot.")
  val filename: String,
) : ExecutableTrailblazeTool {

  override suspend fun execute(toolExecutionContext: TrailblazeToolExecutionContext): TrailblazeToolResult {
    saveScreenshot(toolExecutionContext)
    return TrailblazeToolResult.Success
  }

  private fun saveScreenshot(context: TrailblazeToolExecutionContext) {
    println("### Taking screenshot and saving as $filename")
    val screenState = context.screenState
      ?: error("No screen state available in context $context")

    context.trailblazeAgent.trailblazeLogger.logScreenState(screenState)
  }
}
