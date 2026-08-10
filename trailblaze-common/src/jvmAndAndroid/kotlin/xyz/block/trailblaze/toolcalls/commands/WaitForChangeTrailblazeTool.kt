package xyz.block.trailblaze.toolcalls.commands

import ai.koog.agents.core.tools.annotations.LLMDescription
import kotlin.time.TimeSource
import kotlinx.serialization.Serializable
import maestro.orchestra.WaitForAnimationToEndCommand
import xyz.block.trailblaze.toolcalls.ExecutableTrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolClass
import xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult
import xyz.block.trailblaze.util.Console

@Serializable
@TrailblazeToolClass("waitForChange")
@LLMDescription(
  """
Wait until the UI has settled after your action. Use this instead of a fixed-duration wait when
you've triggered an action (a new screen loads, content updates, a list scrolls) and want to block
until the UI is quiet again. Returns immediately if the UI is already settled when this runs.
Known limit: it returns as soon as the UI is quiet, so it cannot wait out a change that has not
started yet. For that, poll the actual state — assertNotVisibleWithText to wait a loading indicator
out, or take a fresh snapshot and assertVisible a ref that is present (assertVisible checks the
current snapshot and does not itself wait for an element to appear).
    """,
)
data class WaitForChangeTrailblazeTool(
  @LLMDescription("Maximum time to wait for the screen to change, in milliseconds. Default 8000.")
  val timeoutMs: Long = 8000,
  @LLMDescription("Time with no further UI events required to consider the screen settled, in milliseconds. Default 300.")
  val quietWindowMs: Long = 300,
  @LLMDescription("When true, timing out with no change is an error. When false, a timeout is treated as a generous settle and succeeds. Default true.")
  val requireChange: Boolean = true,
) : ExecutableTrailblazeTool {

  override suspend fun execute(
    toolExecutionContext: TrailblazeToolExecutionContext,
  ): TrailblazeToolResult {
    val agent = toolExecutionContext.maestroTrailblazeAgent
    val driverResult = agent?.waitForTreeChange(
      timeoutMs = timeoutMs,
      quietWindowMs = quietWindowMs,
      requireChange = requireChange,
      traceId = toolExecutionContext.traceId,
    )
    if (driverResult != null) return driverResult

    // Unsupported driver (iOS / host / non-accessibility Android): degrade to an animation-end
    // settle so the caller still gets a pause rather than a hard failure.
    Console.log("waitForChange: driver has no change detection, falling back to a settle with a ${timeoutMs}ms ceiling")
    val agentForFallback = agent
      ?: return TrailblazeToolResult.Error.ExceptionThrown(
        errorMessage = "waitForChange could not run: no agent available to perform the wait",
      )
    val startMark = TimeSource.Monotonic.markNow()
    val result = agentForFallback.runMaestroCommands(
      maestroCommands = listOf(WaitForAnimationToEndCommand(timeout = timeoutMs.toString())),
      traceId = toolExecutionContext.traceId,
    )
    if (result is TrailblazeToolResult.Success) {
      // `timeoutMs` is the ceiling the settle may take, never the time it spent (#5279).
      val elapsedMs = startMark.elapsedNow().inWholeMilliseconds
      return TrailblazeToolResult.Success(
        message = "waitForChange degraded to a settle, which returned after ${elapsedMs}ms (ceiling ${timeoutMs}ms)",
      )
    }
    return result
  }
}
