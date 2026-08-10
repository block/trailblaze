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
  """
Settle on a loading screen: block until the UI goes quiet, up to a ceiling. This returns as soon
as the UI is idle, so on an already-static screen it returns almost immediately rather than
waiting the full time — it is a ceiling, not a duration. Use when you see a loading screen —
prefer this over pressing the back button. If you are waiting for something specific to appear,
assert on that element instead: a quiet UI does not mean the thing you expect has arrived.
    """,
)
data class WaitForIdleSyncTrailblazeTool(
  @LLMDescription("Ceiling on how long to settle for, in seconds — not a guaranteed duration. Default Value: 5 seconds.")
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
