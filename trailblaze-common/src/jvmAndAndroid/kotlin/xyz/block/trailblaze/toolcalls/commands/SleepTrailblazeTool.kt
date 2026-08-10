package xyz.block.trailblaze.toolcalls.commands

import ai.koog.agents.core.tools.annotations.LLMDescription
import kotlin.time.TimeSource
import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable
import xyz.block.trailblaze.toolcalls.HostLocalExecutableTrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolClass
import xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult

/**
 * The only tool that consumes wall-clock time unconditionally.
 *
 * `wait` and `waitForChange` are settles: they return the moment the UI is quiet, so on a static
 * screen both come back in ~150ms no matter what duration was asked for. That makes them useless
 * for the case this tool exists for — letting something happen OFF-screen (a server-side value
 * propagating) before the trail navigates to the screen that reads it.
 *
 * Implemented as a [HostLocalExecutableTrailblazeTool] so `BaseTrailblazeAgent.runTrailblazeTools`
 * runs it in-process for EVERY agent, before driver-specific dispatch. That is what makes the
 * duration driver-independent: there is no path on which it can degrade to a Maestro
 * `WaitForAnimationToEnd`, the defect this tool exists to avoid.
 *
 * On the trail path it is also RPC-free. The MCP `step` / `trailblaze tool` path is not:
 * `TrailblazeMcpBridgeImpl.executeHostLocalTool` handles only Playwright, so on an on-device driver
 * the call goes over RPC and is bounded by `OnDeviceRpcTimeouts.HANDLER_AWAIT_CAP_MS` (15 min). The
 * sleep still runs to completion there — [MAX_DURATION_MS] keeps every legal duration well inside
 * that ceiling — but the transport cap does exist on that path.
 *
 * `surfaceToLlm = false`: a fixed sleep is correct when an author knows about an off-screen
 * dependency, and almost always wrong when picked autonomously — the agent should assert on the
 * element it expects instead. Hidden from the LLM toolbox; still callable from hand-authored
 * trail YAML and scripted tools, and still recorded.
 */
@Serializable
@TrailblazeToolClass("sleep", surfaceToLlm = false)
@LLMDescription(
  """
Block for a fixed amount of wall-clock time, always consuming the full duration regardless of
what the UI is doing. Use only to let something happen off-screen (e.g. a server-side value
propagating) before navigating to the screen that reads it. To wait for something visible,
assert on that element instead — and to wait for the UI to settle, use waitForChange.
    """,
)
data class SleepTrailblazeTool(
  @LLMDescription("How long to sleep, in milliseconds. Must be between 100 and 300000 (5 minutes). Default 5000.")
  val durationMs: Long = 5_000,
) : HostLocalExecutableTrailblazeTool {

  override val advertisedToolName: String get() = "sleep"

  override suspend fun execute(
    toolExecutionContext: TrailblazeToolExecutionContext,
  ): TrailblazeToolResult {
    // Out of range is an error, not a clamp: silently coercing a too-long sleep would return
    // before the requested duration while reporting success — the defect this tool removes.
    if (durationMs !in MIN_DURATION_MS..MAX_DURATION_MS) {
      return TrailblazeToolResult.Error.ExceptionThrown(
        errorMessage = "sleep requires durationMs between $MIN_DURATION_MS and $MAX_DURATION_MS, but was $durationMs",
        command = this,
      )
    }
    val startMark = TimeSource.Monotonic.markNow()
    delay(durationMs)
    val elapsedMs = startMark.elapsedNow().inWholeMilliseconds
    return TrailblazeToolResult.Success(
      message = "Slept ${elapsedMs}ms (requested ${durationMs}ms)",
    )
  }

  companion object {
    /** Anything shorter is a no-op that reads as a wait, and is almost always a units slip (`5` meaning 5 seconds). */
    const val MIN_DURATION_MS = 100L

    /**
     * Held under the 10-minute run-poll inactivity window (`DaemonClient.RUN_POLL_TIMEOUT_MS`): a
     * host-local sleep emits no progress, so a longer one is indistinguishable from a wedged run.
     */
    const val MAX_DURATION_MS = 300_000L
  }
}
