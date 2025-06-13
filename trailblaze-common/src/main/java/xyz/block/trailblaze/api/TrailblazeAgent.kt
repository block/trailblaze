package xyz.block.trailblaze.api

import xyz.block.trailblaze.toolcalls.TrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult

interface TrailblazeAgent {

  fun runTrailblazeTools(
    tools: List<TrailblazeTool>,
    llmResponseId: String? = null,
    screenState: ScreenState? = null,
  ): Pair<List<TrailblazeTool>, TrailblazeToolResult>
}
