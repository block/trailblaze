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
    screenState: ScreenState? = null,
    elementComparator: ElementComparator,
    /**
     * Optional provider to capture a fresh screen state on demand.
     * Used by tools like TakeSnapshotTool that need to capture the current device state
     * at the moment the tool is executed, rather than using the cached screenState.
     */
    screenStateProvider: (() -> ScreenState)? = null,
  ): RunTrailblazeToolsResult
}
