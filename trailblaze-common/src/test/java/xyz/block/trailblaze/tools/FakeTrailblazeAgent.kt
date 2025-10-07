package xyz.block.trailblaze.tools

import maestro.orchestra.Command
import xyz.block.trailblaze.MaestroTrailblazeAgent
import xyz.block.trailblaze.api.ScreenState
import xyz.block.trailblaze.logs.model.TraceId
import xyz.block.trailblaze.toolcalls.TrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult
import xyz.block.trailblaze.utils.ElementComparator

class FakeTrailblazeAgent : MaestroTrailblazeAgent() {
  override suspend fun executeMaestroCommands(
    commands: List<Command>,
    traceId: TraceId?,
  ): TrailblazeToolResult = TrailblazeToolResult.Success

  override fun runTrailblazeTools(
    tools: List<TrailblazeTool>,
    traceId: TraceId?,
    screenState: ScreenState?,
    elementComparator: ElementComparator,
  ): Pair<List<TrailblazeTool>, TrailblazeToolResult> {
    error("FakeTrailblazeAgent does not have an implementation for runTrailblazeTools()")
  }
}
