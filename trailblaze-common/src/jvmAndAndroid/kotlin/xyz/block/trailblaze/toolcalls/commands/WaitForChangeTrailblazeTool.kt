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
  "Wait for the UI to settle after an action (a screen loads, content updates, a list scrolls). " +
    "Returns as soon as the UI is quiet, so it cannot wait out a change that has not started yet. " +
    "To wait out a spinner, use assertNotVisibleWithText.",
)
data class WaitForChangeTrailblazeTool(
  @LLMDescription("Max wait in ms. Default 8000.")
  val timeoutMs: Long = 8000,
  @LLMDescription("Time with no UI events that counts as settled, in ms. Default 300.")
  val quietWindowMs: Long = 300,
  @LLMDescription(
    "If true, timing out before the UI changes and settles fails; if false, it succeeds. Default true.",
  )
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
