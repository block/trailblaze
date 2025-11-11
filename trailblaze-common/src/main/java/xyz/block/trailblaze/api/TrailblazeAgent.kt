package xyz.block.trailblaze.api

import xyz.block.trailblaze.logs.model.TraceId
import xyz.block.trailblaze.toolcalls.TrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult
import xyz.block.trailblaze.utils.ElementComparator

interface TrailblazeAgent {

  data class RunTrailblazeToolsResult(
    val inputTools: List<TrailblazeTool>,
    val executedTools: List<TrailblazeTool>,
    val result: TrailblazeToolResult,
  )
  fun runTrailblazeTools(
    tools: List<TrailblazeTool>,
    /** Used to associate the tool calls with downstream logs. */
    traceId: TraceId? = null,
    screenState: ScreenState? = null, // TODO: This should probably be a provider instead of a static state, and should be required
    elementComparator: ElementComparator,
  ): RunTrailblazeToolsResult
}
