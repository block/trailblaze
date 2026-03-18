package xyz.block.trailblaze.compose.driver.tools

import ai.koog.agents.core.tools.annotations.LLMDescription
import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable
import xyz.block.trailblaze.compose.target.ComposeTestTarget
import xyz.block.trailblaze.toolcalls.TrailblazeToolClass
import xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult
import xyz.block.trailblaze.util.Console

@Serializable
@TrailblazeToolClass("compose_wait")
@LLMDescription(
  """
Wait for a specified number of seconds before continuing.
Use this when you need to wait for animations or async operations to complete.
""",
)
class ComposeWaitTool(
  @param:LLMDescription("Number of seconds to wait (e.g., 1, 2, 5). Maximum 30 seconds.")
  val seconds: Int = 1,
) : ComposeExecutableTool {

  override suspend fun executeWithCompose(
    target: ComposeTestTarget,
    context: TrailblazeToolExecutionContext,
  ): TrailblazeToolResult {
    val cappedSeconds = seconds.coerceIn(1, 30)
    Console.log("### Waiting for $cappedSeconds seconds")
    target.waitForIdle()
    delay(cappedSeconds * 1000L)
    target.waitForIdle()
    return TrailblazeToolResult.Success(message = "Waited $cappedSeconds seconds.")
  }
}
