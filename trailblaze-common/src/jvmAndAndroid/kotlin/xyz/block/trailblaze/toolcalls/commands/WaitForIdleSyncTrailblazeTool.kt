package xyz.block.trailblaze.toolcalls.commands

import ai.koog.agents.core.tools.annotations.LLMDescription
import kotlin.time.TimeSource
import kotlinx.serialization.Serializable
import maestro.orchestra.Command
import maestro.orchestra.WaitForAnimationToEndCommand
import xyz.block.trailblaze.toolcalls.MapsToMaestroCommands
import xyz.block.trailblaze.toolcalls.TrailblazeToolClass
import xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult
import xyz.block.trailblaze.toolcalls.isSuccess

@Serializable
@TrailblazeToolClass("wait")
@LLMDescription(
  "Wait on a loading screen until the UI goes idle, up to a ceiling; returns as soon as it is " +
    "idle. Prefer this over pressing back while something loads. To wait out a spinner, use " +
    "assertNotVisibleWithText.",
)
data class WaitForIdleSyncTrailblazeTool(
  @LLMDescription("Max seconds to wait (a ceiling, not a fixed pause). Default 5.")
  val timeToWaitInSeconds: Int = 5,
) : MapsToMaestroCommands() {
  override fun toMaestroCommands(): List<Command> = listOf(
    WaitForAnimationToEndCommand(
      timeout = (timeToWaitInSeconds.toLong() * 1000L).toString(),
    ),
  )

  override suspend fun execute(
    toolExecutionContext: TrailblazeToolExecutionContext,
  ): TrailblazeToolResult {
    val startMark = TimeSource.Monotonic.markNow()
    val result = super.execute(toolExecutionContext)
    if (result.isSuccess()) {
      // Report what actually elapsed. The old message stated the requested ceiling, which on a
      // static screen overstated the real settle by ~30x and read in the log as a wait that
      // had happened (#5279).
      val elapsedMs = startMark.elapsedNow().inWholeMilliseconds
      return TrailblazeToolResult.Success(
        message = "Settled after ${elapsedMs}ms (ceiling ${timeToWaitInSeconds}s)",
      )
    }
    return result
  }
}
