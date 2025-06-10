package xyz.block.trailblaze.toolcalls

import xyz.block.trailblaze.api.ScreenState

/**
 * Context for handling Trailblaze tools.
 */
data class TrailblazeToolExecutionContext(
  val trailblazeTool: TrailblazeTool,
  val screenState: ScreenState?,
  val llmResponseId: String?,
)
