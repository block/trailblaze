package xyz.block.trailblaze.toolcalls.commands

import ai.koog.agents.core.tools.annotations.LLMDescription
import kotlinx.serialization.Serializable
import maestro.orchestra.AssertConditionCommand
import maestro.orchestra.Condition
import maestro.orchestra.ElementSelector
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
  @param:LLMDescription("Filename for the screenshot.")
  val filename: String,
) : ExecutableTrailblazeTool {

  override suspend fun execute(toolExecutionContext: TrailblazeToolExecutionContext): TrailblazeToolResult {
    saveScreenshot(toolExecutionContext)
    return TrailblazeToolResult.Success
  }

  private suspend fun saveScreenshot(context: TrailblazeToolExecutionContext) {
    // Assertion that always passes to get the screenshot with no markup to be taken
    // Through the `traceId` we can correlate the tool log with the filename, screenshot and hierarchy
    context.trailblazeAgent.runMaestroCommands(
      listOf(
        AssertConditionCommand(
          condition = Condition(
            visible = ElementSelector(
              index = "0"
            )
          ),
        )
      ),
      traceId = context.traceId
    )
  }
}
