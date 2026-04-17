package xyz.block.trailblaze.toolcalls.commands

import ai.koog.agents.core.tools.annotations.LLMDescription
import kotlinx.serialization.Serializable
import maestro.orchestra.Command
import maestro.orchestra.WaitForAnimationToEndCommand
import xyz.block.trailblaze.AgentMemory
import xyz.block.trailblaze.toolcalls.MapsToMaestroCommands
import xyz.block.trailblaze.toolcalls.TrailblazeToolClass
import xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult
import xyz.block.trailblaze.toolcalls.isSuccess

@Serializable
@TrailblazeToolClass("wait")
@LLMDescription(
  """
Use this when you think you see a loading screen.
This will force the app to wait for a specified amount of time.
Prefer using this over the back button.
    """,
)
data class WaitForIdleSyncTrailblazeTool(
  @LLMDescription("Unit: seconds. Default Value: 5 seconds.")
  val timeToWaitInSeconds: Int = 5,
) : MapsToMaestroCommands() {
  override fun toMaestroCommands(memory: AgentMemory): List<Command> = listOf(
    WaitForAnimationToEndCommand(
      timeout = timeToWaitInSeconds.toLong() * 1000L,
    ),
  )

  override suspend fun execute(
    toolExecutionContext: TrailblazeToolExecutionContext,
  ): TrailblazeToolResult {
    val result = super.execute(toolExecutionContext)
    if (result.isSuccess()) {
      return TrailblazeToolResult.Success(message = "Waited $timeToWaitInSeconds seconds")
    }
    return result
  }
}
