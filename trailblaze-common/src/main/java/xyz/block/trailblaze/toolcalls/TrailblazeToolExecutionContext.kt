package xyz.block.trailblaze.toolcalls

import xyz.block.trailblaze.MaestroTrailblazeAgent
import xyz.block.trailblaze.api.ScreenState
import xyz.block.trailblaze.devices.TrailblazeDeviceInfo
import xyz.block.trailblaze.logs.model.TraceId

/**
 * Context for handling Trailblaze tools.
 * Todo: Update this to use the PromptStepState since that contains the screen state and llm response id
 */
class TrailblazeToolExecutionContext(
  val screenState: ScreenState?,
  val traceId: TraceId?,
  val trailblazeAgent: MaestroTrailblazeAgent,
  val trailblazeDeviceInfo: TrailblazeDeviceInfo,
)
