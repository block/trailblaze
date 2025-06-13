package xyz.block.trailblaze.toolcalls

import xyz.block.trailblaze.api.ScreenState
import xyz.block.trailblaze.api.TrailblazeAgent

/**
 * Context for handling Trailblaze tools.
 */
class TrailblazeToolExecutionContext(
  val trailblazeTool: TrailblazeTool,
  val screenState: ScreenState?,
  val llmResponseId: String?,
  val trailblazeAgentProvider: () -> TrailblazeAgent,
)
