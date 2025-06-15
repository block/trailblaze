package xyz.block.trailblaze.toolcalls

import xyz.block.trailblaze.MaestroTrailblazeAgent
import xyz.block.trailblaze.api.ScreenState

/**
 * Context for handling Trailblaze tools.
 */
class TrailblazeToolExecutionContext(
  val screenState: ScreenState?,
  val llmResponseId: String?,
  val trailblazeAgent: MaestroTrailblazeAgent,
)
