package xyz.block.trailblaze.api

import xyz.block.trailblaze.toolcalls.TrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult
import xyz.block.trailblaze.utils.ElementComparator

interface TrailblazeAgent {

  fun runTrailblazeTools(
    tools: List<TrailblazeTool>,
    llmResponseId: String? = null,
    screenState: ScreenState? = null, // TODO: This should probably be a provider instead of a static state, and should be required
    elementComparator: ElementComparator,
  ): Pair<List<TrailblazeTool>, TrailblazeToolResult>
}
